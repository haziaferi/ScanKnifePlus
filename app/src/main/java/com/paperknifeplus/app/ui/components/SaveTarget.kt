package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.inlineTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An empty file made for a tool's output where its save puts it: [uri] to write into, and [notice], said once as the save starts, if any. */
class SaveSpot(val uri: Uri, @StringRes val notice: Int? = null)

/** Where a tool's primary save puts its output, decided as the save starts. */
fun interface SavePlaces {
    /** Creates an empty file for an output named [name]; null when the system picker has to ask where instead. Blocking. */
    @WorkerThread
    fun create(name: String): SaveSpot?
}

/** The shell's save places (the user's folder, else the app's own storage); a host without them, such as one screen's test, asks the picker. */
val LocalSavePlaces = staticCompositionLocalOf { SavePlaces { null } }

/**
 * The one save path of a tool's output (ledger L8): [launch] makes the file where saves go now ([LocalSavePlaces]) and hands its URI to the
 * tool's callback, as the system picker would have; [launchElsewhere] asks the picker. A launch while a file is being made is ignored.
 */
class SaveTarget internal constructor(
    private val places: () -> SavePlaces,
    private val scope: CoroutineScope,
    private val context: Context,
    private val picker: ActivityResultLauncher<String>,
    private val result: () -> (Uri?) -> Unit,
) {
    private var making = false
    private var elsewhere = false

    /**
     * Saves an output named [name] where saves go now, or with the picker when there is no such place (or inside [elsewhere]). A screen
     * that goes while the file is being made gets nothing: the file is deleted again, and no picker opens on a launcher that is gone.
     */
    fun launch(name: String) {
        if (elsewhere) return launchElsewhere(name)
        if (making) return
        making = true
        scope.launch {
            val screen = coroutineContext[Job]
            val spot = try {
                // Not cancelled half way, so a file made just as the screen goes is known and removed, here or below.
                withContext(Dispatchers.IO + NonCancellable) {
                    places().create(name)?.takeUnless { made ->
                        (screen?.isActive == false).also { gone -> if (gone) deleteCreatedDocument(context, made.uri) }
                    }
                }
            } catch (e: CancellationException) {
                throw e // never a picker for a screen that is gone
            } catch (e: Exception) {
                Log.w(TAG, "No place for $name", e)
                null // the picker still saves it
            } finally {
                making = false
            }
            if (!isActive) {
                spot?.let { withContext(Dispatchers.IO + NonCancellable) { deleteCreatedDocument(context, it.uri) } }
                return@launch
            }
            if (spot == null) {
                picker.launch(name)
                return@launch
            }
            spot.notice?.let { Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show() }
            result()(spot.uri)
        }
    }

    /** Saves an output named [name] where the user picks with the system picker; the file is not copied into the folder. */
    fun launchElsewhere(name: String) = picker.launch(name)

    /** Runs [action], a tool's save action that calls [launch] before it returns, with that launch asking the picker ("Save elsewhere…"). */
    fun elsewhere(action: () -> Unit) {
        elsewhere = true
        try {
            action()
        } finally {
            elsewhere = false
        }
    }

    private companion object {
        const val TAG = "SaveTarget"
    }
}

/**
 * The [SaveTarget] of a tool's output of type [mime]; [onResult] gets the URI to write into, from wherever it was made, or null when the
 * picker was cancelled, exactly as a CreateDocument launcher's callback does.
 */
@Composable
fun rememberSaveTarget(mime: String, onResult: (Uri?) -> Unit): SaveTarget {
    val result by rememberUpdatedState(onResult)
    val places by rememberUpdatedState(LocalSavePlaces.current)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { result(it) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    return remember(picker, scope, context) { SaveTarget({ places }, scope, context, picker, { result }) }
}

/** "Save elsewhere…" (T3), the smaller way out under a save button: the same save through the system picker. */
@Composable
fun SaveElsewhereButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .inlineTarget()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.tool_save_elsewhere),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) colors.onBackground else colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
