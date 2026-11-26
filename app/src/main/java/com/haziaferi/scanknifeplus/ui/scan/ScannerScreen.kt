package com.haziaferi.scanknifeplus.ui.scan

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import coil.compose.AsyncImage
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.CameraText
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.inlineTarget
import java.io.File
import kotlinx.coroutines.delay

/**
 * What the Scanner shows, in plain values, so the screen can be drawn without a camera.
 *
 * @property holdProgress how far auto-capture's hold has run, from 0 to 1; the shutter's ring shows it while [holdingSteady]. Read only where
 *   the ring is drawn, so its steps (about ten a second) do not recompose the screen.
 * @property ringCloses how many auto-captures have been taken; each new one runs the ring to full before it clears.
 */
internal data class ScannerUi(
    val mode: CaptureMode = CaptureMode.SINGLE,
    val canChangeMode: Boolean = true,
    val autoCapture: Boolean = true,
    val liveDetection: Boolean = false,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val holdingSteady: Boolean = false,
    val holdProgress: () -> Float = { 0f },
    val ringCloses: Int = 0,
    val lowLight: Boolean = false,
    val count: Int = 0,
    val latestPage: File? = null,
    val shutterEnabled: Boolean = false,
    val permissionGranted: Boolean = true,
    val permissionBlocked: Boolean = false,
    val cameraProblem: Int? = null,
    val storeFailed: Boolean = false,
    val shotFailed: Boolean = false,
)

/** The Scanner's actions; every one is a plain callback so tests can count them. */
internal class ScannerActions(
    val onClose: () -> Unit = {},
    val onToggleAuto: () -> Unit = {},
    val onToggleTorch: () -> Unit = {},
    val onMode: (CaptureMode) -> Unit = {},
    val onShutter: () -> Unit = {},
    val onReview: () -> Unit = {},
    val onAllowCamera: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onRetryStore: () -> Unit = {},
    val onDismissStore: () -> Unit = {},
    val onDismissShot: () -> Unit = {},
)

/**
 * The Scanner (prototype "Scanner", proposal 3f): the camera [preview] full screen under a close button, the auto-capture and torch toggles,
 * the capture modes in a band below them, the holding-steady cue (only with auto-capture on) and a solid footer with the shutter exactly
 * centred between the page stack and Done (3a). While the page is held steady a ring on the shutter fills over the hold. Everything over the
 * picture sits on the camera colour at 75% (offBg). Colours are the palette's camera colours, which are the same in Light and OLED.
 */
