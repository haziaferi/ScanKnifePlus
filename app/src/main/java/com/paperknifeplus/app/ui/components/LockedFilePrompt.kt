package com.paperknifeplus.app.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paperknifeplus.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The check a tool runs on the file it was given, one at a time: reading it, or trying a password in [LockedFilePrompt], whose Cancel and
 * Back [stop] it. [start] stops the check before it, so a new file's check replaces the old one. A stopped check never applies its result:
 * each check applies it only after a `withContext(Dispatchers.IO)` returns, which then throws CancellationException. Use on the main thread.
 */
@Stable
class FileCheck internal constructor(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** True from [start] until the check ends or is stopped: the tool shows that it is loading, and the prompt its spinner. */
    var running by mutableStateOf(false)
        private set

    /** Stops the running check, then runs [check]. */
    fun start(check: suspend () -> Unit) {
        stop()
        running = true
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                check()
            } finally {
                // A stopped check ends later, after the next may have started, so only the current one clears the state.
                if (job === coroutineContext[Job]) {
                    job = null
                    running = false
                }
            }
        }
        job = launched
        launched.start()
    }

    /** Cancels the running check, if any, so its result is never applied. */
    fun stop() {
        job?.cancel()
        job = null
        running = false
    }
}

/** A [FileCheck] that lives as long as the tool's screen. */
@Composable
fun rememberFileCheck(): FileCheck {
    val scope = rememberCoroutineScope()
    return remember(scope) { FileCheck(scope) }
}

/**
 * Applies a password check's outcome the one way every tool does: [onOpened] with what it read; for a wrong password [onWrongPassword],
 * where the prompt says it is incorrect; for a file that cannot be read, the tools' unreadable message and [onUnreadable]. There most tools
 * close the prompt and go back to choosing a file; the reader closes, Compare closes the prompt and leaves its cards as they were, and Merge
 * takes the file off its list. Inline, so the handlers can suspend and return from the check.
 */
internal inline fun <T> PasswordCheck<T>.resolve(
    context: Context,
    onWrongPassword: () -> Unit,
    onUnreadable: () -> Unit,
    onOpened: (T) -> Unit
) {
    when (this) {
        is PasswordCheck.Opened -> onOpened(value)
        PasswordCheck.WrongPassword -> onWrongPassword()
        PasswordCheck.Unreadable -> {
            Toast.makeText(context, toolText(context, R.string.tool_unreadable), Toast.LENGTH_LONG).show()
            onUnreadable()
        }
    }
}

/**
 * Asks for the password of [fileName]. The field is masked (with a show toggle) and kept out of the keyboard's learning. [isError] shows
 * "Incorrect password" until the user edits the field or submits again, so callers only need to set it after a failed attempt. While
 * [isLoading] (the password is being checked), Cancel and Back still [onDismiss] it, which stops the check ([FileCheck.stop]) and resets
 * the tool; only a tap outside does nothing then, so a stray touch cannot.
 */
@Composable
fun LockedFilePrompt(
    fileName: String,
    onDismiss: () -> Unit,
    onUnlocked: (String) -> Unit,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    isLoading: Boolean = false,
    isError: Boolean = false
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val showError = isError && !edited && !isLoading
    val submit = {
        edited = false
        onUnlocked(password)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = !isLoading, usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = accentColor, modifier = Modifier.size(28.dp))
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    stringResource(R.string.password_required),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    fileName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        edited = true
                    },
                    label = { Text(stringResource(R.string.password_pdf_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    isError = showError,
                    enabled = !isLoading,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) stringResource(R.string.password_hide_password) else stringResource(R.string.password_show_password)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!isLoading) submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        focusedLabelColor = accentColor,
                        cursorColor = accentColor
                    )
                )

                if (showError) {
                    Text(
                        stringResource(R.string.password_wrong),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Start).padding(start = 8.dp, top = 4.dp)
                    )
                }

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = submit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = MaterialTheme.colorScheme.onPrimary)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    } else {
                        Text(stringResource(R.string.action_unlock), fontWeight = FontWeight.Black)
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
