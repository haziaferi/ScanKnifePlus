package com.paperknifeplus.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The upstream accent, still hard-coded by the screens that do not read [LocalScanPalette] yet. */
val PaperPink = Color(0xFFF43F5E)

/**
 * The Scanner's camera colours, one set per palette and the same in Light and OLED: the viewfinder is always dark. Names are the approved
 * prototype's (docs/design/options-prototype.html, table K).
 *
 * @property cam viewfinder and Scanner background.
 * @property onCam text and icons on [cam]; also the label of an inactive control.
 * @property shutter the shutter ring and button.
 * @property ctrlOffBg the control bar over the preview.
 * @property offBg an inactive toggle (auto-capture, mode) over the preview.
 * @property onBg an active toggle; [onFg] is its label.
 * @property torchBg the torch toggle while the torch is on; [torchFg] is its icon.
 * @property doneBg the Done button and the page-count badge; [doneFg] is their label (7:1).
 * @property outline the live document outline over the preview (3:1 over a black and a mid-grey preview).
 * @property split the Book mode split line over the preview (3:1 over a black and a mid-grey preview).
 */
@Immutable
data class CameraColors(
    val cam: Color,
    val onCam: Color,
    val shutter: Color,
    val ctrlOffBg: Color,
    val offBg: Color,
    val onBg: Color,
    val onFg: Color,
    val torchBg: Color,
    val torchFg: Color,
    val doneBg: Color,
    val doneFg: Color,
    val outline: Color,
    val split: Color
)

/**
 * The role tokens of one palette in one theme, taken unchanged from the approved prototype (docs/design/options-prototype.html, table T).
 * [toColorScheme] maps them onto Material3; the roles Material3 has no slot for are read from [LocalScanPalette].
 *
 * @property bg screen background.
 * @property sf card and sheet surface.
 * @property sf2 recessed rows and fields on a surface.
 * @property tx body text and icons.
 * @property mu secondary text.
 * @property meta file metadata lines (date, page count).
 * @property nav navigation labels and icons (7:1).
 * @property bd borders and outlines (3:1).
 * @property ac accent text and icons on [bg] / [sf].
 * @property acf filled primary buttons (Scan, Done, Choose folder); [onAcf] is their label (7:1).
 * @property acSoft tonal accent fill under [ac].
 * @property ok success dot and status.
 * @property ring focus ring and the selected-page frame.
 * @property warnBg warning banner (folder unreachable); [warnFg] is its text and button fill.
 * @property danger destructive actions (Delete).
 * @property selBg selected chip and the Undo bar; [selFg] is their text.
 * @property scanBg History tag "Scan"; [scanFg] is its text.
 * @property toolBg History tag for tool outputs ("Merge"); [toolFg] is its text.
 * @property confBg History tag "Conflict copy"; [confFg] is its text.
 * @property bar the folder bar on Home; [barMu] is its secondary text and [barOk] its status dot.
 * @property camera the Scanner's colours, shared by both themes.
 */
@Immutable
data class ScanPalette(
    val bg: Color,
    val sf: Color,
    val sf2: Color,
    val tx: Color,
    val mu: Color,
    val meta: Color,
    val nav: Color,
    val bd: Color,
    val ac: Color,
    val acf: Color,
    val onAcf: Color,
    val acSoft: Color,
    val ok: Color,
    val ring: Color,
    val warnBg: Color,
    val warnFg: Color,
    val danger: Color,
    val selBg: Color,
    val selFg: Color,
    val scanBg: Color,
    val scanFg: Color,
    val toolBg: Color,
    val toolFg: Color,
    val confBg: Color,
    val confFg: Color,
    val bar: Color,
    val barMu: Color,
    val barOk: Color,
    val camera: CameraColors
)

private val StillWaterCamera = CameraColors(
    cam = Color(0xFF0D1110), onCam = Color(0xFFECEFEE), shutter = Color(0xFFECEFEE), ctrlOffBg = Color(0xFF0D1110).copy(alpha = 0.55f),
    offBg = Color(0xFF0D1110).copy(alpha = 0.45f), onBg = Color(0xFFECEFEE), onFg = Color(0xFF0D1110), torchBg = Color(0xFFFFD5A3),
    torchFg = Color(0xFF0D1110), doneBg = Color(0xFF7FB5A6), doneFg = Color(0xFF0D1110), outline = Color(0xFF7DF5CF),
    split = Color(0xFFFFD98A)
)