@Composable
internal fun ScannerScreen(ui: ScannerUi, actions: ScannerActions, preview: @Composable BoxScope.() -> Unit) {
    val k = LocalScanPalette.current.camera
    val single = ui.mode == CaptureMode.SINGLE
    val holding = ui.autoCapture && ui.liveDetection && ui.holdingSteady
    // A dimmed shutter would not fire, so it shows no hold.
    val ring = rememberHoldRing(holding && ui.shutterEnabled, ui.holdProgress, ui.ringCloses)
    Box(Modifier.fillMaxSize().background(k.cam)) {
        when {
            ui.permissionGranted -> preview()
            ui.permissionBlocked -> PermissionPrompt(R.string.scan_permission_blocked, R.string.scan_permission_settings, actions.onOpenSettings)
            else -> PermissionPrompt(R.string.scan_permission_body, R.string.scan_permission_allow, actions.onAllowCamera)
        }

        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            // A small gap under the row: at large text every dp down to the band's end covers the picture.
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val close = stringResource(R.string.scan_close)
                Box(
                    Modifier
                        .size(TargetStep.ACT.hit)
                        .clip(CircleShape)
                        .background(k.offBg)
                        .semantics { contentDescription = close }
                        .clickable(role = Role.Button, onClick = actions.onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, null, tint = k.onCam)
                }
                Spacer(Modifier.weight(1f))
                if (ui.liveDetection) {
                    val label = stringResource(if (ui.autoCapture) R.string.scan_auto_on else R.string.scan_auto_off)
                    Box(
                        Modifier
                            .heightIn(min = TargetStep.ACT.drawn)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (ui.autoCapture) k.onBg else k.offBg)
                            .toggleable(value = ui.autoCapture, role = Role.Switch, onValueChange = { actions.onToggleAuto() })
                            .semantics { stateDescription = label }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = if (ui.autoCapture) k.onFg else k.onCam)
                    }
                }
                if (ui.torchAvailable) {
                    Spacer(Modifier.size(8.dp))
                    val torch = stringResource(R.string.scan_torch)
                    val torchState = stringResource(if (ui.torchOn) R.string.scan_state_on else R.string.scan_state_off)
                    Box(
                        Modifier
                            .size(TargetStep.ACT.hit)
                            .clip(CircleShape)
                            .background(if (ui.torchOn) k.torchBg else k.offBg)
                            .toggleable(value = ui.torchOn, role = Role.Switch, onValueChange = { actions.onToggleTorch() })
                            .semantics {
                                contentDescription = torch
                                stateDescription = torchState
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (ui.torchOn) Icons.Filled.FlashlightOn else Icons.Outlined.FlashlightOn, null,
                            tint = if (ui.torchOn) k.torchFg else k.onCam
                        )
                    }
                }
            }
            // Without the permission the prompt stands alone; nothing could be captured anyway.
            if (ui.permissionGranted) ModeSelector(ui, actions.onMode)
            // The banners with buttons come first, so at large text they stay clear of the footer.
            CameraMessages(ui, actions)
            if (holding) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(k.offBg)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(k.outline))
                    Text(
                        stringResource(if (ui.mode == CaptureMode.BOOK) R.string.scan_hold_steady_book else R.string.scan_hold_steady),
                        style = CameraText, color = k.onCam
                    )
                }
            }
            if (ui.lowLight) {
                Text(
                    stringResource(R.string.scan_low_light),
                    style = CameraText,
                    color = k.onCam,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(k.offBg)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        if (ui.permissionGranted) BoxWithConstraints(
            Modifier
                .align(Alignment.BottomCenter)
                .testTag(SCANNER_FOOTER_TAG)
                .fillMaxWidth()
                .background(k.cam)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = 144.dp)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)
        ) {
            if (!single) {
                // Each side slot ends where the centred shutter begins, so neither reaches its hit area (R14 rule 4).
                val side = Modifier.widthIn(max = (maxWidth - SHUTTER) / 2)
                PageStack(ui, actions.onReview, side.align(Alignment.CenterStart))
                DoneButton(ui.count > 0, actions.onReview, side.align(Alignment.CenterEnd))
            }
            Shutter(single, ui.shutterEnabled, ring, actions.onShutter, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun ModeSelector(ui: ScannerUi, onMode: (CaptureMode) -> Unit) {
    val k = LocalScanPalette.current.camera
    val group = stringResource(R.string.scan_mode_group)
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        // The band is the segments alone, each with 4 dp above and below its label: every dp of it covers the picture at large text.
        FlowRow(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(k.offBg)
                .selectableGroup()
                .semantics { contentDescription = group },
            horizontalArrangement = Arrangement.Center
        ) {
            for ((mode, label) in listOf(
                CaptureMode.SINGLE to R.string.scan_mode_single,
                CaptureMode.MULTI to R.string.scan_mode_multi,
                CaptureMode.BOOK to R.string.scan_mode_book
            )) {
                val selected = ui.mode == mode
                Box(
                    Modifier
                        .heightIn(min = TargetStep.ACT.drawn)
                        .clip(RoundedCornerShape(24.dp))
                        .then(if (selected) Modifier.background(k.onBg) else Modifier)
                        .selectable(selected = selected, enabled = ui.canChangeMode || selected, role = Role.RadioButton) { onMode(mode) }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val color = when {
                        selected -> k.onFg
                        ui.canChangeMode -> k.onCam
                        else -> k.onCam.copy(alpha = LOCKED_MODE_ALPHA)
                    }
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}

/** The label alpha of a capture mode locked after the first shot (disabled). */
internal const val LOCKED_MODE_ALPHA = 0.6f

/** The shutter (T1), drawn larger than its step. */
private val SHUTTER = 76.dp
private val SHUTTER_BORDER = 4.dp

/** How long the ring takes to reach each new hold progress: about the time between two analysis results. */
private const val RING_STEP_MS = 100

/** The test tag the shutter carries while it draws the hold's ring. */
internal const val SHUTTER_RING_TAG = "shutterRing"

/** The test tag of the footer under the shutter. */
internal const val SCANNER_FOOTER_TAG = "scannerFooter"

/**
 * The shutter's ring for a hold: it follows [progress] while [holding], easing over each step between analysis results. On each new
 * [closes] (an auto-capture firing) it runs to full, stays a moment and clears; a hold that breaks empties it at once. Returns the ring's
 * value to draw, or null while there is none. [progress] is read only here and in the drawing, so the hold does not recompose the screen.
 */
@Composable
private fun rememberHoldRing(holding: Boolean, progress: () -> Float, closes: Int): (() -> Float)? {
    val ring = remember { Animatable(0f) }
    val latest by rememberUpdatedState(progress)
    // A fire that happened before the screen came is not closed again.
    var closed by remember { mutableIntStateOf(closes) }
    val closing = closes != closed
    val step = tween<Float>(RING_STEP_MS, easing = LinearEasing)
    // Keyed on one phase, so the cue ending mid-close does not restart the close.
    val phase = when {
        closing -> RingPhase.CLOSING
        holding -> RingPhase.HOLDING
        else -> RingPhase.NONE
    }
    LaunchedEffect(phase) {
        when (phase) {
            RingPhase.CLOSING -> {
                ring.animateTo(1f, step)
                delay(RING_STEP_MS.toLong())
                ring.snapTo(0f)
                closed = closes
            }
            RingPhase.HOLDING -> snapshotFlow { latest() }.collect { ring.animateTo(it, step) }
            RingPhase.NONE -> ring.snapTo(0f)
        }
    }
    return if (phase == RingPhase.NONE) null else ({ ring.value })
}

private enum class RingPhase { NONE, HOLDING, CLOSING }

/**
 * The shutter. With [ring] (auto-capture's hold, 0 to 1) an arc in the live outline's colour fills its border clockwise from the top. The ring
 * is state, not an announcement: the holding-steady cue already speaks, and a progress value would be read out at every step.
 */
@Composable
private fun Shutter(single: Boolean, enabled: Boolean, ring: (() -> Float)?, onShutter: () -> Unit, modifier: Modifier) {
    val k = LocalScanPalette.current.camera
    val description = stringResource(if (single) R.string.scan_shutter_single else R.string.scan_shutter)
    Box(
        modifier
            .size(SHUTTER)
            .then(
                if (ring == null) Modifier else Modifier.testTag(SHUTTER_RING_TAG).drawWithContent {
                    drawContent()
                    val stroke = SHUTTER_BORDER.toPx()
                    drawArc(
                        k.outline, startAngle = -90f, sweepAngle = 360f * ring(), useCenter = false, topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke)
                    )
                }
            )
            .clip(CircleShape)
            .border(SHUTTER_BORDER, k.shutter.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onShutter),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(k.shutter.copy(alpha = if (enabled) 1f else 0.5f)))
    }
}

@Composable
private fun PageStack(ui: ScannerUi, onReview: () -> Unit, modifier: Modifier) {
    val k = LocalScanPalette.current.camera
    val description = pluralStringResource(R.plurals.scan_review_pages, ui.count, ui.count)
    Box(
        modifier
            .sizeIn(minWidth = 56.dp, minHeight = 64.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(enabled = ui.count > 0, role = Role.Button, onClick = onReview)
    ) {
        Box(Modifier.offset(x = 6.dp).size(44.dp, 58.dp).clip(RoundedCornerShape(4.dp)).background(k.onCam.copy(alpha = 0.45f)))
        Box(
            Modifier
                .offset(y = 6.dp)
                .size(44.dp, 58.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(k.onCam)
                .border(2.dp, k.onCam, RoundedCornerShape(4.dp))
        ) {
            ui.latestPage?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 6.dp, y = (-6).dp)
                .sizeIn(minWidth = 22.dp, minHeight = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(k.doneBg)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("${ui.count}", style = MaterialTheme.typography.labelSmall, color = k.doneFg)
        }
    }
}

@Composable
private fun DoneButton(enabled: Boolean, onDone: () -> Unit, modifier: Modifier) {
    val k = LocalScanPalette.current.camera
    Box(
        modifier
            .heightIn(min = TargetStep.ACT.drawn)
            .clip(RoundedCornerShape(24.dp))
            .background(k.doneBg.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onDone)
            .horizontalPaddingUpTo(20.dp, min = 8.dp)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.scan_done), style = MaterialTheme.typography.titleSmall, color = k.doneFg)
    }
}

/**
 * Horizontal padding of [max] on each side that gives way down to [min] when the width allowed cannot hold the content beside all of it,
 * so at large text a label loses padding before it breaks.
 */
private fun Modifier.horizontalPaddingUpTo(max: Dp, min: Dp): Modifier = layout { measurable, constraints ->
    val most = max.roundToPx()
    val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - measurable.maxIntrinsicWidth(constraints.maxHeight)) / 2 else most
    val pad = room.coerceIn(min.roundToPx(), most)
    val placeable = measurable.measure(constraints.offset(horizontal = -2 * pad))
    layout(constraints.constrainWidth(placeable.width + 2 * pad), placeable.height) { placeable.place(pad, 0) }
}

/** The camera's problems and the failed shots (7b) as banners under the header; text keeps its contrast on the camera scrim. */
@Composable
private fun CameraMessages(ui: ScannerUi, actions: ScannerActions) {
    val messages = listOfNotNull(
        ui.cameraProblem?.let { it to null },
        if (ui.storeFailed) R.string.scan_store_failed to (actions.onRetryStore to actions.onDismissStore) else null,
        if (ui.shotFailed) R.string.scan_shot_failed to (null to actions.onDismissShot) else null,
    )
    for ((text, buttons) in messages) {
        Banner(stringResource(text), retry = buttons?.first, dismiss = buttons?.second)
    }
}

@Composable
private fun Banner(text: String, retry: (() -> Unit)?, dismiss: (() -> Unit)?) {
    val k = LocalScanPalette.current.camera
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(k.cam.copy(alpha = 0.8f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, style = CameraText, color = k.onCam, modifier = Modifier.padding(vertical = 6.dp))
        if (retry != null || dismiss != null) {
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(TargetStep.INLINE_GAP)) {
                dismiss?.let { BannerButton(stringResource(R.string.scan_dismiss), it) }
                retry?.let { BannerButton(stringResource(R.string.scan_retry), it) }
            }
        }
    }
}

/** A banner's text button (T3). */
@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    val k = LocalScanPalette.current.camera
    Box(
        Modifier
            .inlineTarget()
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = k.onCam)
    }
}

@Composable
private fun BoxScope.PermissionPrompt(@StringRes body: Int, @StringRes button: Int, onClick: () -> Unit) {
    val k = LocalScanPalette.current.camera
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.scan_permission_title), style = MaterialTheme.typography.titleMedium, color = k.onCam,
            textAlign = TextAlign.Center
        )
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = k.onCam, textAlign = TextAlign.Center)
        Box(
            Modifier
                .heightIn(min = TargetStep.COMMIT.drawn)
                .clip(RoundedCornerShape(16.dp))
                .background(k.doneBg)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(button), style = MaterialTheme.typography.titleMedium, color = k.doneFg)
        }
    }
}
