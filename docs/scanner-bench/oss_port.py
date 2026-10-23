# Python port of parts of OSS-DocumentScanner (https://github.com/ossappscollective/OSS-DocumentScanner) at commit
# ff05fc52b601d1cccb4076f3f6c3318f74e72f0c: cpp/src/DocumentDetector.cpp (scanPoint, findSquares, detectGutterAndSplit, applyTransforms),
# WhitePaperTransform.cpp, WhitePaperTransform2.cpp, ColorSimplificationTransform.cpp, Utils.cpp (getPalette) and DocumentOCR.cpp
# (the preprocessing; its line filter is in oss_ocr_filter.py). MIT License, Copyright (c) 2022 Martin Guillon. A benchmark reference, not shipped code.
"""Faithful ports of OSS-DocumentScanner's detector, filters and OCR preprocessing, for the scanner benchmark (README.md).

Images are numpy arrays in the channel order the app hands to the native code: Android bitmaps are RGBA, and the C++ code reads them
as if they were BGR(A). Each function notes where this or OpenCV 5 changes anything.
"""

import math

import cv2
import numpy as np

# DocumentDetector.h DetectOptions defaults, plus what the app's one-shot path changes (bench.py cites the call sites).
DETECT_DEFAULTS = dict(
    useChannel=-1, borderSize=10.0, cannyFactor=2.0, morphologyAnchorSize=4.0, dilateAnchorSize=3.0, thresh=160.0,
    threshMax=256.0, medianBlurValue=9.0, contoursApproxEpsilonFactor=0.02, expectedMaxCosine=0.4,
    expectedOptimalMaxCosine=0.3, expectedAreaFactor=0.20, areaScaleMinFactor=0.04, minDistanceFromBorderFactor=0.0,
    houghLinesThreshold=0,
)


def _angle(pt1, pt2, pt0):
    dx1, dy1 = float(pt1[0] - pt0[0]), float(pt1[1] - pt0[1])
    dx2, dy2 = float(pt2[0] - pt0[0]), float(pt2[1] - pt0[1])
    return (dx1 * dx2 + dy1 * dy2) / math.sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)


def max_cosine(quad):
    """findSquares' squareness measure: the largest |cos| of the four corner angles, corners in contour order."""
    m = 0.0
    for j in range(2, 6):
        m = max(m, abs(_angle(quad[j % 4], quad[j - 2], quad[(j - 1) % 4])))
    return m


def _find_squares(edged, width, height, squares, weight, o):
    marge = int(int(width * o["minDistanceFromBorderFactor"]) + o["borderSize"])
    contours, _ = cv2.findContours(edged, cv2.RETR_TREE, cv2.CHAIN_APPROX_SIMPLE)
    # compareContourAreas: descending |contourArea|; std::sort is not stable, Python's is (ties may order differently).
    contours = sorted(contours, key=lambda c: -abs(cv2.contourArea(c)))
    b = o["borderSize"]
    max_allowed = (width - 2 * b) * (height - 2 * b) * 0.92
    for contour in contours:
        arc = cv2.arcLength(contour, True)
        area = cv2.contourArea(contour)
        if arc < 100 or area < width * height * o["areaScaleMinFactor"] or area >= max_allowed:
            continue
        approx = cv2.approxPolyDP(contour, arc * o["contoursApproxEpsilonFactor"], True)
        if len(approx) != 4 or not cv2.isContourConvex(approx):
            continue
        pts = [(int(p[0][0]), int(p[0][1])) for p in approx]
        if any(x < marge or x >= width - marge or y < marge or y >= height - marge for x, y in pts):
            continue
        mc = max_cosine(pts)
        if mc < o["expectedMaxCosine"]:
            # houghLinesThreshold defaults to 0, which skips the Hough refinement entirely.
            squares.append((pts, area, mc, int(weight)))


def _sort_factor(s):
    return s[1] + float(s[3]) * (1 - s[2])


def sort_points(points):
    """sortPoints: by y, then the top two by x ascending and the bottom two by x descending (TL, TR, BR, BL)."""
    p = sorted(points, key=lambda q: q[1])
    return sorted(p[:2], key=lambda q: q[0]) + sorted(p[2:], key=lambda q: -q[0])


