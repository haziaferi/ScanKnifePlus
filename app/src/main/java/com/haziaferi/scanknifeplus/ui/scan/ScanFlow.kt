package com.haziaferi.scanknifeplus.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.haziaferi.scanknifeplus.scan.CameraPermission
import com.haziaferi.scanknifeplus.scan.camera.CameraError
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraState
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.PlusJakartaSans

/** The route name MainActivity shows [ScanFlow] for. */
const val SCAN_ROUTE = "scan"

/**
 * The document scanner: Scanner, then Review, then a PDF saved where the system file picker says (the storage folder comes with the storage
 * wiring). Given [importUris] (picked images), the flow starts in Review with them as its pages instead. The camera permission is asked as the
 * flow starts. [onSaved] gets the saved PDF, already in History; [onClose] runs when the user
 * leaves without saving, after the keep-or-discard prompt (decision 6) when there are pages. The flow holds the screen in portrait, as the
 * camera, the live outline and the crop all assume a portrait screen; Book mode reads how the phone is held from its sensors instead.
 */
@Composable
fun ScanFlow(onClose: () -> Unit, onSaved: (SavedPdf) -> Unit, importUris: List<Uri> = emptyList()) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val holder = remember(activity) { ViewModelProvider(activity)[ScanFlowHolder::class.java] }
    val model = remember(holder) {
        holder.model ?: ScanFlowModel(context.applicationContext).also {
            holder.model = it
            it.importImages(context.contentResolver, importUris)
        }
    }
    val finish = {
        holder.model = null
        onClose()
    }

    HoldPortrait(activity, holder)
    FollowDeviceTurns(context, model)

    var granted by remember { mutableStateOf(CameraPermission.isGranted(context)) }
    // Denied with "don't ask again" (or twice): the system no longer shows the request, so only the app's settings can grant it.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        blocked = !it && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    // Granted from the system settings while the app was in the background.
    LifecycleResumeEffect(Unit) {
        granted = CameraPermission.isGranted(context)
        onPauseOrDispose {}
    }

    var cropping by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var saveOptions by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var retakePrompt by remember { mutableStateOf(false) }
    // The options wait in the model, which outlives an activity recreated while the picker is open; saved state could reach the disk.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
        val options = model.pendingSave
        model.pendingSave = null
        when {
            uri == null -> {}
            options == null -> model.dropTarget(uri)
            else -> model.save(uri, options.first, options.second) { saved ->
                holder.model = null
                onSaved(saved)
            }
        }
    }

    val requestLeave = {
        if (!model.busy) {
            if (model.hasPages) leaving = true else model.leave(keep = false, onDone = finish)
        }
    }
    val back = {
        if (!model.busy) {
            if (model.backNeedsPrompt) retakePrompt = true else model.backToScanner()
        }
    }
    BackHandler {
        when {
            cropping -> cropping = false
            model.phase == ScanPhase.REVIEW -> back()
            else -> requestLeave()
        }
    }

    val selected = model.selected
    // Re-crops start from the page's kept original, else its unfiltered copy, else the page, as ScanLibrary.recropPage does.
    val cropSource = if (cropping) selected?.let { model.fileOf(it.original ?: it.unfiltered ?: it.image) } else null
    when {
        cropSource != null -> CropScreen(cropSource, model.cropStart, onApply = { quad ->
            cropping = false
            model.crop(quad)
        }, onCancel = { cropping = false })
        model.phase == ScanPhase.SCANNER -> Scanner(
            model, granted, blocked,
            onAllowCamera = { permission.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            },
            onClose = requestLeave
        )
        else -> Review(model, onBack = back, onCrop = {
            model.commitDelete()
            cropping = true
        }, onFilter = { filtering = true }, onDone = {
            model.commitDelete()
            saveOptions = true
        })
    }

    if (filtering && selected != null) {
        FilterDialog(selected.filter, onPick = {
            filtering = false
            model.applyFilter(it)
        }, onDismiss = { filtering = false })
    }
    if (saveOptions) {
        SaveDialog(onSave = { size, password ->
            saveOptions = false
            val name = model.pdfName
            if (name != null) {
                model.pendingSave = size to password
                picker.launch(name)
            }
        }, onDismiss = { saveOptions = false })
    }
    if (leaving) {
        LeaveDialog(
            count = model.pages.size + model.sessionState.pending,
            onKeep = {
                leaving = false
                model.leave(keep = true, onDone = finish)
            },
            onDiscard = {
                leaving = false
                model.leave(keep = false, onDone = finish)
            },
            onStay = { leaving = false }
        )
    }
    if (retakePrompt) {
        // Back from an edited Single page: keep it and leave, or discard it for a retake.
        LeaveDialog(
            count = model.pages.size,
            onKeep = {
                retakePrompt = false
                model.leave(keep = true, onDone = finish)
            },
            onDiscard = {
                retakePrompt = false
                model.backToScanner()
            },
            onStay = { retakePrompt = false }
        )
    }
}

