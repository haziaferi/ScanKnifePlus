package com.haziaferi.scanknifeplus.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.haziaferi.scanknifeplus.scan.CameraPermission
import com.haziaferi.scanknifeplus.scan.camera.CameraError
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraState
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.PlusJakartaSans

/** The route name MainActivity shows [ScanFlow] for. */
const val SCAN_ROUTE = "scan"

/**
 * The document scanner: Scanner, then Review, then a PDF saved where the system file picker says (the storage folder comes with the storage
 * wiring). The camera permission is asked as the flow starts. [onSaved] gets the saved PDF, already in History; [onClose] runs when the user
 * leaves without saving, after the keep-or-discard prompt (decision 6) when there are pages. The flow holds the screen in portrait, as the
 * camera, the live outline and the crop all assume a portrait screen; Book mode reads how the phone is held from its sensors instead.
 */
@Composable
fun ScanFlow(onClose: () -> Unit, onSaved: (SavedPdf) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val holder = remember(activity) { ViewModelProvider(activity)[ScanFlowHolder::class.java] }
    val model = remember(holder) { holder.model ?: ScanFlowModel(context.applicationContext).also { holder.model = it } }
    val finish = {
        holder.model = null
        onClose()
    }

    HoldPortrait(activity)
    FollowDeviceTurns(context, model)

    var granted by remember { mutableStateOf(CameraPermission.isGranted(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
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
    // Not saveable: saved state can be written to disk, and the password must not be.
    var pendingSave by remember { mutableStateOf<Pair<PdfPageSize, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME)) { uri ->
        val options = pendingSave
        pendingSave = null
        if (uri != null && options != null) {
            model.save(uri, options.first, options.second) { saved ->
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
    BackHandler {
        when {
            cropping -> cropping = false
            model.phase == ScanPhase.REVIEW -> if (!model.busy) model.backToScanner()
            else -> requestLeave()
        }
    }

    val selected = model.selected
    // Re-crops start from the page's kept original, else its unfiltered copy, else the page, as ScanLibrary.recropPage does.
    val cropSource = if (cropping) selected?.let { model.fileOf(it.original ?: it.unfiltered ?: it.image) } else null
    when {
        cropSource != null -> CropScreen(cropSource, onApply = { quad ->
            cropping = false
            model.crop(quad)
        }, onCancel = { cropping = false })
        model.phase == ScanPhase.SCANNER -> Scanner(model, granted, onAllowCamera = { permission.launch(Manifest.permission.CAMERA) }, onClose = requestLeave)
        else -> Review(model, onCrop = {
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
                pendingSave = size to password
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
}

@Composable
private fun Scanner(model: ScanFlowModel, granted: Boolean, onAllowCamera: () -> Unit, onClose: () -> Unit) {
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
            count = state.count,
            latestPage = model.pages.lastOrNull()?.let { model.fileOf(it.image) },
            shutterEnabled = cam.ready && !cam.capturing,
            permissionGranted = granted,
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
                count = state.count,
            )
        }
    }
}

@Composable
private fun Review(model: ScanFlowModel, onCrop: () -> Unit, onFilter: () -> Unit, onDone: () -> Unit) {
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
        ),
        actions = ReviewActions(
            onBack = model::backToScanner,
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

/** Holds the activity in portrait while the flow shows, then gives back the orientation it had. */
@Composable
private fun HoldPortrait(activity: Activity) {
    DisposableEffect(activity) {
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity.requestedOrientation = before }
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