def scan_point(image, scale=1.0, resize_scale=1.0, options=None):
    """DocumentDetector::scanPoint(edged, image, false) on an already bordered image (4-channel RGBA as the bitmap arrives).

    Returns ([TL, TR, BR, BL] in source pixels or None, number of edge masks tried).
    """
    o = dict(DETECT_DEFAULTS, **(options or {}))
    height, width = image.shape[:2]
    found = []
    temp1 = cv2.medianBlur(image, int(o["medianBlurValue"])) if o["medianBlurValue"] > 0 else image
    dsz = max(1, int(o["dilateAnchorSize"]))
    msz = max(1, int(o["morphologyAnchorSize"]))
    dilate_struct = cv2.getStructuringElement(cv2.MORPH_RECT, (dsz, dsz))
    morph_struct = cv2.getStructuringElement(cv2.MORPH_RECT, (msz, msz))
    channels = min(image.shape[2] if image.ndim == 3 else 1, 3)
    i, min_i = channels - 1, 0
    if o["useChannel"] >= 0:
        i = min_i = o["useChannel"]
    weight = 3000000
    tried = 0

    def good_enough():
        if not found:
            return False
        found.sort(key=_sort_factor, reverse=True)
        first = found[0]
        return first[2] < o["expectedOptimalMaxCosine"] and first[1] > width * height * o["expectedAreaFactor"]

    done = False
    while i >= min_i and not done:
        # On an RGBA bitmap channel 2 is blue: the loop visits B, G, R.
        temp2 = np.ascontiguousarray(temp1[:, :, i]) if temp1.ndim == 3 else temp1
        _, edged = cv2.threshold(temp2, o["thresh"], o["threshMax"], cv2.THRESH_BINARY)
        edged = cv2.morphologyEx(edged, cv2.MORPH_CLOSE, morph_struct)
        edged = cv2.dilate(edged, dilate_struct)
        _find_squares(edged, width, height, found, weight, o)
        weight -= 1
        tried += 1
        if good_enough():
            break
        t = 60
        while t >= 10:
            edged = cv2.Canny(temp2, t * o["cannyFactor"], o["cannyFactor"] * t * 2)
            edged = cv2.dilate(edged, dilate_struct)
            _find_squares(edged, width, height, found, weight, o)
            weight -= 1
            tried += 1
            if good_enough():
                done = True  # `i = minI; break;` then the for loop's i-- ends the outer loop too
                break
            t -= 10
        i -= 1
    if not found:
        return None, tried
    found.sort(key=_sort_factor, reverse=True)
    b = int(o["borderSize"])
    pts = []
    for x, y in found[0][0]:
        if b > 0:
            x, y = x - b, y - b
        # cv::Point *= double rounds through saturate_cast (round half to even).
        pts.append((int(np.rint(x * resize_scale * scale)), int(np.rint(y * resize_scale * scale))))
    return sort_points(pts), tried


def one_shot_input(rgb_upright, resize_threshold=300, border=10):
    """The image scanPoint sees in the app's one-shot path (processCameraImage -> getJSONDocumentCornersFromFile).

    Android decodes the photo to a long edge of `resize_threshold` (inSampleSize, then density scaling; approximated here by INTER_AREA)
    and rotates it upright; the detector, given shrunkImageHeight 0, does not resize again and only adds the black border.
    Returns (bordered RGBA image, scale back to the full photo).
    """
    h, w = rgb_upright.shape[:2]
    f = resize_threshold / max(w, h)
    sw, sh = max(1, round(w * f)), max(1, round(h * f))
    small = cv2.resize(rgb_upright, (sw, sh), interpolation=cv2.INTER_AREA)
    rgba = cv2.cvtColor(small, cv2.COLOR_RGB2RGBA)
    rgba = cv2.copyMakeBorder(rgba, border, border, border, border, cv2.BORDER_CONSTANT, value=(0, 0, 0, 0))
    return rgba, w / sw