@Composable
private fun Scanner(
    model: ScanFlowModel,
    granted: Boolean,
    blocked: Boolean,
    onAllowCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val cam = model.camera
    val state = model.sessionState
    LightStatusBarIcons()
    ScannerScreen(
        ui = ScannerUi(
            mode = model.mode,
            canChangeMode = model.canChangeMode,
            autoCapture = model.autoCapture,
            liveDetection = cam.liveDetection,
            torchAvailable = cam.torchAvailable,
            torchOn = cam.torchOn,
            holdingSteady = model.holdingSteady,
            lowLight = model.lowLight,
            count = model.count,
            latestPage = model.pages.lastOrNull()?.let { model.fileOf(it.image) },
            shutterEnabled = cam.ready && !cam.capturing && model.canShoot,
            permissionGranted = granted,
            permissionBlocked = blocked,
            cameraProblem = cameraProblem(cam),
            storeFailed = model.failure != null,
            shotFailed = model.notice == ScanNotice.SHOT_FAILED,
        ),
        actions = ScannerActions(
            onClose = onClose,
            onToggleAuto = model::toggleAutoCapture,
            onToggleTorch = { model.controller.toggleTorch() },
            onMode = model::chooseMode,
            onShutter = model::shutter,
            onReview = model::review,
            onAllowCamera = onAllowCamera,
            onOpenSettings = onOpenSettings,
            onRetryStore = { model.failure?.let(model::retry) },
            onDismissStore = { model.failure?.let(model::dismiss) },
            onDismissShot = { model.notice = null },
        ),
    ) {
        if (granted) {
            CameraViewfinder(
                controller = model.controller,
                quad = model.quad.takeIf { cam.liveDetection },
                sensorRotationDegrees = cam.sensorRotationDegrees,
                book = model.mode == CaptureMode.BOOK,
                quarterTurns = model.deviceQuarterTurns,
                count = model.count,
            )
        }
    }
}

@Composable
private fun Review(model: ScanFlowModel, onBack: () -> Unit, onCrop: () -> Unit, onFilter: () -> Unit, onDone: () -> Unit) {
    val pages = model.pages.mapNotNull { page -> model.fileOf(page.image)?.let { ReviewPage(page.id, it) } }
    val spread = model.spread?.let { s ->
        val left = pages.firstOrNull { it.id == s.left.id }
        val right = pages.firstOrNull { it.id == s.right.id }
        if (left != null && right != null) ReviewSpread(left, right, s.leftNumber, s.split.toFloat()) else null
    }
    ReviewScreen(
        ui = ReviewUi(
            mode = model.mode,
            pages = pages,
            selectedId = model.selected?.id,
            spread = spread,
            deletedNumber = model.deleted?.number,
            storing = model.sessionState.pending > 0,
            busy = model.busy,
            canSave = model.canSave,
            notice = noticeText(model.notice),
            storeFailed = model.failure != null,
        ),
        actions = ReviewActions(
            onBack = onBack,
            onSelect = model::select,
            onMove = model::move,
            onSplit = { model.resplit(it.toDouble()) },
            onRotate = model::rotate,
            onCrop = onCrop,
            onFilter = onFilter,
            onDelete = model::delete,
            onUndo = model::undoDelete,
            onAddPage = model::backToScanner,
            onDone = onDone,
            onDismissNotice = { model.notice = null },
            onRetryStore = { model.failure?.let(model::retry) },
            onDismissStore = { model.failure?.let(model::dismiss) },
        ),
    )
}

@StringRes
private fun cameraProblem(cam: ScanCameraState): Int? = when (cam.error) {
    null, CameraError.PERMISSION_DENIED -> null
    CameraError.IN_USE -> R.string.scan_camera_in_use
    CameraError.OPEN_TIMEOUT, CameraError.RECOVERABLE -> R.string.scan_camera_waiting
    else -> R.string.scan_camera_unavailable
}

@StringRes
private fun noticeText(notice: ScanNotice?): Int? = when (notice) {
    ScanNotice.EDIT_FAILED -> R.string.scan_edit_failed
    ScanNotice.SAVE_FAILED -> R.string.scan_save_failed
    ScanNotice.SHOT_FAILED, null -> null
}

/**
 * Holds the activity in portrait while the flow shows, then gives back the orientation it had before the first lock. That orientation is kept
 * in [holder], since the lock itself can recreate the activity, and is not given back while the activity is only being recreated.
 */
@Composable
internal fun HoldPortrait(activity: Activity, holder: ScanFlowHolder) {
    DisposableEffect(activity) {
        if (holder.orientationBefore == null) holder.orientationBefore = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (!activity.isChangingConfigurations) {
                holder.orientationBefore?.let { activity.requestedOrientation = it }
                holder.orientationBefore = null
            }
        }
    }
}

/** Keeps [ScanFlowModel.deviceQuarterTurns] at how the phone is held, the turn a Book mode shot needs to read upright. */
@Composable
private fun FollowDeviceTurns(context: Context, model: ScanFlowModel) {
    DisposableEffect(context, model) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) model.deviceQuarterTurns = ((orientation + 45) / 90) % 4
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
}

/** Light status bar icons over the dark viewfinder, whatever the theme; the theme's own choice comes back when the Scanner goes. */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context.findActivity()).window
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = before }
    }
}

private tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("ScanFlow needs a ComponentActivity")
}

/** The flow's text: the app font at [size] sp and [weight], growing with the system text size. */
internal fun scanText(size: Int, weight: FontWeight): TextStyle =
    TextStyle(fontFamily = PlusJakartaSans, fontWeight = weight, fontSize = size.sp, lineHeight = (size * LINE_HEIGHT).sp)

private const val PDF_MIME = "application/pdf"
private const val LINE_HEIGHT = 1.45f