private val RoseCamera = CameraColors(
    cam = Color(0xFF0C0C0E), onCam = Color(0xFFCDCDCF), shutter = Color(0xFFCDCDCF), ctrlOffBg = Color(0xFF0C0C0E).copy(alpha = 0.55f),
    offBg = Color(0xFF0C0C0E).copy(alpha = 0.45f), onBg = Color(0xFFCDCDCF), onFg = Color(0xFF0C0C0E), torchBg = Color(0xFFEDC492),
    torchFg = Color(0xFF0C0C0E), doneBg = Color(0xFFEB9699), doneFg = Color(0xFF0C0C0E), outline = Color(0xFF7DF5CF),
    split = Color(0xFFFFE08A)
)

private val GraphiteCamera = CameraColors(
    cam = Color(0xFF0D0E10), onCam = Color(0xFFE9E9ED), shutter = Color(0xFFE9E9ED), ctrlOffBg = Color(0xFF0D0E10).copy(alpha = 0.55f),
    offBg = Color(0xFF0D0E10).copy(alpha = 0.45f), onBg = Color(0xFFE9E9ED), onFg = Color(0xFF0D0E10), torchBg = Color(0xFFF2C997),
    torchFg = Color(0xFF0D0E10), doneBg = Color(0xFF9DA6D0), doneFg = Color(0xFF0D0E10), outline = Color(0xFFA8E8FF),
    split = Color(0xFFFFD98A)
)

internal val StillWaterLight = ScanPalette(
    bg = Color(0xFFEAEEED), sf = Color(0xFFF7FBFA), sf2 = Color(0xFFE7EBEA), tx = Color(0xFF121414), mu = Color(0xFF444746),
    meta = Color(0xFF444746), nav = Color(0xFF323434), bd = Color(0xFF535656), ac = Color(0xFF254840), acf = Color(0xFF2B4B43),
    onAcf = Color(0xFFEAEEED), acSoft = Color(0xFFE4F0ED), ok = Color(0xFF1E7D5E), ring = Color(0xFF2B4B43), warnBg = Color(0xFFF6E4CE),
    warnFg = Color(0xFF442900), danger = Color(0xFF983A2D), selBg = Color(0xFF121414), selFg = Color(0xFFEAEEED), scanBg = Color(0xFFE4F0ED),
    scanFg = Color(0xFF0B3028), toolBg = Color(0xFFD6F3E5), toolFg = Color(0xFF00563D), confBg = Color(0xFFFAE8D2), confFg = Color(0xFF633F00),
    bar = Color(0xFFBCC0BF), barMu = Color(0xFF444746), barOk = Color(0xFF121414), camera = StillWaterCamera
)

internal val StillWaterOled = ScanPalette(
    bg = Color(0xFF0D1110), sf = Color(0xFF1A1D1C), sf2 = Color(0xFF171A1A), tx = Color(0xFFE7ECEA), mu = Color(0xFFA7ABAA),
    meta = Color(0xFFA7ABAA), nav = Color(0xFFDFE4E3), bd = Color(0xFF757A79), ac = Color(0xFF8ABAAD), acf = Color(0xFF7FB5A6),
    onAcf = Color(0xFF0D1110), acSoft = Color(0xFF121F1C), ok = Color(0xFF97E5C4), ring = Color(0xFF7FB5A6), warnBg = Color(0xFF2A1903),
    warnFg = Color(0xFFFFE3C1), danger = Color(0xFFF1988A), selBg = Color(0xFFE7ECEA), selFg = Color(0xFF0D1110), scanBg = Color(0xFF121F1C),
    scanFg = Color(0xFFB4E5D7), toolBg = Color(0xFF042519), toolFg = Color(0xFF7DBFA3), confBg = Color(0xFF2B1B04), confFg = Color(0xFFE1B987),
    bar = Color(0xFF303433), barMu = Color(0xFFA7ABAA), barOk = Color(0xFF97E5C4), camera = StillWaterCamera
)