def detect_gutter_and_split(image, min_page_width_ratio=0.20, blur_size=5):
    """detectGutterAndSplit: the column (in [image]'s pixels) with the least smoothed horizontal-gradient energy in the middle half."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 and image.shape[2] == 3 else image.copy()
    gray = cv2.GaussianBlur(gray, (blur_size, blur_size), 0)
    grad_x = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    energy = cv2.reduce(grad_x, 0, cv2.REDUCE_SUM, dtype=cv2.CV_32F)[0]
    n = len(energy)
    r = 15
    csum = np.concatenate([[0.0], np.cumsum(energy.astype(np.float64))])
    idx = np.arange(n)
    lo, hi = np.maximum(idx - r, 0), np.minimum(idx + r, n - 1)
    smooth = (csum[hi + 1] - csum[lo]) / (hi - lo + 1)
    width = image.shape[1]
    search_min, search_max = int(width * 0.25), int(width * 0.75)
    gutter = -1
    best = float("inf")
    for i in range(search_min, search_max):
        if smooth[i] < best:  # strict: the first minimum wins
            best, gutter = smooth[i], i
    min_width = int(width * min_page_width_ratio)
    found = gutter >= 0 and (gutter > min_width or width - gutter > min_width)
    return gutter, found


# WhitePaperTransform.h defaults. csWhitePer is a double 99.5 passed to an int parameter, so contrastStretch sees 99.
WP = dict(csBlackPer=2, csWhitePer=99.5, gaussKSize=3, gaussSigma=1.0, gammaValue=1.1, cbBlackPer=2, cbWhitePer=1, dogKSize=15,
          dogSigma1=100, dogSigma2=0)


def _dog_kernel(k, s1, s2):
    x = y = (k - 1) // 2
    kernel = np.zeros(k * k, np.float64)
    if s1 > 0:
        co1, co2 = 1 / (2 * s1 * s1), 1 / (2 * math.pi * s1 * s1)
        i = 0
        for v in range(-y, y + 1):
            for u in range(-x, x + 1):
                kernel[i] = math.exp(-(u * u + v * v) * co1) * co2
                i += 1
    else:
        kernel[x + y * k] = 1.0
    if s2 > 0:
        co1, co2 = 1 / (2 * s2 * s2), 1 / (2 * math.pi * s2 * s2)
        i = 0
        for v in range(-y, y + 1):
            for u in range(-x, x + 1):
                kernel[i] -= math.exp(-(u * u + v * v) * co1) * co2
                i += 1
    else:
        kernel[x + y * k] -= 1.0
    # normalizeKernel(kernel, k, k, 1.0), including its quirk: a non-zero negative range leaves negScale at 1.
    kernel[np.abs(kernel) < 1e-12] = 0.0
    pos = kernel[kernel >= 0].sum()
    neg = kernel[kernel < 0].sum()
    pos_scale = 1.0 / (pos if abs(pos) >= 1e-12 else 1.0)
    neg_scale = 1.0 / (1.0 if abs(neg) >= 1e-12 else -neg)
    kernel = np.where(kernel >= 0, kernel * pos_scale, kernel * neg_scale)
    return kernel.reshape(k, k)


def _half_up(v):
    # C++ round() and `(uchar)(v + 0.5)` agree for the non-negative values these LUTs see.
    return np.floor(v + 0.5)


def _hist(ch):
    return np.bincount(ch.ravel(), minlength=256)


def _contrast_stretch(img, black, white, rounding, v2=False):
    tot = img.shape[0] * img.shape[1]
    black_count = tot * int(black) // 100
    white_count = tot * int(white) // 100
    out = []
    for c in range(min(img.shape[2], 3)):
        h = _hist(img[:, :, c])
        cum = np.cumsum(h)
        above = np.nonzero(cum > black_count)[0]
        b_ind = int(above[0]) if len(above) else 0
        rcum = np.cumsum(h[::-1])
        above = np.nonzero(rcum > tot - white_count)[0]
        w_ind = 255 - int(above[0]) if len(above) else 255
        j = np.arange(256)
        if w_ind - b_ind > 0:
            lut = np.where(j < b_ind, 0, np.where(j > w_ind, 255, rounding((j - b_ind) / (w_ind - b_ind) * 255))).astype(np.uint8)
        elif v2:
            lut = np.zeros(256, np.uint8)
        else:
            lut = np.where(j > w_ind, 255, 0).astype(np.uint8)
        out.append(lut[img[:, :, c]])
    return np.dstack(out)


def _color_balance(img, low_per, high_per, rounding, reduced=False):
    """colorBalance; [reduced] reproduces WhitePaperTransform.cpp, which searches cv::reduce(hist) (one row holding the pixel count),
    so both bounds land on 0 and every channel is returned unchanged."""
    if reduced:
        return img
    tot = img.shape[0] * img.shape[1]
    low_count = int(tot * low_per / 100)
    high_count = int(tot * (100 - high_per) / 100)
    out = []
    for c in range(img.shape[2]):
        ch = img[:, :, c]
        h = _hist(ch)
        cum = np.cumsum(h)
        li = int(np.nonzero(cum >= low_count)[0][0])
        rcum = np.cumsum(h[::-1])
        hi = 255 - int(np.nonzero(rcum >= high_count)[0][0])
        if li == hi:
            out.append(ch)
            continue
        j = np.arange(256)
        mid = rounding((j - li) / (hi - li) * 255) if hi - li > 0 else np.zeros(256)
        lut = np.where(j < li, 0, np.where(j > hi, 255, mid)).astype(np.uint8)
        out.append(lut[ch])
    return np.dstack(out)


def whitepaper(rgb):
    """whiteboardEnhance with default options (the app's "whitepaper" transform). Takes and returns 3 channels."""
    o = WP
    res = cv2.filter2D(rgb, -1, _dog_kernel(o["dogKSize"], o["dogSigma1"], o["dogSigma2"]))
    res = cv2.bitwise_not(res)
    res = _contrast_stretch(res, o["csBlackPer"], int(o["csWhitePer"]), _half_up)
    if o["gaussKSize"] > 0:
        k = cv2.getGaussianKernel(o["gaussKSize"], o["gaussSigma"])
        res = cv2.sepFilter2D(res, -1, k, k)
    lut = np.array([int(math.pow(i / 255.0, 1.0 / o["gammaValue"]) * 255) for i in range(256)], np.uint8)
    res = lut[res]
    return _color_balance(res, o["cbBlackPer"], o["cbWhitePer"], _half_up, reduced=True)


def whitepaper2(rgb):
    """whiteboardEnhance2 with default options (not offered in the app's transform list, benchmarked because the brief names it)."""
    o = WP
    k = o["dogKSize"]
    b1 = cv2.GaussianBlur(rgb, (k, k), o["dogSigma1"]) if o["dogSigma1"] > 0 else rgb.copy()
    b2 = cv2.GaussianBlur(rgb, (k, k), o["dogSigma2"]) if o["dogSigma2"] > 0 else rgb.copy()
    res = cv2.bitwise_not(cv2.subtract(b1, b2))
    res = _contrast_stretch(res, o["csBlackPer"], int(o["csWhitePer"]), _half_up, v2=True)
    if o["gaussKSize"] > 0:
        g = cv2.getGaussianKernel(o["gaussKSize"], o["gaussSigma"])
        res = cv2.sepFilter2D(res, -1, g, g)
    lut = np.array([int(math.pow(i / 255.0, 1.0 / o["gammaValue"]) * 255.0 + 0.5) for i in range(256)], np.uint8)
    res = lut[res]
    return _color_balance(res, o["cbBlackPer"], o["cbWhitePer"], _half_up)


def enhance(rgb):
    """The "enhance" transform: cv::detailEnhance(src, dst, 10, 0.15). The RGBA bitmap is read as BGR, so pass RGB as is."""
    return cv2.detailEnhance(rgb, sigma_s=10, sigma_r=0.15)


def _hsv_distance(c1, c2):
    # colorDistance for HSV: the hue difference is an int divided by 180, so it is 0 for every real pair and only S and V count.
    a, b = int(c1[0]), int(c2[0])
    hue = min(a - b, b - a + 180) if a > b else min(b - a, a - b + 180)
    hue //= 180
    return math.sqrt(hue * hue + (int(c1[1]) - int(c2[1])) ** 2 + (int(c1[2]) - int(c2[2])) ** 2)


def _bgr_distance(c1, c2):
    # cv::norm(color2 - color1) on Vec3b: the subtraction saturates at 0 per channel.
    d = [max(0, int(c2[k]) - int(c1[k])) for k in range(3)]
    return math.sqrt(sum(v * v for v in d))


def color(rgb, seed=0x12345):
    """The "color" transform: colorSimplificationTransform(img, img, false, 100, 20, 40, 5, HSV, BGR) as applyTransforms calls it.

    kmeans (K 8, KMEANS_PP_CENTERS, 10 attempts) uses OpenCV's RNG; it is seeded here so runs repeat.
    """
    cv2.setRNGSeed(seed)
    h, w = rgb.shape[:2]
    size = max(w, h)
    small = rgb
    if size > 100:  # Utils resizeImageToSize: int truncation of size / (size / 100)
        s = size / 100.0
        small = cv2.resize(rgb, (int(w / s), int(h / s)))
    data = small.reshape(-1, 3).astype(np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS, 10, 0.1)
    _, labels, centers = cv2.kmeans(data, 8, None, criteria, 10, cv2.KMEANS_PP_CENTERS)
    # convertTo(CV_8U) rounds and saturates.
    quant = np.clip(np.rint(centers[labels.ravel()]), 0, 255).astype(np.uint8)
    n = len(quant)
    counts = {}
    for px in map(tuple, quant):
        counts[px] = counts.get(px, 0) + 1
    # getPaletteFrom1Row: std::map order (lexicographic), keep > 0.1 %, then sort by share (std::sort, not stable).
    pairs = [(c, cnt / n) for c, cnt in sorted(counts.items()) if cnt / n > 0.001]
    pairs.sort(key=lambda p: -p[1])
    nb = 5
    if len(pairs) > nb:
        thr = 10
        while len(pairs) > nb and thr <= 512:
            i = len(pairs) - 1
            while i >= 0:
                for j in range(i - 1, -1, -1):
                    if _bgr_distance(pairs[i][0], pairs[j][0]) <= thr:
                        del pairs[i]
                        break
                if len(pairs) <= nb:
                    break
                i -= 1
            thr += 10
    palette = [np.array(c, np.uint8) for c, _ in pairs]
    to_hsv = [cv2.cvtColor(c.reshape(1, 1, 3), cv2.COLOR_BGR2HSV)[0, 0] for c in palette]
    hsv = cv2.cvtColor(rgb, cv2.COLOR_BGR2HSV)
    flat = hsv.reshape(-1, 3).astype(np.int32)
    out = flat.copy()
    assigned = np.zeros(len(flat), bool)
    for c in to_hsv:
        # Same as the per-pixel loop: the first palette colour within 40 (S, V distance) wins.
        d = np.sqrt((flat[:, 1] - int(c[1])) ** 2 + (flat[:, 2] - int(c[2])) ** 2)
        hit = (d < 40) & ~assigned
        out[hit] = c
        assigned |= hit
    res = out.reshape(hsv.shape).astype(np.uint8)
    return cv2.cvtColor(res, cv2.COLOR_HSV2BGR)


TRANSFORMS = {"whitepaper": whitepaper, "whitepaper2": whitepaper2, "enhance": enhance, "color": color}

# DocumentOCR.h DetectOptions defaults.
OCR_DEFAULTS = dict(adapThresholdBlockSize=339, adapThresholdC=47, detectContours=1, textDetectDilate=40, textDetect1=70, textDetect2=4,
                    dpi="300", pageSegMode=3, oem=3, iteratorLevel=2)


def _sort_key(rect):
    x, y, w, h = rect
    # getYSortValue: (y + h / 2) / 100 is integer division in C++, then round() and * 10000.
    return float(round((y + h // 2) // 100) * 10000) + x


def ocr_blocks(rgb, detect_contours):
    """detectTextImpl up to Tesseract: adaptive threshold, optional text-block contours, sort, inversion, 15 px white border per block.

    Returns the grey block images Tesseract would get, in its order. With detect_contours False (what app/services/ocr.ts passes) it is
    the whole binarised page as one block.
    """
    o = OCR_DEFAULTS
    gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)  # image.channels() == 4 -> COLOR_RGBA2GRAY on the bitmap
    binv = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, o["adapThresholdBlockSize"],
                                 o["adapThresholdC"])
    height, width = binv.shape
    rects = []
    if detect_contours:
        el = cv2.getStructuringElement(cv2.MORPH_RECT, (o["textDetect1"], o["textDetect2"]))
        thr = cv2.morphologyEx(binv, cv2.MORPH_CLOSE, el)
        if o["textDetectDilate"] > 0:
            el = cv2.getStructuringElement(cv2.MORPH_RECT, (o["textDetectDilate"], o["textDetectDilate"]))
            thr = cv2.dilate(thr, el, iterations=1)
        contours, _ = cv2.findContours(thr, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        if contours:
            contours = sorted(contours, key=lambda c: -abs(cv2.contourArea(c)))
            for c in contours:
                if len(c) > 200:
                    poly = cv2.approxPolyDP(c, 3, True)
                    x, y, w, h = cv2.boundingRect(poly)
                    x0, y0 = max(x, 0), max(y, 0)
                    x1, y1 = min(x + w, width), min(y + h, height)
                    w, h = x1 - x0, y1 - y0
                    if w > 0 and h > 0 and w < 0.9 * width and h < 0.9 * height:
                        rects.append((x0, y0, w, h))
            rects.sort(key=_sort_key)
        else:
            rects.append((0, 0, width, height))
    else:
        rects.append((0, 0, width, height))
    page = cv2.bitwise_not(binv)
    blocks = []
    for x, y, w, h in rects:
        crop = page[y:y + h, x:x + w]
        blocks.append(((x, y, w, h), cv2.copyMakeBorder(crop, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=255)))
    return blocks
