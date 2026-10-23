"""Scanner benchmark harness: ScanKnife+ against OSS-DocumentScanner (README.md).

Usage (run.sh runs these in order; WORK holds every derived image and per-photo output and is never committed):
  python bench.py prepare    --photos DIR --labels FILE --work WORK   upright PNGs for the JVM, the photo list, the labels keyed by photo
  python bench.py oss-detect --work WORK                               their one-shot detection (oss_port.scan_point) on every photo
  python bench.py score      --work WORK [--out results.md]            after ScannerBenchTest: scores, their filters, OCR images, results.md

Needs OpenCV (opencv-python-headless) and numpy. One process at a time; nothing runs in parallel.
"""

import argparse
import csv
import json
import math
import os
import shutil
import statistics
import sys
import time

import cv2
import numpy as np

import oss_port

HERE = os.path.dirname(os.path.abspath(__file__))
OSS_SHA = "ff05fc52b601d1cccb4076f3f6c3318f74e72f0c"
CORRECT_IOU = 0.9

# Short neutral labels for the stock-camera and GlassCam shots (books and notes take theirs from field.tsv).
SHOT_LABELS = {
    "IMG_20261023_122844": "receipt", "IMG_20261023_122917": "book back cover", "IMG_20261023_123110": "plastic card",
    "IMG_20261023_123313": "red book cover", "IMG_20261023_123334": "red book cover (2)", "IMG_20261023_123600": "page on light surface",
    "IMG_20261023_123743": "page on wood", "IMG_20261023_123746": "page on wood (2)", "IMG_20261023_123837": "page held by hand",
    "IMG_20261023_123852": "pen across page", "IMG_20261023_124558": "folded page", "2026_10_23_12_26_45": "GlassCam: ticket with QR",
    "2026_10_23_12_27_40": "GlassCam: reflective sachet",
}

# Photos whose label cannot score an outline detector (the labeller's notes): kept out of the headline detection numbers, shown apart.
UNSUITABLE = {
    "book-06": "page fills the frame, label = frame", "book-07": "page fills the frame, label = frame",
    "book-08": "page fills the frame, label = frame", "book-09": "no paper edge, label = frame",
    "IMG_20261023_124558": "folded page: two planes", "2026_10_23_12_27_40": "bottle-shaped sachet",
}
CORPUS_LANGS = {"a-english": "eng", "b-italian": "ita", "c-footnotes": "eng+ita", "d-receipt": "ita", "e-layout": "eng"}
A4_300 = (2480, 3508)
OUR_FILTERS = ["Original", "Auto", "Lighten", "Grayscale", "B&W", "Whiteboard"]
OSS_FILTERS = list(oss_port.TRANSFORMS)


def die(msg):
    sys.exit(f"bench.py: {msg}")


def load_json(path, default=None):
    if not os.path.isfile(path):
        return default
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    os.replace(tmp, path)


