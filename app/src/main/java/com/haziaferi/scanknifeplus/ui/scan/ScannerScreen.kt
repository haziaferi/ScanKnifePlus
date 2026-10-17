package com.haziaferi.scanknifeplus.ui.scan

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import java.io.File

/** What the Scanner shows, in plain values, so the screen can be drawn without a camera. */
internal data class ScannerUi(
    val mode: CaptureMode = CaptureMode.SINGLE,
    val canChangeMode: Boolean = true,
    val autoCapture: Boolean = true,
    val liveDetection: Boolean = false,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val holdingSteady: Boolean = false,
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
 * The Scanner (prototype "Scanner", proposal 3f): the camera [preview] full screen under a close button, the auto-capture and torch toggles, the
 * holding-steady cue (only with auto-capture on), the capture modes and a footer with the shutter exactly centred between the page stack and
 * Done (3a). Colours are the palette's camera colours, which are the same in Light and OLED.
 */
@Composable
internal fun ScannerScreen(ui: ScannerUi, actions: ScannerActions, preview: @Composable BoxScope.() -> Unit) {
    val k = LocalScanPalette.current.camera
    val single = ui.mode == CaptureMode.SINGLE
    Box(Modifier.fillMaxSize().background(k.cam)) {
        when {
            ui.permissionGranted -> preview()
            ui.permissionBlocked -> PermissionPrompt(R.string.scan_permission_blocked, R.string.scan_permission_settings, actions.onOpenSettings)
            else -> PermissionPrompt(R.string.scan_permission_body, R.string.scan_permission_allow, actions.onAllowCamera)
        }

        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val close = stringResource(R.string.scan_close)
                Box(
                    Modifier
                        .size(48.dp)
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
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (ui.autoCapture) k.onBg else k.offBg)
                            .toggleable(value = ui.autoCapture, role = Role.Switch, onValueChange = { actions.onToggleAuto() })
                            .semantics { stateDescription = label }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, style = scanText(14, FontWeight.Bold), color = if (ui.autoCapture) k.onFg else k.onCam)
                    }
                }
                if (ui.torchAvailable) {
                    Spacer(Modifier.size(8.dp))
                    val torch = stringResource(R.string.scan_torch)
                    val torchState = stringResource(if (ui.torchOn) R.string.scan_state_on else R.string.scan_state_off)
                    Box(
                        Modifier
                            .size(48.dp)
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
            if (ui.autoCapture && ui.liveDetection && ui.holdingSteady) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(k.cam.copy(alpha = 0.65f))
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(k.outline))
                    Text(
                        stringResource(if (ui.mode == CaptureMode.BOOK) R.string.scan_hold_steady_book else R.string.scan_hold_steady),
                        style = scanText(14, FontWeight.SemiBold), color = k.onCam
                    )
                }
            }
            if (ui.lowLight) {
                Text(
                    stringResource(R.string.scan_low_light),
                    style = scanText(14, FontWeight.SemiBold),
                    color = k.onCam,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(k.cam.copy(alpha = 0.65f))
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            CameraMessages(ui, actions)
        }

        // Without the permission the prompt stands alone; nothing could be captured anyway.
        if (ui.permissionGranted) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            ModeSelector(ui, actions.onMode)
            Spacer(Modifier.size(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(k.ctrlOffBg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .heightIn(min = 144.dp)
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)
            ) {
                if (!single) {
                    PageStack(ui, actions.onReview, Modifier.align(Alignment.CenterStart))
                    DoneButton(ui.count > 0, actions.onReview, Modifier.align(Alignment.CenterEnd))
                }
                Shutter(single, ui.shutterEnabled, actions.onShutter, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ModeSelector(ui: ScannerUi, onMode: (CaptureMode) -> Unit) {
    val k = LocalScanPalette.current.camera
    val group = stringResource(R.string.scan_mode_group)
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        FlowRow(
            Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(k.cam.copy(alpha = 0.6f))
                .padding(4.dp)
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
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .then(if (selected) Modifier.background(k.onCam.copy(alpha = 0.22f)) else Modifier)
                        .selectable(selected = selected, enabled = ui.canChangeMode || selected, role = Role.RadioButton) { onMode(mode) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val alpha = if (ui.canChangeMode || selected) 1f else 0.6f
                    Text(stringResource(label), style = scanText(14, FontWeight.Bold), color = k.onCam.copy(alpha = alpha))
                }
            }
        }
    }
}

@Composable
private fun Shutter(single: Boolean, enabled: Boolean, onShutter: () -> Unit, modifier: Modifier) {
    val k = LocalScanPalette.current.camera
    val description = stringResource(if (single) R.string.scan_shutter_single else R.string.scan_shutter)
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(4.dp, k.shutter.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)
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
            Text("${ui.count}", style = scanText(12, FontWeight.ExtraBold), color = k.doneFg)
        }
    }
}

@Composable
private fun DoneButton(enabled: Boolean, onDone: () -> Unit, modifier: Modifier) {
    val k = LocalScanPalette.current.camera
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(k.doneBg.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onDone)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(stringResource(R.string.scan_done), style = scanText(15, FontWeight.Bold), color = k.doneFg)
    }
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
        Text(text, style = scanText(14, FontWeight.SemiBold), color = k.onCam, modifier = Modifier.padding(vertical = 6.dp))
        if (retry != null || dismiss != null) {
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dismiss?.let { BannerButton(stringResource(R.string.scan_dismiss), it) }
                retry?.let { BannerButton(stringResource(R.string.scan_retry), it) }
            }
        }
    }
}

@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    val k = LocalScanPalette.current.camera
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = scanText(15, FontWeight.ExtraBold), color = k.onCam)
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
            stringResource(R.string.scan_permission_title), style = scanText(18, FontWeight.ExtraBold), color = k.onCam,
            textAlign = TextAlign.Center
        )
        Text(stringResource(body), style = scanText(15, FontWeight.Medium), color = k.onCam, textAlign = TextAlign.Center)
        Box(
            Modifier
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(k.doneBg)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(button), style = scanText(16, FontWeight.Bold), color = k.doneFg)
        }
    }
}