internal val RoseLight = ScanPalette(
    bg = Color(0xFFE2E2E2), sf = Color(0xFFEEEEEE), sf2 = Color(0xFFDDDDDD), tx = Color(0xFF0C0C0C), mu = Color(0xFF3B3B3B),
    meta = Color(0xFF3B3B3B), nav = Color(0xFF202020), bd = Color(0xFF797979), ac = Color(0xFF5E3033), acf = Color(0xFF731324),
    onAcf = Color(0xFFE2E2E2), acSoft = Color(0xFFF8DFDF), ok = Color(0xFF107556), ring = Color(0xFF731324), warnBg = Color(0xFFF7E5CF),
    warnFg = Color(0xFF472B00), danger = Color(0xFF791C12), selBg = Color(0xFF0C0C0C), selFg = Color(0xFFE2E2E2), scanBg = Color(0xFFF8DFDF),
    scanFg = Color(0xFF67393B), toolBg = Color(0xFFC7E3D6), toolFg = Color(0xFF00291C), confBg = Color(0xFFEAD8C3), confFg = Color(0xFF3B2300),
    bar = Color(0xFFB6B6B6), barMu = Color(0xFF3B3B3B), barOk = Color(0xFF0C0C0C), camera = RoseCamera
)

internal val RoseOled = ScanPalette(
    bg = Color(0xFF0C0C0E), sf = Color(0xFF1A1A1C), sf2 = Color(0xFF171718), tx = Color(0xFFE6E6E8), mu = Color(0xFF979799),
    meta = Color(0xFFA9A9AB), nav = Color(0xFFCCCCCE), bd = Color(0xFF69696C), ac = Color(0xFFF497A0), acf = Color(0xFFEB9699),
    onAcf = Color(0xFF0C0C0E), acSoft = Color(0xFF2B1014), ok = Color(0xFF98E6C5), ring = Color(0xFFEB9699), warnBg = Color(0xFF36240D),
    warnFg = Color(0xFFF6CD9B), danger = Color(0xFFCB7568), selBg = Color(0xFFE6E6E8), selFg = Color(0xFF0C0C0E), scanBg = Color(0xFF2B1014),
    scanFg = Color(0xFFFFDDDF), toolBg = Color(0xFF142E24), toolFg = Color(0xFF85C8AB), confBg = Color(0xFF402E16), confFg = Color(0xFFE0B886),
    bar = Color(0xFF303032), barMu = Color(0xFF979799), barOk = Color(0xFF98E6C5), camera = RoseCamera
)

internal val GraphiteLight = ScanPalette(
    bg = Color(0xFFECEDF0), sf = Color(0xFFF9FAFE), sf2 = Color(0xFFE9E9ED), tx = Color(0xFF1E1F22), mu = Color(0xFF595A5D),
    meta = Color(0xFF595A5D), nav = Color(0xFF343437), bd = Color(0xFF78797C), ac = Color(0xFF3B4370), acf = Color(0xFF3B426F),
    onAcf = Color(0xFFECEDF0), acSoft = Color(0xFFEBEFFF), ok = Color(0xFF006D4F), ring = Color(0xFF3B426F), warnBg = Color(0xFFF7E5CF),
    warnFg = Color(0xFF4A2E00), danger = Color(0xFF6F0F07), selBg = Color(0xFF1E1F22), selFg = Color(0xFFECEDF0), scanBg = Color(0xFFEBEFFF),
    scanFg = Color(0xFF454D7B), toolBg = Color(0xFFCBE8DA), toolFg = Color(0xFF00291C), confBg = Color(0xFFEFDDC7), confFg = Color(0xFF664000),
    bar = Color(0xFFBFC0C2), barMu = Color(0xFF343437), barOk = Color(0xFF1E1F22), camera = GraphiteCamera
)

internal val GraphiteOled = ScanPalette(
    bg = Color(0xFF0D0E10), sf = Color(0xFF1B1B1E), sf2 = Color(0xFF141416), tx = Color(0xFFE9E9ED), mu = Color(0xFF9FA0A3),
    meta = Color(0xFF9FA0A3), nav = Color(0xFFE1E2E5), bd = Color(0xFF858689), ac = Color(0xFFA1ADE3), acf = Color(0xFF9DA6D0),
    onAcf = Color(0xFF0D0E10), acSoft = Color(0xFF111526), ok = Color(0xFF9DEBC9), ring = Color(0xFF9DA6D0), warnBg = Color(0xFF312008),
    warnFg = Color(0xFFF2C997), danger = Color(0xFFEB9384), selBg = Color(0xFFE9E9ED), selFg = Color(0xFF0D0E10), scanBg = Color(0xFF111526),
    scanFg = Color(0xFF9FABE1), toolBg = Color(0xFF204033), toolFg = Color(0xFFB5FADB), confBg = Color(0xFF47351E), confFg = Color(0xFFF7CE9B),
    bar = Color(0xFF313234), barMu = Color(0xFF9FA0A3), barOk = Color(0xFF9DEBC9), camera = GraphiteCamera
)