def read_inventory(work):
    path = os.path.join(work, "inventory.tsv")
    if not os.path.isfile(path):
        die("run `prepare` first")
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def read_rgb(path):
    """An image as RGB, upright: cv2.imread applies the EXIF orientation."""
    bgr = cv2.imread(path, cv2.IMREAD_COLOR)
    if bgr is None:
        die(f"cannot read {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def write_png(path, img, rgb=True):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = cv2.cvtColor(img, cv2.COLOR_RGB2BGR) if rgb and img.ndim == 3 else img
    if not cv2.imwrite(path, out, [cv2.IMWRITE_PNG_COMPRESSION, 1]):
        die(f"cannot write {path}")


# ---------------------------------------------------------------------------------------------------------------------------- prepare

def prepare(args):
    photos, work = args.photos, args.work
    rows = []
    field = {}
    tsv = os.path.join(photos, "books", "field.tsv")
    if os.path.isfile(tsv):
        with open(tsv, encoding="utf-8", newline="") as f:
            field = {r["page"]: r for r in csv.DictReader(f, delimiter="\t")}
    for set_name, sub in (("books", "books"), ("corpus", "corpus"), ("shots", "shots/camera"), ("shots", "shots/glasscam")):
        folder = os.path.join(photos, sub)
        for name in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
            if not name.lower().endswith((".jpg", ".jpeg")):
                continue
            pid = name.split(".")[0]
            if set_name == "books":
                label = field.get(pid, {}).get("label", "")
            elif set_name == "corpus":
                label = f"printed corpus page {pid}"
            else:
                label = SHOT_LABELS.get(pid, "shot")
            rows.append({"id": pid, "set": set_name, "file": name, "src": os.path.join(folder, name), "label": label})
    if not rows:
        die(f"no photos under {photos}")
    os.makedirs(work, exist_ok=True)
    with open(os.path.join(work, "inventory.tsv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["id", "set", "file", "src", "label"], delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    for r in rows:
        out = os.path.join(work, "upright", r["id"] + ".png")
        if not os.path.isfile(out):
            write_png(out, read_rgb(r["src"]))
            print(f"[prepare] upright {r['id']}", flush=True)
    labels = load_json(args.labels, None) if args.labels else None
    if labels is None:
        print("[prepare] no labels file yet: detection runs, scoring waits for it")
        return
    by_file = {r["file"]: r["id"] for r in rows}
    by_id = {r["id"] for r in rows}
    keyed = {}
    for key, value in labels.items():
        base = key.replace("\\", "/").split("/")[-1]
        pid = by_file.get(base) or (base.split(".")[0] if base.split(".")[0] in by_id else None)
        if pid is None:
            print(f"[prepare] label {key} matches no photo, ignored")
            continue
        quad = value.get("quad")
        if not quad or len(quad) != 4:
            continue
        keyed[pid] = {"quad": [[float(x), float(y)] for x, y in quad], "fold": value.get("fold"), "note": value.get("note", "")}
    save_json(os.path.join(work, "labels.json"), keyed)
    # The same quads for ScannerBenchTest, which reads no JSON.
    with open(os.path.join(work, "labels.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("id\tquad\n")
        for pid, lab in keyed.items():
            f.write(pid + "\t" + " ".join(f"{v:.3f}" for pt in lab["quad"] for v in pt) + "\n")
    print(f"[prepare] {len(keyed)} labels of {len(rows)} photos")


# ------------------------------------------------------------------------------------------------------------------------- oss-detect

def oss_detect(args):
    work = args.work
    path = os.path.join(work, "detect-oss.json")
    done = load_json(path, {})
    for r in read_inventory(work):
        if r["id"] in done:
            continue
        rgb = read_rgb(os.path.join(work, "upright", r["id"] + ".png"))
        t0 = time.perf_counter()
        # processCameraImage passes { resizeThreshold: previewResizeThreshold * 1.5, areaScaleMinFactor } (app/utils/ui/index.common.ts:1874):
        # 200 * 1.5 = 300 (constants.ts:72) and 0.1 (constants.ts:73); shrunkImageHeight 0 sets medianBlurValue 3 (document_detector.cpp:196-199).
        image, scale = oss_port.one_shot_input(rgb, 300)
        quad, tried = oss_port.scan_point(image, scale=scale, options={"areaScaleMinFactor": 0.1, "medianBlurValue": 3})
        ms = (time.perf_counter() - t0) * 1000
        done[r["id"]] = {"quad": quad, "ms": ms, "masks": tried, "w": rgb.shape[1], "h": rgb.shape[0]}
        save_json(path, done)
        print(f"[oss-detect] {r['id']}: {'found' if quad else 'none'} in {ms:.0f} ms ({tried} masks)", flush=True)


# ------------------------------------------------------------------------------------------------------------------------------ score

def poly_area(p):
    return abs(sum(p[i][0] * p[(i + 1) % 4][1] - p[(i + 1) % 4][0] * p[i][1] for i in range(4))) / 2


def iou(a, b):
    """Polygon IoU; convex intersection when both are convex, else a rasterised estimate (1/1000 of the label's diagonal per pixel)."""
    pa, pb = np.array(a, np.float32), np.array(b, np.float32)
    if cv2.isContourConvex(pa) and cv2.isContourConvex(pb):
        inter, _ = cv2.intersectConvexConvex(pa, pb)
        union = poly_area(a) + poly_area(b) - inter
        return inter / union if union > 0 else 0.0
    allp = np.vstack([pa, pb])
    x0, y0 = allp.min(0)
    s = 1000 / max(1.0, float(np.ptp(allp, 0).max()))
    size = (int((allp[:, 1].max() - y0) * s) + 2, int((allp[:, 0].max() - x0) * s) + 2)
    ma, mb = np.zeros(size, np.uint8), np.zeros(size, np.uint8)
    cv2.fillPoly(ma, [np.round((pa - [x0, y0]) * s).astype(np.int32)], 1)
    cv2.fillPoly(mb, [np.round((pb - [x0, y0]) * s).astype(np.int32)], 1)
    union = np.count_nonzero(ma | mb)
    return np.count_nonzero(ma & mb) / union if union else 0.0


def corner_error(quad, label):
    """Largest corner distance under the best one-to-one corner matching, as % of the label's longer diagonal."""
    import itertools
    diag = max(math.dist(label[0], label[2]), math.dist(label[1], label[3]))
    best = min(max(math.dist(quad[p[i]], label[i]) for i in range(4)) for p in itertools.permutations(range(4)))
    return 100 * best / diag


def median(v):
    return statistics.median(v) if v else float("nan")


def fmt(v, d=1):
    return "-" if v is None or v != v else f"{v:.{d}f}"


def score_detection(inv, labels, work):
    oss = load_json(os.path.join(work, "detect-oss.json"), {})
    ours = load_json(os.path.join(work, "detect-ours.json"), {})
    methods = [("new", "ScanKnife+ (DocumentDetector.detectFromRgba)"), ("old", "ScanKnife+ before the port (OpenScan candidates only)"),
               ("oss", "OSS-DocumentScanner (scanPoint, one-shot path)"), ("hybrid", "hybrid: old candidates, their score"),
               ("newLive", "ScanKnife+ live path (640x480 frame)"), ("oldLive", "before the port, live path")]
    per = []
    for r in inv:
        if r["id"] not in labels:
            continue
        label = labels[r["id"]]["quad"]
        row = {"id": r["id"], "set": r["set"], "label": r["label"], "unsuitable": UNSUITABLE.get(r["id"])}
        for key, _ in methods:
            src = oss.get(r["id"]) if key == "oss" else (ours.get(r["id"]) or {}).get(key)
            quad = src.get("quad") if src else None
            row[key] = {"iou": iou(quad, label) if quad else 0.0, "err": corner_error(quad, label) if quad else None,
                        "found": quad is not None, "ms": src.get("ms") if src else None, "ran": src is not None}
        per.append(row)
    out = ["## 1. Outline detection", "",
           "IoU of the detected quad with the hand-marked one (correct at IoU >= 0.9) and the largest corner error under the best corner "
           "matching, as % of the label's longer diagonal. Time is PC time for detection on the decoded photo (decode excluded), "
           "Python/OpenCV for theirs and the JVM for ours; it is not device time. No quad counts as IoU 0. Photos whose label cannot score "
           "an outline (page filling the frame, no paper edge, a folded page, a bottle-shaped sachet) are left out of the set rows and "
           "counted in the `unsuitable` rows; book-01..05 and the two notes are cut off by the frame (corners on the frame edge) and stay in. "
           "The live rows shrink the photo to a 640x480 luminance frame and reduce it as the app's live path does (FrameAdapter, 320 px); "
           "their time includes that shrink from the full photo.", ""]
    out += ["| Set | Photos | Method | Correct | Found | Median IoU | Median corner error % | Median PC ms |", "|---|---|---|---|---|---|---|---|"]
    for set_name in ("books", "corpus", "shots", "all", "unsuitable"):
        if set_name == "unsuitable":
            rows = [p for p in per if p["unsuitable"]]
        else:
            rows = [p for p in per if not p["unsuitable"] and (set_name == "all" or p["set"] == set_name)]
        if not rows:
            continue
        for key, title in methods:
            ran = [p[key] for p in rows if p[key]["ran"]]
            if not ran:
                continue
            out.append(f"| {set_name} | {len(ran)} | {title} | {sum(m['iou'] >= CORRECT_IOU for m in ran)} | {sum(m['found'] for m in ran)} | "
                       f"{fmt(median([m['iou'] for m in ran]), 2)} | {fmt(median([m['err'] for m in ran if m['err'] is not None]))} | "
                       f"{fmt(median([m['ms'] for m in ran if m['ms'] is not None]), 0)} |")
    out += ["", "Per photo (IoU / corner error %; `-` = no quad):", "",
            "| Photo | Label | Ours | Before | Theirs | Hybrid | Live | Live before |", "|---|---|---|---|---|---|---|---|"]
    for p in per:
        cells = []
        for key, _ in methods:
            m = p[key]
            cells.append("not run" if not m["ran"] else (f"{m['iou']:.2f} / {fmt(m['err'])}" if m["found"] else "-"))
        note = f" (unsuitable: {p['unsuitable']})" if p["unsuitable"] else ""
        out.append(f"| {p['id']} | {p['label']}{note} | " + " | ".join(cells) + " |")
    timing = load_json(os.path.join(work, "live-time.json"))
    if timing:
        out += ["", f"Live path, JVM ms per frame: FrameAdapter's reduction to 320 px plus detection, on {timing['frames']} luminance frames made "
                f"from the photos, each detected {timing['repeats']} times in a row by a worker that keeps its previous quad, after two "
                "warm-up passes (ScannerBenchTest.liveFrameTime). PC time, not device time.", "",
                "| Analysis frame | Detector | Median ms | p90 ms | Max ms |", "|---|---|---|---|---|"]
        for size, t in timing["sizes"].items():
            for key, title in (("old", "before the port"), ("new", "ScanKnife+")):
                out.append(f"| {size} | {title} | {t[key]['median']:.1f} | {t[key]['p90']:.1f} | {t[key]['max']:.1f} |")
    device = os.path.join(work, "device-timing.tsv")
    if os.path.isfile(device):
        with open(device, encoding="utf-8") as f:
            rows = list(csv.DictReader(f, delimiter="\t"))
        out += ["", "On the phone (OnePlus 6T, Android 11; DetectorTimingDeviceTest, opt-in, its timing.tsv and frames.tsv copied into WORK as "
                "device-timing.tsv and device-frames.tsv): ms per call, the one-shot path on the full photo and the live path (FrameAdapter's "
                "reduction plus detection) on luminance frames made from the photos, each call warmed up once, then timed "
                f"{rows[0]['calls'] if rows else '?'} times in all over {rows[0]['frames'] if rows else '?'} photos.", "",
                "| Path | Median ms | p90 ms | Max ms |", "|---|---|---|---|"]
        out += [f"| {r['path']} | {r['median_ms']} | {r['p90_ms']} | {r['max_ms']} |" for r in rows]
        frames = os.path.join(work, "device-frames.tsv")
        if os.path.isfile(frames):
            with open(frames, encoding="utf-8") as f:
                fr = list(csv.DictReader(f, delimiter="\t"))
            cols = [c for c in fr[0] if c != "photo"] if fr else []
            out += ["", "Photos where OSS-DocumentScanner's search found nothing and OpenScan's candidates answered (presumably the slow calls; 7-9 of 29 photos, about the top 25-30% of calls): " +
                    "; ".join(f"{c.replace('_fallback', '').replace('_', ' ')} {sum(r[c] == 'true' for r in fr)} of {len(fr)} "
                              f"({', '.join(r['photo'] for r in fr if r[c] == 'true')})" for c in cols) + "."]
    return out + [""], per


def warp(rgb, quad, size=None):
    """cv2 perspective warp of [quad] (TL, TR, BR, BL) to its natural size (longest opposite edges) or [size] = (w, h)."""
    q = np.array(quad, np.float32)
    if size is None:
        w = max(math.dist(quad[0], quad[1]), math.dist(quad[3], quad[2]))
        h = max(math.dist(quad[0], quad[3]), math.dist(quad[1], quad[2]))
        size = (max(1, round(w)), max(1, round(h)))
    dst = np.array([[0, 0], [size[0], 0], [size[0], size[1]], [0, size[1]]], np.float32)
    m = cv2.getPerspectiveTransform(q, dst)
    return cv2.warpPerspective(rgb, m, size, flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE), m


def score_gutter(inv, labels, work):
    rows = []
    for r in inv:
        lab = labels.get(r["id"])
        if not lab or not lab.get("fold"):
            continue
        rgb = read_rgb(os.path.join(work, "upright", r["id"] + ".png"))
        spread, m = warp(rgb, lab["quad"])
        del rgb
        fold = cv2.perspectiveTransform(np.array([lab["fold"]], np.float32), m)[0]
        h, w = spread.shape[:2]
        # A fold that runs across the warp means the spread was shot sideways: turn it so the fold is vertical, as the app's
        # quarter turns would, since detectGutterAndSplit only looks for vertical folds.
        if abs(fold[1][1] - fold[0][1]) < abs(fold[1][0] - fold[0][0]):
            spread = cv2.rotate(spread, cv2.ROTATE_90_CLOCKWISE)
            fold = np.array([[h - y, x] for x, y in fold])
            h, w = w, h
        truth = float(fold[:, 0].mean()) / w
        if not 0.25 <= truth <= 0.75:
            print(f"[gutter] {r['id']}: fold at {truth:.3f}, not a spread", flush=True)
            continue
        # As in their desktop test app (cpp/cpp_test_app/scanner.cpp:159, 780-782): resizeImageMax at 300 with a 10 px black border,
        # then detectGutterAndSplit(resized, 0.4).
        coef = max(w, h) / 300.0
        sw, sh = math.floor(w / coef), math.floor(h / coef)
        small = cv2.resize(cv2.cvtColor(spread, cv2.COLOR_RGB2BGR), (sw, sh))
        small = cv2.copyMakeBorder(small, 10, 10, 10, 10, cv2.BORDER_CONSTANT, value=(0, 0, 0))
        t0 = time.perf_counter()
        gx, found = oss_port.detect_gutter_and_split(small, 0.4)
        ms = (time.perf_counter() - t0) * 1000
        pred = (gx - 10) / sw if gx >= 0 else None
        rows.append({"id": r["id"], "label": r["label"], "truth": truth, "pred": pred, "found": found, "ms": ms})
        print(f"[gutter] {r['id']}: fold {truth:.3f}, theirs {pred}", flush=True)
    out = ["## 2. Gutter", "",
           "Two-page spreads (labels whose marked fold lies in the middle half of the quad; folds nearer an edge mark a single page with "
           "the facing page's edge and are skipped), warped with the labelled quad (turned so the fold is vertical). Error = |predicted - marked "
           "fold| as % of the spread's width; the marked fold is the mean x of the two fold points after the warp. Theirs runs as in their "
           "desktop test app (long edge 300 px plus a 10 px border, minPageWidthRatio 0.4); the app itself never calls it.", ""]
    if not rows:
        return out + ["No labelled folds.", ""], rows
    e_oss = [abs(r["pred"] - r["truth"]) * 100 for r in rows if r["pred"] is not None]
    e_half = [abs(0.5 - r["truth"]) * 100 for r in rows]
    out += ["| Method | Spreads | Median error % | Max error % | Within 2% |", "|---|---|---|---|---|",
            f"| detectGutterAndSplit | {len(e_oss)} | {fmt(median(e_oss))} | {fmt(max(e_oss) if e_oss else None)} | {sum(e < 2 for e in e_oss)} |",
            f"| fixed 0.5 (BookSplit.DEFAULT_SPLIT) | {len(e_half)} | {fmt(median(e_half))} | {fmt(max(e_half))} | {sum(e < 2 for e in e_half)} |",
            "", "| Spread | Label | Marked fold | Theirs | Error theirs % | Error 0.5 % |", "|---|---|---|---|---|---|"]
    for r in rows:
        et = fmt(abs(r["pred"] - r["truth"]) * 100) if r["pred"] is not None else "-"
        out.append(f"| {r['id']} | {r['label']} | {r['truth']:.3f} | {fmt(r['pred'], 3)} | {et} | {fmt(abs(0.5 - r['truth']) * 100)} |")
    return out + [""], rows


def lab_l(rgb):
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)[:, :, 0].astype(np.float32) * (100.0 / 255.0)


def page_masks(original):
    """Paper = above the page's own Otsu threshold (on the unfiltered warp), eroded 5x5; ink = the rest, not eroded (text strokes are only a
    few pixels wide at 300 DPI, so an erosion would leave only figures and shadows)."""
    grey = cv2.cvtColor(original, cv2.COLOR_RGB2GRAY)
    _, paper = cv2.threshold(grey, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (5, 5))
    return cv2.erode(paper, k) > 0, paper == 0


def filter_metrics(img, paper, ink):
    l = lab_l(img)
    lp, li = l[paper], l[ink]
    return {"std": float(lp.std()) if lp.size else None, "spread": float(np.percentile(lp, 95) - np.percentile(lp, 5)) if lp.size else None,
            "contrast": float(np.median(lp) - np.median(li)) if lp.size and li.size else None,
            "core": float(np.median(lp) - np.percentile(li, 10)) if lp.size and li.size else None}


def score_filters(inv, labels, work):
    ours_t = load_json(os.path.join(work, "filters-ours.json"), {})
    path = os.path.join(work, "filters-metrics.json")
    metrics = load_json(path, {})
    for r in inv:
        pdir = os.path.join(work, "pages", r["id"])
        orig_path = os.path.join(pdir, "Original.png")
        if r["id"] not in labels or not os.path.isfile(orig_path) or r["id"] in metrics:
            continue
        original = read_rgb(orig_path)
        paper, ink = page_masks(original)
        m = {}
        for name in OUR_FILTERS:
            p = os.path.join(pdir, name.replace("&", "") + ".png")
            if os.path.isfile(p):
                m["ours:" + name] = dict(filter_metrics(read_rgb(p), paper, ink), ms=ours_t.get(r["id"], {}).get(name))
        for name, fn in oss_port.TRANSFORMS.items():
            t0 = time.perf_counter()
            out = fn(original)
            ms = (time.perf_counter() - t0) * 1000
            write_png(os.path.join(pdir, "oss-" + name + ".png"), out)
            m["oss:" + name] = dict(filter_metrics(out, paper, ink), ms=ms)
        metrics[r["id"]] = m
        save_json(path, metrics)
        print(f"[filters] {r['id']}", flush=True)
    keys = ["ours:" + n for n in OUR_FILTERS] + ["oss:" + n for n in OSS_FILTERS]
    out = ["## 3. Colour filters", "",
           "Every labelled photo warped with its hand-marked quad by our PerspectiveCrop (corpus pages to A4 at 300 DPI, others to their natural "
           "size capped at 3508 px), so detection plays no part. Ours run on the JVM, theirs (default options, as the app's transform list "
           "applies them) in Python/OpenCV. L* is CIELAB lightness (0-100). Paper = pixels above the unfiltered page's Otsu threshold, "
           "eroded 5x5; ink = the rest (not eroded, so thin strokes count). Uniformity: standard deviation and p5-p95 spread of paper L* (lower is flatter). "
           "Contrast: median paper L* minus median ink L* (higher is crisper); stroke cores: median paper L* minus the 10th percentile of ink L*, "
           "which still sees text a filter has thinned. Medians over pages; PC ms is median time per page, not device time.", ""]
    out += ["| Filter | Pages | Paper L* std | Paper L* p5-p95 | Ink/paper contrast L* | Stroke-core contrast L* | Median PC ms |",
            "|---|---|---|---|---|---|---|"]
    for set_name in ("all",):
        for k in keys:
            vals = [metrics[p][k] for p in metrics if k in metrics[p]]
            if not vals:
                continue
            g = lambda f: median([v[f] for v in vals if v.get(f) is not None])
            name = ("ours " if k.startswith("ours") else "theirs ") + k.split(":", 1)[1]
            out.append(f"| {name} | {len(vals)} | {fmt(g('std'))} | {fmt(g('spread'))} | {fmt(g('contrast'))} | {fmt(g('core'))} | {fmt(g('ms'), 0)} |")
    out += ["", "By set (median paper L* std / contrast):", "", "| Filter | " + " | ".join(("books", "corpus", "shots")) + " |", "|---|---|---|---|"]
    sets = {r["id"]: r["set"] for r in inv}
    for k in keys:
        cells = []
        for s in ("books", "corpus", "shots"):
            vals = [metrics[p][k] for p in metrics if k in metrics[p] and sets.get(p) == s]
            cells.append(f"{fmt(median([v['std'] for v in vals if v['std'] is not None]))} / "
                         f"{fmt(median([v['contrast'] for v in vals if v['contrast'] is not None]))}" if vals else "-")
        out.append(f"| {('ours ' if k.startswith('ours') else 'theirs ') + k.split(':', 1)[1]} | " + " | ".join(cells) + " |")
    return out + [""], metrics


def to_grey(rgb):
    # BT.601 luma, as OcrBenchmarkTest converts.
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)


def prepare_ocr(inv, labels, work):
    """Image sets for `docs/ocr-bench/run.sh --images` (no detection, no OSD on the device): every filter of every corpus page, book page
    and handwritten sheet in grey (the labels give the corners in reading order, so every warp is upright), plus their OCR preprocessing (whole binarised page as the app sends it, and the text-block
    crops of the header defaults) with an order manifest."""
    root = os.path.join(work, "ocr")
    shutil.rmtree(root, ignore_errors=True)
    field = {}
    sets = {"sb-corpus": [], "sb-books": [], "sb-oss": []}
    for r in inv:
        pdir = os.path.join(work, "pages", r["id"])
        if r["set"] not in ("corpus", "books") or not os.path.isfile(os.path.join(pdir, "Original.png")):
            continue
        set_name = "sb-corpus" if r["set"] == "corpus" else "sb-books"
        lang = CORPUS_LANGS.get(r["id"], "ita")
        variants = [("ours-" + n.replace("&", ""), os.path.join(pdir, n.replace("&", "") + ".png")) for n in OUR_FILTERS]
        variants += [("oss-" + n, os.path.join(pdir, "oss-" + n + ".png")) for n in OSS_FILTERS]
        for variant, src in variants:
            if not os.path.isfile(src):
                continue
            name = f"{r['id']}~{variant}.png"
            write_png(os.path.join(root, set_name, name), to_grey(read_rgb(src)), rgb=False)
            sets[set_name].append((name, r["id"], variant, lang, 300, 0))
        original = read_rgb(os.path.join(pdir, "Original.png"))
        for variant, contours in (("ossocr-app", False), ("ossocr-blocks", True)):
            for i, (_, block) in enumerate(oss_port.ocr_blocks(original, contours)):
                name = f"{r['id']}~{variant}~{i:03d}.png"
                write_png(os.path.join(root, "sb-oss", name), block, rgb=False)
                sets["sb-oss"].append((name, r["id"], variant, lang, 300, i))
        print(f"[ocr] {r['id']}", flush=True)
    for set_name, rows in sets.items():
        if not rows:
            continue
        with open(os.path.join(root, set_name, "images.tsv"), "w", encoding="utf-8", newline="") as f:
            f.write("image\tpage\tvariant\tlang\tdpi\torder\n")
            for row in rows:
                f.write("\t".join(map(str, row)) + "\n")
    return {k: len(v) for k, v in sets.items()}


def score_autocapture(work):
    data = load_json(os.path.join(work, "autocapture.json"))
    out = ["## 5. Auto-capture", ""]
    if not data:
        return out + ["Not run.", ""]
    out += [data["method"], "",
            "| Scenario | Should capture | ScanKnife+ captured (runs) | ScanKnife+ median ms to capture | ScanKnife+ captures per run | "
            "Detector alone captured | Detector alone median ms | "
            f"Theirs @{data['frames'][0]} captured | Theirs median ms | Theirs captures per run | Theirs @{data['frames'][1]} captured | "
            "Theirs median ms |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for s in data["scenarios"]:
        o, a, t, t2 = s["ours"], s["oursAlone"], s["theirs"], s["theirs2"]
        out.append(f"| {s['name']} | { {True: 'yes', False: 'no'}.get(s['shouldCapture'], '-') } | {o['runsCaptured']}/{s['runs']} | {fmt(o['medianMs'], 0)} | "
                   f"{o['meanCaptures']:.2f} | {a['runsCaptured']}/{s['runs']} | {fmt(a['medianMs'], 0)} | "
                   f"{t['runsCaptured']}/{s['runs']} | {fmt(t['medianMs'], 0)} | {t['meanCaptures']:.2f} | "
                   f"{t2['runsCaptured']}/{s['runs']} | {fmt(t2['medianMs'], 0)} |")
    out += ["", "Summary: " + data["summary"], ""]
    return out


def score(args):
    work = args.work
    inv = read_inventory(work)
    labels = load_json(os.path.join(work, "labels.json"), {})
    lines = ["# Scanner benchmark results", "",
             f"ScanKnife+ against OSS-DocumentScanner at `{OSS_SHA}`; method in [README.md](README.md). Generated by `bench.py score`; "
             "aggregates and short per-photo labels only (the photos and every derived image stay outside git).", "",
             f"Photos: {len(inv)} ({', '.join(f'{s} {sum(r['set'] == s for r in inv)}' for s in ('books', 'corpus', 'shots'))}); "
             f"hand-marked: {len(labels)}.", ""]
    det, _ = score_detection(inv, labels, work)
    gut, _ = score_gutter(inv, labels, work)
    fil, _ = score_filters(inv, labels, work)
    counts = prepare_ocr(inv, labels, work)
    ocr = ["## 4. OCR", "",
           "Device step, run by hand (README, \"Running\"); the scores land in [ocr-results.md](ocr-results.md). Images prepared: " +
           ", ".join(f"`{k}` {v}" for k, v in counts.items()) + ".", ""]
    lines += det + gut + fil + ocr + score_autocapture(work)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines).rstrip() + "\n")
    print(f"[score] wrote {args.out}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["prepare", "oss-detect", "score"])
    ap.add_argument("--work", required=True)
    ap.add_argument("--photos")
    ap.add_argument("--labels")
    ap.add_argument("--out", default=os.path.join(HERE, "results.md"))
    args = ap.parse_args()
    if args.command == "prepare":
        if not args.photos:
            die("prepare needs --photos")
        prepare(args)
    elif args.command == "oss-detect":
        oss_detect(args)
    else:
        score(args)


if __name__ == "__main__":
    main()
