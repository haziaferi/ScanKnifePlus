"""The open OCR questions (questions.md): prepares the page images for `run.sh --images` and scores what the phone read.

Usage (WORK holds every derived image, the PDF truth and the page metadata; keep it outside the repository):
  python questions.py prepare --work WORK --photos PHOTOS --labels LABELS --detect DETECT --set SET [--rule RULE]
  python questions.py score   --work WORK [--out questions-results.md]

PHOTOS is the scanner benchmark's photo folder (docs/scanner-bench/README.md, "Photos and labels"), LABELS its labels.json and DETECT the
detect-ours.json of its JVM half: the current one-shot detector's quad (DocumentDetector.detectFromRgba) is its `ours` entry, or `old` in
a file from a detector comparison. Sets, each written to WORK/img/<set>/ with an images.tsv for `run.sh --images` (delete the images
once the phone has read them; prepare writes them again):
  q1-clean q1-photo q1-shots q1-books q1-ita q1-eng   every page at 150-400 DPI and at x-height 20/25/30 px (question 1)
  q3                                                   PSM 1, 4, 6, 11 at RULE on the pages with truth (3 comes from q1)
  q5                                                   every page upright at RULE, for imagesOsd (which turns them itself)
  q6                                                   the corpus photos, books and shots warped with the detector's quad at RULE
  q8                                                   Grayscale filter and stored PDF page at RULE (plain grey comes from q1)
The PDF pages are simulated scans as in PaperKnife+ PC's study (phase12/ocr_measure.py): rendered at 200 or 300 ppi, saved as JPEG q85,
put back on an image-only page and rendered again at the target resolution; their own text layer is the truth.

Needs OpenCV, numpy and PyMuPDF. One process at a time.
"""

import argparse
import json
import math
import os
import re
import shutil
import statistics
import sys
import time
from collections import Counter, defaultdict

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import score as sc  # noqa: E402

FIXED_DPI = [150, 200, 250, 300, 400]
XH_TARGETS = [20, 25, 30]
RULES = [f"dpi{d}" for d in FIXED_DPI] + [f"xh{t}" for t in XH_TARGETS]
# Body x-height in pixels of 11 pt Liberation Serif at 300 DPI; an x-height rule passes Tesseract the DPI that target implies for such text.
REF_XH_300 = 21.0
MAX_EDGE = 6000
SCAN_PPI = [200, 300]
# The owner's Italian PDF from PaperKnife+ PC's study (29 pages of tables, read-only); its path stays out of the repository.
PDF_ITA = (os.environ.get("OCR_QUESTIONS_ITA_PDF", ""), list(range(29)), "ita")
PDF_ENG = [  # The English PDFs of PaperKnife+ PC's study, read-only, from installed programs.
    ("C:/Program Files/PDF24/qpdf/share/doc/qpdf/qpdf-manual.pdf", list(range(10, 16)), "qpdf"),
    ("C:/Program Files/gs/gs10.06.0/doc/GS9_Color_Management.pdf", list(range(1, 7)), "gs"),
    ("C:/Program Files/Adobe/Acrobat DC/Acrobat/WebResources/Resource2/static/js/plugins/sample-files/assets/Sample Files/"
     "Adobe Cloud Services.pdf", list(range(6)), "adobe"),
    ("C:/Program Files (x86)/Blackmagic Design/Blackmagic RAW/Blackmagic RAW Speed Test/Blackmagic RAW Speed Test.pdf", list(range(1, 7)), "bmd"),
]
CORPUS_LANGS = {"a-english": "eng", "b-italian": "ita", "c-footnotes": "eng+ita", "d-receipt": "ita", "e-layout": "eng"}
# Shots with text, with their language and an estimated physical long edge in mm (A4 pages measured, the rest estimated from the photo).
SHOTS = {
    "IMG_20261023_122844": ("receipt", "ita", 190), "2026_10_23_12_26_45": ("ticket", "ita", 120),
    "2026_10_23_12_27_40": ("sachet", "ita", 180), "IMG_20261023_123600": ("page-light", "eng", 297),
    "IMG_20261023_123743": ("page-wood", "eng", 297), "IMG_20261023_123746": ("page-wood2", "eng", 297),
    "IMG_20261023_123837": ("page-hand", "eng", 297), "IMG_20261023_123852": ("page-pen", "eng", 297),
    "IMG_20261023_124558": ("page-folded", "eng", 297),
}
PSMS = [1, 4, 6, 11]


# ------------------------------------------------------------------------------------------------------------------------------ pages

def grey(rgb):
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)  # BT.601, as OcrBenchmarkTest converts


def read_rgb(path):
    bgr = cv2.imread(path, cv2.IMREAD_COLOR)  # applies EXIF orientation
    if bgr is None:
        sys.exit(f"cannot read {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def warp_natural(rgb, quad):
    """cv2 warp of quad (TL, TR, BR, BL) to its natural size (longest opposite edges)."""
    q = np.array(quad, np.float32)
    w = max(math.dist(quad[0], quad[1]), math.dist(quad[3], quad[2]))
    h = max(math.dist(quad[0], quad[3]), math.dist(quad[1], quad[2]))
    size = (max(1, round(w)), max(1, round(h)))
    dst = np.array([[0, 0], [size[0], 0], [size[0], size[1]], [0, size[1]]], np.float32)
    return cv2.warpPerspective(rgb, cv2.getPerspectiveTransform(q, dst), size, flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)


def resize(img, scale):
    h, w = img.shape[:2]
    size = (max(1, round(w * scale)), max(1, round(h * scale)))
    return cv2.resize(img, size, interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR)


def xheight(g):
    """Cheap x-height estimate in px: the most common height of letter-sized connected components of the binarised page. A 3 x 3 median
    removes paper grain, then an adaptive threshold (window about 1/40 of the long edge, so shading does not matter; 40 levels below the
    local mean, strict enough that blurred 8 pt letters do not merge into words). Components count when 6-200 px tall, 0.15-3 times as
    wide as tall, 15-90% filled, and followed within 0.8 of their height by a component of about the same height on about the same baseline
    (a letter in a word; grain, rules and figure detail rarely are). The mode of their heights (smoothed over +-1 px) is the height of the
    most common letters: the x-height on mixed-case text, the cap height on all-caps text. Returns (px, components used, ms)."""
    t0 = time.perf_counter()
    block = max(15, (max(g.shape) // 40) | 1)
    ink = cv2.adaptiveThreshold(cv2.medianBlur(g, 3), 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, block, 40)
    _, _, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    x, y, w, h, area = (stats[1:, i] for i in range(5))
    keep = (h >= 6) & (h <= 200) & (w >= 0.15 * h) & (w <= 3 * h) & (area >= 0.15 * w * h) & (area <= 0.9 * w * h)
    x, w, h, bottom = x[keep], w[keep], h[keep], (y + h)[keep]
    rows = defaultdict(list)
    for i in np.argsort(x, kind="stable"):
        rows[int(bottom[i]) // 4].append(i)
    xs = {k: x[v] for k, v in rows.items()}
    letters = []
    for k, members in rows.items():
        for i in members:
            right, hi = x[i] + w[i], h[i]
            for kk in (k - 1, k, k + 1):
                if kk not in rows:
                    continue
                cand = np.asarray(rows[kk])[(xs[kk] >= right - 0.1 * hi) & (xs[kk] <= right + 0.8 * hi)]
                if np.any((np.abs(h[cand] - hi) <= max(2, 0.2 * hi)) & (cand != i)):
                    letters.append(hi)
                    break
    if len(letters) < 20:
        return float("nan"), len(letters), (time.perf_counter() - t0) * 1000
    hist = np.bincount(np.asarray(letters), minlength=202).astype(float)
    smooth = hist[:-2] + hist[1:-1] + hist[2:]
    peak = int(np.argmax(smooth[5:]) + 6)
    lo, hi = peak - 1, peak + 1
    px = float((hist[lo:hi + 1] * np.arange(lo, hi + 1)).sum() / max(1, hist[lo:hi + 1].sum()))
    return px, len(letters), (time.perf_counter() - t0) * 1000


class Page:
    """One page: id, group, language, physical long edge, and a way to get its image at a scale of its natural resolution."""

    def __init__(self, pid, group, lang, long_mm, truth):
        self.id, self.group, self.lang, self.long_mm, self.truth = pid, group, lang, long_mm, truth

    def meta(self):
        return {"group": self.group, "lang": self.lang, "long_mm": self.long_mm, "truth": self.truth}


class RasterPage(Page):
    def __init__(self, pid, group, lang, long_mm, truth, load):
        super().__init__(pid, group, lang, long_mm, truth)
        self.load = load  # () -> natural RGB

    def natural(self):
        if not hasattr(self, "_rgb"):
            self._rgb = self.load()
        return self._rgb

    def natural_dpi(self):
        return max(self.natural().shape[:2]) / (self.long_mm / 25.4)

    def release(self):
        self.__dict__.pop("_rgb", None)

    def at(self, scale):
        return grey(resize(self.natural(), scale))


class PdfPage(Page):
    """A simulated scan: the PDF page rendered at ppi as RGB, saved as JPEG q85 and put back on an image-only page (their scan_of)."""

    def __init__(self, pid, group, lang, truth, src, index, ppi):
        import pymupdf
        page = src[index]
        long_mm = max(page.rect.width, page.rect.height) / 72 * 25.4
        super().__init__(pid, group, lang, long_mm, truth)
        pix = page.get_pixmap(dpi=ppi, colorspace=pymupdf.csRGB, alpha=False)
        rgb = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, 3)
        ok, jpg = cv2.imencode(".jpg", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        self.scan = pymupdf.open()
        p = self.scan.new_page(width=page.rect.width, height=page.rect.height)
        p.insert_image(p.rect, stream=jpg.tobytes())
        self.ppi = ppi
        self._natural = cv2.imdecode(jpg, cv2.IMREAD_COLOR)[:, :, ::-1]

    def natural(self):
        return self._natural

    def natural_dpi(self):
        return float(self.ppi)

    def release(self):
        pass

    def at(self, scale):
        import pymupdf
        z = self.ppi * scale / 72
        pix = self.scan[0].get_pixmap(matrix=pymupdf.Matrix(z, z), colorspace=pymupdf.csRGB, alpha=False)
        return grey(np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, 3))


def pdf_truth(work, pid, page):
    """The page's own text layer: reading-order text and word boxes in points (for the study's position-paired "words found")."""
    os.makedirs(os.path.join(work, "truth"), exist_ok=True)
    with open(os.path.join(work, "truth", pid + ".txt"), "w", encoding="utf-8") as f:
        f.write(page.get_text())
    with open(os.path.join(work, "truth", pid + ".words.json"), "w", encoding="utf-8") as f:
        json.dump([[w[4], [w[0], w[1], w[2], w[3]]] for w in page.get_text("words")], f, ensure_ascii=False)


def pages_of(group, args, labels):
    """The pages of a group; photos are warped with their hand-marked quad."""
    out = []
    upright = os.path.join(os.path.dirname(args.labels), "upright")

    def photo(key):
        return lambda: warp_natural(read_rgb(os.path.join(upright, key + ".png")), labels[key]["quad"])

    def add(page, key):
        page.key = key
        out.append(page)

    if group == "clean":
        import pymupdf
        doc = pymupdf.open(os.path.join(HERE, "corpus", "corpus.pdf"))
        for i, name in enumerate(sorted(CORPUS_LANGS)):
            def load(i=i):
                pix = doc[i].get_pixmap(dpi=600, colorspace=pymupdf.csRGB, alpha=False)
                return np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, 3).copy()
            out.append(RasterPage(f"cl-{name}", group, CORPUS_LANGS[name], 297, name, load))
    elif group == "photo":
        for name in sorted(CORPUS_LANGS):
            add(RasterPage(f"ph-{name}", group, CORPUS_LANGS[name], 297, name, photo(name)), name)
    elif group == "shots":
        for key, (label, lang, mm) in SHOTS.items():
            add(RasterPage(f"sh-{label}", group, lang, mm, None, photo(key)), key)
    elif group == "books":
        field = {}
        with open(os.path.join(args.photos, "books", "field.tsv"), encoding="utf-8") as f:
            rows = [l.rstrip("\n").split("\t") for l in f]
        for r in rows[1:]:
            field[r[0]] = float(r[3])
        for key in sorted(field):
            add(RasterPage(f"bk-{key}", group, "ita", field[key], None, photo(key)), key)
    elif group in ("ita", "eng"):
        import pymupdf
        if group == "ita" and not PDF_ITA[0]:
            sys.exit("questions.py: set OCR_QUESTIONS_ITA_PDF to the Italian PDF's path")
        sources = [(PDF_ITA[0], PDF_ITA[1], "it")] if group == "ita" else PDF_ENG
        for path, indices, tag in sources:
            src = pymupdf.open(path)
            for i in indices:
                for ppi in SCAN_PPI:
                    pid = f"{group[:2]}-{tag}-p{i + 1:02d}-s{ppi}"
                    pdf_truth(args.work, pid, src[i])
                    out.append(PdfPage(pid, group, "ita" if group == "ita" else "eng", pid, src, i, ppi))
    return out


def current_quad(detect, key):
    """The current detector's quad for a photo: `ours` as ScannerBenchTest writes it, `old` in a detector comparison's file."""
    entry = detect.get(key, {})
    return (entry.get("ours") or entry.get("old") or {}).get("quad")


def quad_iou(a, b):
    a, b = np.array(a, np.float32), np.array(b, np.float32)
    inter, _ = cv2.intersectConvexConvex(a, b)
    return float(inter / (cv2.contourArea(a) + cv2.contourArea(b) - inter))


def scale_for(page, rule, xh):
    """(scale of the natural image, DPI passed to Tesseract) for a rule: dpiN or xhN."""
    if rule.startswith("dpi"):
        dpi = int(rule[3:])
        return dpi / page.natural_dpi(), dpi
    target = int(rule[2:])
    if math.isnan(xh):  # too few letters to measure: the rule falls back to the DPI it implies for 11 pt text
        return round(300 * target / REF_XH_300) / page.natural_dpi(), round(300 * target / REF_XH_300)
    return target / xh, round(300 * target / REF_XH_300)


def grayscale_filter(g):
    """Our Grayscale filter (DocumentFilters.Grayscale) on a grey page: 0.5% percentile auto-levels. The filter's BT.709 luma differs from
    BT.601 by at most a few levels on these near-neutral pages, so it is applied to the BT.601 grey."""
    hist = np.bincount(g.ravel(), minlength=256)
    total = hist.sum()
    target = math.floor(total * 0.005)
    low = int(np.argmax(np.cumsum(hist) > target))
    high = 255 - int(np.argmax(np.cumsum(hist[::-1]) > target))
    span = max(1, high - low)
    lut = np.clip(np.floor((np.arange(256) - low) / span * 255 + 0.5), 0, 255).astype(np.uint8)
    return lut[g]


def stored_page(rgb):
    """What the PDF holds for a page with the default (Original) filter: the warp capped at 2400 px, JPEG q85 (StoredImage)."""
    scale = min(1.0, 2400 / max(rgb.shape[:2]))
    small = resize(rgb, scale)
    ok, jpg = cv2.imencode(".jpg", cv2.cvtColor(small, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
    return cv2.imdecode(jpg, cv2.IMREAD_COLOR)[:, :, ::-1], scale


# ---------------------------------------------------------------------------------------------------------------------------- prepare

def load_meta(work):
    path = os.path.join(work, "pages.json")
    return json.load(open(path, encoding="utf-8")) if os.path.isfile(path) else {}


def save_meta(work, meta):
    tmp = os.path.join(work, "pages.json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=1, ensure_ascii=False)
    os.replace(tmp, os.path.join(work, "pages.json"))


def prepare(args):
    labels = json.load(open(args.labels, encoding="utf-8"))
    meta = load_meta(args.work)
    out_dir = os.path.join(args.work, "img", args.set)
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    rows = []

    def emit(page, variant, img, dpi, psm="", scale=1.0):
        name = f"{page.id}~{variant}.png"
        cv2.imwrite(os.path.join(out_dir, name), img, [cv2.IMWRITE_PNG_COMPRESSION, 9])
        rows.append([name, page.id, variant, page.lang, dpi, 0, psm])
        meta.setdefault(page.id, page.meta()).setdefault("variants", {})[variant] = {
            "scale": scale, "dpi": dpi, "w": img.shape[1], "h": img.shape[0]}

    def describe(page):
        if hasattr(page, "xh"):
            return page.xh
        g = grey(page.natural())
        xh, n, ms = xheight(g)
        m = meta.setdefault(page.id, page.meta())
        m.update(page.meta())
        m.update({"natural_w": g.shape[1], "natural_h": g.shape[0], "natural_dpi": page.natural_dpi(), "xh": xh, "xh_n": n, "xh_ms": ms})
        page.xh = xh
        return xh

    def at_rule(page, rule):
        xh = describe(page)
        scale, dpi = scale_for(page, rule, xh)
        scale = min(scale, MAX_EDGE / max(page.natural().shape[:2]))
        return page.at(scale), dpi, scale

    if args.set.startswith("q1-"):
        for page in pages_of(args.set[3:], args, labels):
            describe(page)
            for rule in RULES:
                img, dpi, scale = at_rule(page, rule)
                emit(page, rule, img, dpi, scale=scale)
            print(f"[prepare] {page.id} xh {meta[page.id]['xh']:.1f}", flush=True)
            page.release()
    elif args.set == "q3":
        for group in ("clean", "photo", "ita", "eng"):
            for page in pages_of(group, args, labels):
                img, dpi, scale = at_rule(page, args.rule)
                for psm in PSMS:
                    emit(page, f"{args.rule}-psm{psm}", img, dpi, psm, scale)
                page.release()
                print(f"[prepare] {page.id}", flush=True)
    elif args.set == "q5":
        for group in ("clean", "photo", "books", "ita"):
            for page in pages_of(group, args, labels):
                if group == "ita" and not page.id.endswith("s300"):
                    continue
                img, dpi, scale = at_rule(page, args.rule)
                emit(page, f"{args.rule}-osd", img, dpi, scale=scale)
                page.release()
                print(f"[prepare] {page.id}", flush=True)
    elif args.set == "q6":
        # The detector's corners come in image orientation, the labels' in reading order: the detector quad is taken from the corner that
        # matches the label's first, so both warps come out upright (orientation is question 5), and both are scaled like the label crop.
        detect = json.load(open(args.detect, encoding="utf-8"))
        for group in ("photo", "books", "shots"):
            for page in pages_of(group, args, labels):
                quad, label = current_quad(detect, page.key), labels[page.key]["quad"]
                shift = min(range(4), key=lambda k: sum(math.dist(quad[(i + k) % 4], label[i]) for i in range(4)))
                quad = [quad[(i + shift) % 4] for i in range(4)]
                _, dpi, scale = at_rule(page, args.rule)
                label_long = max(page.natural().shape[:2])
                page.release()
                auto = RasterPage(page.id, page.group, page.lang, page.long_mm, page.truth,
                                  lambda key=page.key, q=quad: warp_natural(read_rgb(os.path.join(os.path.dirname(args.labels), "upright",
                                                                                                 key + ".png")), q))
                ratio = max(auto.natural().shape[:2]) / label_long
                emit(auto, f"{args.rule}-auto", auto.at(scale), dpi, scale=scale)
                meta[page.id]["auto"] = {"iou": quad_iou(quad, label), "turned": shift, "long_ratio": ratio}
                auto.release()
                print(f"[prepare] {page.id} auto", flush=True)
    elif args.set == "q8":
        for group in ("photo", "shots", "books"):
            for page in pages_of(group, args, labels):
                img, dpi, scale = at_rule(page, args.rule)
                emit(page, f"{args.rule}-grayscale", grayscale_filter(img), dpi, scale=scale)
                stored, s0 = stored_page(page.natural())
                emit(page, f"{args.rule}-stored", grey(resize(stored, scale / s0)), dpi, scale=scale)
                page.release()
                print(f"[prepare] {page.id}", flush=True)
    else:
        sys.exit(f"unknown set {args.set}")
    with open(os.path.join(out_dir, "images.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("image\tpage\tvariant\tlang\tdpi\torder\tpsm\n")
        for r in rows:
            f.write("\t".join(map(str, r)) + "\n")
    save_meta(args.work, meta)
    mb = sum(os.path.getsize(os.path.join(out_dir, n)) for n in os.listdir(out_dir)) / 1e6
    print(f"[prepare] {args.set}: {len(rows)} images, {mb:.0f} MB")


# ------------------------------------------------------------------------------------------------------------------------------ score

GROUPS = {"clean": "corpus, clean render", "photo": "corpus, printed and photographed", "ita": "Italian PDF, simulated scans",
          "eng": "English PDFs, simulated scans", "shots": "shots (receipt, ticket, sachet, A4 pages)", "books": "book pages",
          "hand": "handwritten sheets"}
TRUTH_GROUPS = ["clean", "photo", "ita", "eng"]
PUNCT = ".,;:!?()[]{}\"'«»“”‘’…*"
X_LETTERS = re.compile(r"^[acemnorsuvwxz]{3,}$")


class FastScore(sc.Score):
    """score.Score with the bit-vector edit distance (same result, fast on whole pages)."""

    def add(self, ref, hyp):
        ref, hyp = sc.normalise(ref), sc.normalise(hyp)
        self.char_err += sc.fast_levenshtein(ref, hyp)
        self.chars += len(ref)
        rw, hw = ref.split(), hyp.split()
        self.word_err += sc.fast_levenshtein(rw, hw)
        self.words += len(rw)
        self.missing += sum((Counter(rw) - Counter(hw)).values())
        return self


class Run:
    """One image as read by one model: text, words (block, para, line, conf, box, text), time and memory."""

    def __init__(self, mdir, stem):
        self.text = sc.read_text(os.path.join(mdir, stem + ".txt"))
        self.words = [(int(r["block"]), int(r["para"]), int(r["line"]), float(r["conf"]),
                       (int(r["left"]), int(r["top"]), int(r["right"]), int(r["bottom"])), r["text"])
                      for r in sc.read_tsv(os.path.join(mdir, stem + ".words.tsv"))]
        row = sc.read_tsv(os.path.join(mdir, stem + ".runs.tsv"))[0]
        self.ms = int(row["recognise_ms"])
        self.native_mb = (int(row["native_peak_kb"]) - int(row["native_base_kb"])) / 1024
        self.pss_mb = int(row["pss_peak_kb"]) / 1024
        self.w, self.h = int(row["width"]), int(row["height"])

    def mean_conf(self):
        return statistics.fmean(w[3] for w in self.words) if self.words else float("nan")

    def text_at(self, threshold):
        """The text rebuilt from the words with confidence at or above threshold, one line per text line."""
        lines = defaultdict(list)
        for b, p, l, c, _, t in self.words:
            if c >= threshold:
                lines[(b, p, l)].append(t)
        return "\n".join(" ".join(v) for _, v in sorted(lines.items()))


def load_runs(images_out):
    runs, osd = {}, []
    for set_name in sorted(os.listdir(images_out)):
        if not set_name.startswith("q"):
            continue
        for model in sorted(os.listdir(os.path.join(images_out, set_name))):
            mdir = os.path.join(images_out, set_name, model)
            if not os.path.isdir(mdir):
                continue
            for name in sorted(os.listdir(mdir)):
                if model == "osd" and name.endswith(".osd.tsv"):
                    osd += sc.read_tsv(os.path.join(mdir, name))
                elif name.endswith(".runs.tsv"):
                    page, variant = name[:-len(".runs.tsv")].split("~")
                    runs[(page, variant, model)] = Run(mdir, name[:-len(".runs.tsv")])
    return runs, osd


def group_of(pid, meta):
    return "hand" if pid.startswith("bk-hand") else meta[pid]["group"]


def found_junk(ref, run, long_pt):
    """PaperKnife+ PC's study metric (ocr_score.py page_counts at threshold 0): reference words paired by position with the OCR word
    overlapping them most (at least half of the smaller box); found = exact match after stripping punctuation; junk = OCR words lying where
    the page has no text. OCR boxes are mapped to points through the image's long edge. Returns (found, ref words, junk, OCR words)."""
    k = long_pt / max(run.w, run.h)

    def inter(a, b):
        w = min(a[2], b[2]) - max(a[0], b[0])
        h = min(a[3], b[3]) - max(a[1], b[1])
        return w * h if w > 0 and h > 0 else 0.0

    def area(r):
        return max(1e-6, (r[2] - r[0]) * (r[3] - r[1]))

    ref = [(w.strip(PUNCT), b) for w, b in ref if w.strip(PUNCT) and "�" not in w]
    ocr = [(t.strip(PUNCT), [v * k for v in box]) for _, _, _, _, box, t in run.words if t.strip(PUNCT)]
    found = 0
    for rw, rb in ref:
        best, score = "", 0.0
        for ow, ob in ocr:
            i = inter(rb, ob)
            if i and i / min(area(rb), area(ob)) >= 0.5 and i > score:
                best, score = ow, i
        found += best == rw
    junk = sum(1 for _, ob in ocr if not any(inter(ob, rb) / area(ob) >= 0.3 for _, rb in ref))
    return found, len(ref), junk, len(ocr)


class Agg:
    """Pages of one configuration added up: CER/WER/missed words (micro average), study metrics, words, confidence, time, memory."""

    def __init__(self):
        self.s = FastScore()
        self.pages = self.ms = self.found = self.ref = self.junk = self.ocr = self.nwords = 0
        self.conf = 0.0
        self.native = self.pss = 0.0
        self.has_truth = self.has_study = False

    def add(self, run, truth=None, study=None):
        self.pages += 1
        self.ms += run.ms
        self.nwords += len(run.words)
        self.conf += sum(w[3] for w in run.words)
        self.native = max(self.native, run.native_mb)
        self.pss = max(self.pss, run.pss_mb)
        if truth is not None:
            self.has_truth = True
            self.s.add(truth, run.text)
        if study is not None:
            self.has_study = True
            f, r, j, o = study
            self.found += f
            self.ref += r
            self.junk += j
            self.ocr += o
        return self

    def cells(self):
        out = []
        if self.has_truth:
            out += [sc.fmt(self.s.cer()), sc.fmt(self.s.wer()), sc.fmt(self.s.missed())]
        if self.has_study:
            out += [sc.fmt(100 * self.found / max(1, self.ref), 1), sc.fmt(100 * self.junk / max(1, self.ocr), 1)]
        if not self.has_truth:
            out += [str(self.nwords), sc.fmt(self.conf / self.nwords if self.nwords else float("nan"), 1)]
        return out + [f"{self.ms / self.pages / 1000:.2f}", f"{self.native:.0f}", f"{self.pss:.0f}"]


def header(group, study, extra="Rule"):
    cols = [extra, "Model", "Pages"]
    if group in TRUTH_GROUPS:
        cols += ["CER", "WER", "Missed words"]
    if study:
        cols += ["Found (study)", "Junk (study)"]
    if group not in TRUTH_GROUPS:
        cols += ["Words", "Mean conf"]
    cols += ["s/page", "Native +MB (max)", "PSS MB (max)"]
    return ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]


def score(args):
    work = args.work
    meta = load_meta(work)
    images_out = os.path.join(HERE, "images-out")
    runs, osd = load_runs(images_out)
    corpus_truth = {os.path.splitext(n)[0]: sc.read_text(os.path.join(HERE, "corpus", "truth", n))
                    for n in os.listdir(os.path.join(HERE, "corpus", "truth")) if n.endswith(".txt")}
    truth, refwords = {}, {}
    for pid, m in meta.items():
        if m["truth"] in corpus_truth:
            truth[pid] = corpus_truth[m["truth"]]
        elif m["truth"]:
            truth[pid] = sc.read_text(os.path.join(work, "truth", pid + ".txt"))
            refwords[pid] = json.load(open(os.path.join(work, "truth", pid + ".words.json"), encoding="utf-8"))
    long_pt = {pid: m["long_mm"] / 25.4 * 72 for pid, m in meta.items()}
    study_cache = {}

    def study(pid, key):
        if pid not in refwords:
            return None
        if key not in study_cache:
            study_cache[key] = found_junk(refwords[pid], runs[key], long_pt[pid])
        return study_cache[key]

    def agg(pids, variant, model):
        a = Agg()
        for pid in pids:
            key = (pid, variant, model)
            if key in runs:
                a.add(runs[key], truth.get(pid), study(pid, key))
        return a

    groups = defaultdict(list)
    for pid in sorted(meta):
        groups[group_of(pid, meta)].append(pid)
    models = [m for m in ("fast", "best") if any(k[2] == m for k in runs)]
    md = ["# Open OCR questions, scored on the OnePlus 6T", "",
          "Generated by `docs/ocr-bench/questions.py score` from `run.sh --images` runs of the sets `questions.py prepare` writes (method in "
          "each section). Aggregates and short labels only: the photos, the PDFs and every recognised text stay outside git. CER, WER and "
          "missed words in %, lower is better; missed words ignores order. s/page is Tesseract's recognition time (`setImage` + "
          "`getUTF8Text`); native +MB the native heap growth during recognition, PSS the whole test process; both are the worst page.", ""]

    # ---- question 1
    miss_cache = {}

    def miss(pid, rule, model):
        if (pid, rule, model) not in miss_cache:
            r = runs.get((pid, rule, model))
            miss_cache[(pid, rule, model)] = None if r is None else FastScore().add(truth[pid], r.text).missed()
        return miss_cache[(pid, rule, model)]

    md += ["## 1. Resolution: fixed DPI or a text-height rule", ""]
    rules_seen = [r for r in RULES if any(k[1] == r for k in runs)]
    winners = {}
    for group in ["clean", "photo", "ita", "eng", "shots", "books", "hand"]:
        pids = groups.get(group, [])
        if not pids:
            continue
        studyish = group in ("ita", "eng")
        md += [f"### {GROUPS[group]} ({len(pids)} pages)", ""] + header(group, studyish)
        best_rule = {}
        for model in models:
            rows = []
            for rule in rules_seen:
                a = agg(pids, rule, model)
                if a.pages:
                    rows.append((rule, a))
                    md.append(f"| {rule} | {model} | {a.pages} | " + " | ".join(a.cells()) + " |")
            if not rows:
                continue
            if group in TRUTH_GROUPS:
                best_rule[model] = min(rows, key=lambda r: (round(r[1].s.missed(), 1), r[1].s.cer()))[0]
            else:
                best_rule[model] = max(rows, key=lambda r: r[1].conf / max(1, r[1].nwords))[0]
        winners[group] = best_rule
        md += ["", "Winner (" + ("fewest missed words, then CER" if group in TRUTH_GROUPS else "highest mean word confidence; no truth") +
               "): " + ", ".join(f"{m} {r}" for m, r in best_rule.items()), ""]
        if studyish:
            md += ["By scan resolution (fast): missed words in % and the median x-height Tesseract got, in px (the natural x-height times "
                   "the rule's scale).", "", "| Rule | " + " | ".join(f"{p} ppi scans: missed / x-height" for p in SCAN_PPI) + " |",
                   "|---|" + "---|" * len(SCAN_PPI)]
            for rule in rules_seen:
                cells = []
                for ppi in SCAN_PPI:
                    sub = [p for p in pids if p.endswith(f"-s{ppi}")]
                    a = agg(sub, rule, "fast")
                    xs = [meta[p]["xh"] * meta[p]["variants"][rule]["scale"] for p in sub
                          if rule in meta[p].get("variants", {}) and not math.isnan(meta[p]["xh"])]
                    cells.append(f"{a.s.missed():.1f} / {statistics.median(xs):.0f}" if a.pages and xs else "-")
                md.append(f"| {rule} | " + " | ".join(cells) + " |")
            md.append("")
        if group in TRUTH_GROUPS:
            md += ["Where a rule fails: pages whose missed words are at least 5 points above that page's best rule (fast).", "",
                   "| Rule | Failing pages | Worst page (missed %, best rule's %) |", "|---|---|---|"]
            for rule in rules_seen:
                fails = []
                for pid in pids:
                    vals = {r: miss(pid, r, "fast") for r in rules_seen}
                    vals = {r: v for r, v in vals.items() if v is not None}
                    if rule in vals and vals[rule] >= min(vals.values()) + 5:
                        fails.append((vals[rule] - min(vals.values()), pid, vals[rule], min(vals.values())))
                worst = max(fails) if fails else None
                md.append(f"| {rule} | {len(fails)} of {len(pids)} | " +
                          (f"{worst[1]} ({worst[2]:.1f}, {worst[3]:.1f})" if worst else "-") + " |")
            md.append("")
    # Overall: mean over the truth groups of each group's missed words (macro average), fast and best.
    md += ["### Overall", "", "Macro average over the four sets with truth (each set weighs the same), missed words and CER in %.", "",
           "| Rule | Model | Mean missed words | Mean CER | Worst set's missed words |", "|---|---|---|---|---|"]
    overall = {}
    for model in models:
        for rule in rules_seen:
            vals = [agg(groups[g], rule, model) for g in TRUTH_GROUPS if groups.get(g)]
            if any(v.pages == 0 for v in vals):
                continue
            m = statistics.fmean(v.s.missed() for v in vals)
            overall[(rule, model)] = m
            md.append(f"| {rule} | {model} | {m:.2f} | {statistics.fmean(v.s.cer() for v in vals):.2f} | "
                      f"{max(v.s.missed() for v in vals):.2f} |")
    win = min((k for k in overall if k[1] == "fast"), key=lambda k: overall[k], default=(None, None))[0]
    md += ["", f"Overall winner (fast): **{win}**.", ""]

    # x-height estimate against Tesseract's word boxes
    md += ["### The x-height estimate against Tesseract", "",
           "Estimate = `xheight()` on the natural grey page (before any scaling). Tesseract's x-height = median box height of the words it read "
           "that contain only x-height letters (a c e m n o r s u v w x z, at least 3), from the dpi300 fast run, divided by that image's scale. "
           "Ratio = estimate / Tesseract; the effective DPI is what the x-height rule chose for that page (median over the set).", "",
           "| Set | Pages | Median ratio | Ratio range | Pages off by > 15% | Estimate ms (median) | Effective DPI xh20 / xh25 / xh30 |",
           "|---|---|---|---|---|---|---|"]
    for group in ["clean", "photo", "ita", "eng", "shots", "books", "hand"]:
        ratios, ms = [], []
        for pid in groups.get(group, []):
            r = runs.get((pid, "dpi300", "fast"))
            m = meta[pid]
            if r is None or not m.get("xh") or math.isnan(m["xh"]):
                continue
            hs = [w[4][3] - w[4][1] for w in r.words if X_LETTERS.match(w[5])]
            if len(hs) < 5:
                continue
            tess = statistics.median(hs) / m["variants"]["dpi300"]["scale"]
            ratios.append(m["xh"] / tess)
            ms.append(m["xh_ms"])
        if not ratios:
            continue
        eff = []
        for t in XH_TARGETS:
            v = [meta[p]["variants"][f"xh{t}"]["scale"] * meta[p]["natural_dpi"] for p in groups[group] if f"xh{t}" in meta[p].get("variants", {})]
            eff.append(f"{statistics.median(v):.0f}" if v else "-")
        md.append(f"| {GROUPS[group]} | {len(ratios)} | {statistics.median(ratios):.2f} | {min(ratios):.2f}-{max(ratios):.2f} | "
                  f"{sum(abs(x - 1) > 0.15 for x in ratios)} | {statistics.median(ms):.0f} | {' / '.join(eff)} |")
    md.append("")

    # ---- question 2
    if win:
        md += ["## 2. Model at the winning rule", "", f"Rule {win}.", ""]
        for group in ["clean", "photo", "ita", "eng", "shots", "books", "hand"]:
            pids = groups.get(group, [])
            rows = [(model, agg(pids, win, model)) for model in models]
            rows = [r for r in rows if r[1].pages]
            if not rows:
                continue
            md += [f"### {GROUPS[group]}", ""] + header(group, group in ("ita", "eng"), extra="Rule")
            md += [f"| {win} | {m} | {a.pages} | " + " | ".join(a.cells()) + " |" for m, a in rows] + [""]

    # ---- question 3
    psm_rows = [k for k in runs if "-psm" in k[1]]
    if psm_rows:
        rule = psm_rows[0][1].split("-psm")[0]
        model = psm_rows[0][2]
        md += ["## 3. Page segmentation at the winning rule", "", f"Rule {rule}, {model}. PSM 3 from question 1.", ""]
        for group in TRUTH_GROUPS:
            pids = groups.get(group, [])
            md += [f"### {GROUPS[group]}", ""] + header(group, group in ("ita", "eng"), extra="PSM")
            for psm in [3] + PSMS:
                a = agg(pids, rule if psm == 3 else f"{rule}-psm{psm}", model)
                if a.pages:
                    md.append(f"| {psm} | {model} | {a.pages} | " + " | ".join(a.cells()) + " |")
            md.append("")
        md += ["Per page, PSM with the fewest missed words then lowest CER (ties go to the lower number):", "",
               "| Page | " + " | ".join(f"PSM {p} CER / missed" for p in [3] + PSMS) + " |", "|---|" + "---|" * (1 + len(PSMS))]
        for group in ["clean", "photo"]:
            for pid in groups.get(group, []):
                cells = []
                for psm in [3] + PSMS:
                    r = runs.get((pid, rule if psm == 3 else f"{rule}-psm{psm}", model))
                    cells.append("-" if r is None else (lambda s: f"{s.cer():.1f} / {s.missed():.1f}")(FastScore().add(truth[pid], r.text)))
                md.append(f"| {pid} | " + " | ".join(cells) + " |")
        md.append("")

    # ---- question 4
    if win:
        md += ["## 4. Word-confidence filter for the .txt", "",
               f"Rule {win}. The text is rebuilt from the words at or above the threshold; dropped = words removed, real = dropped words that "
               "occur in the page's truth (pure loss). Sets without truth show the share of words dropped.", ""]
        for model in models:
            md += [f"### {model}", "", "| Threshold | " + " | ".join(f"{g} CER (dropped / real)" for g in TRUTH_GROUPS) +
                   " | shots dropped % | books dropped % | handwriting dropped % |", "|---|" + "---|" * (len(TRUTH_GROUPS) + 3)]
            for t in range(0, 100, 10):
                cells = []
                for g in TRUTH_GROUPS:
                    s, dropped, real = FastScore(), 0, 0
                    for pid in groups.get(g, []):
                        r = runs.get((pid, win, model))
                        if r is None:
                            continue
                        s.add(truth[pid], r.text_at(t))
                        bag = Counter(sc.normalise(truth[pid]).split())
                        for w in r.words:
                            if w[3] < t:
                                dropped += 1
                                if bag[w[5]] > 0:
                                    real += 1
                    cells.append(f"{s.cer():.2f} ({dropped} / {real})" if s.chars else "-")
                for g in ("shots", "books", "hand"):
                    tot = drop = 0
                    for pid in groups.get(g, []):
                        r = runs.get((pid, win, model))
                        if r:
                            tot += len(r.words)
                            drop += sum(w[3] < t for w in r.words)
                    cells.append(f"{100 * drop / tot:.1f}" if tot else "-")
                md.append(f"| {t} | " + " | ".join(cells) + " |")
            md.append("")

    # ---- question 5
    if osd:
        md += ["## 5. Rotation: OSD confidence floor", "",
               "Every upright page turned clockwise by 0, 90, 180 and 270 degrees, then `detectOrientationScript()` (fast data's osd, legacy "
               "engine, fresh instance per call). A page is turned by OSD's `rotate` only when its confidence reaches the floor. Correct = the page "
               "ends upright; wrong turn = OSD turned it, but not upright; left = a turned page left as it was; spoiled = an upright page turned. "
               "Counts over pages x turns.", ""]
        by_group = defaultdict(list)
        for r in osd:
            by_group[group_of(r["page"], meta)].append(r)
        md += ["| Set | Floor | Correct | Wrong turn | Left turned | Spoiled upright | Of |", "|---|---|---|---|---|---|---|"]
        floor_tot = defaultdict(lambda: [0, 0, 0, 0, 0])
        for g in ["clean", "photo", "ita", "books", "hand"]:
            rows = by_group.get(g, [])
            if not rows:
                continue
            for floor in range(0, 16):
                c = [0, 0, 0, 0]
                for r in rows:
                    turn = int(r["turn"])
                    conf = float(r["osd_conf"]) if r["osd_conf"] else -1
                    rot = int(r["rotate"]) if r["rotate"] else 0
                    applied = rot if conf >= floor else 0
                    final = (turn + applied) % 360
                    if final == 0:
                        c[0] += 1
                    elif applied:
                        c[1] += 1
                    elif turn:
                        c[2] += 1
                    if turn == 0 and applied:
                        c[3] += 1
                for i in range(4):
                    floor_tot[floor][i] += c[i]
                floor_tot[floor][4] += len(rows)
                if floor in (0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 15):
                    md.append(f"| {GROUPS[g]} | {floor} | {c[0]} | {c[1]} | {c[2]} | {c[3]} | {len(rows)} |")
        md += ["", "All sets together:", "", "| Floor | Correct | Wrong turn | Left turned | Spoiled upright | Of |", "|---|---|---|---|---|---|"]
        for floor in range(0, 16):
            c = floor_tot[floor]
            md.append(f"| {floor} | {c[0]} | {c[1]} | {c[2]} | {c[3]} | {c[4]} |")
        confs = defaultdict(list)
        for r in osd:
            if r["osd_conf"]:
                ok = (int(r["turn"]) + int(r["rotate"])) % 360 == 0
                confs[(group_of(r["page"], meta), ok)].append(float(r["osd_conf"]))
        md += ["", "OSD confidence when its answer was right / wrong (median, min-max):", "",
               "| Set | Right: n, median, range | Wrong: n, median, range |", "|---|---|---|"]
        for g in ["clean", "photo", "ita", "books", "hand"]:
            cells = []
            for ok in (True, False):
                v = confs.get((g, ok), [])
                cells.append(f"{len(v)}, {statistics.median(v):.1f}, {min(v):.1f}-{max(v):.1f}" if v else "0")
            if any(confs.get((g, ok)) for ok in (True, False)):
                md.append(f"| {GROUPS[g]} | " + " | ".join(cells) + " |")
        ms = [int(r["osd_ms"]) for r in osd if r["osd_ms"]]
        md += ["", f"OSD time per call: median {statistics.median(ms) / 1000:.2f} s, max {max(ms) / 1000:.2f} s.", ""]

    # ---- question 6
    auto = sorted({k[0] for k in runs if k[1].endswith("-auto")})
    if auto:
        rule = next(k[1] for k in runs if k[1].endswith("-auto")).rsplit("-auto", 1)[0]
        md += ["## 6. Confirmed (labelled) crop against the automatic crop", "",
               f"Rule {rule}. Label = the page warped with the hand-marked quad; auto = warped with the current one-shot detector's quad "
               "(`DocumentDetector.detectFromRgba`, the quads of the scanner benchmark). Both through the same cv2 warp and scaling. With truth: "
               "CER / missed words of each; without: agreement of the auto-crop text with the label-crop text (CER, and the label text's words "
               "missing from the auto text, order-free).", ""]
        for model in models:
            md += [f"### {model}", "", "| Page | Detector IoU | Label: CER / missed | Auto: CER / missed | Auto vs label: CER / words lost |",
                   "|---|---|---|---|---|"]
            lost_tot = []
            for pid in auto:
                rl, ra = runs.get((pid, rule, model)), runs.get((pid, f"{rule}-auto", model))
                if rl is None or ra is None:
                    continue
                iou = meta[pid].get("auto", {}).get("iou", float("nan"))
                if pid in truth:
                    sl, sa = FastScore().add(truth[pid], rl.text), FastScore().add(truth[pid], ra.text)
                    c1, c2 = f"{sl.cer():.1f} / {sl.missed():.1f}", f"{sa.cer():.1f} / {sa.missed():.1f}"
                else:
                    c1 = c2 = "-"
                cer, _, lost = sc.agreement(rl.text, ra.text)
                lost_tot.append(lost)
                md.append(f"| {pid} | {iou:.2f} | {c1} | {c2} | {cer:.1f} / {lost:.1f} |")
            if lost_tot:
                md += ["", f"Median words lost: {statistics.median(lost_tot):.1f}%; pages losing more than 10%: "
                       f"{sum(x > 10 for x in lost_tot)} of {len(lost_tot)}.", ""]

    # ---- question 7
    if win:
        md += ["## 7. Handwriting: can a page statistic flag it?", "",
               "Every page read at every rule (fast and best): mean word confidence, and the share of words under confidence 50. Handwritten = "
               "the two sheets of notes; printed = everything else.", ""]
        stats = []
        for (pid, variant, model), r in runs.items():
            if variant not in RULES or not r.words:
                continue
            low = 100 * sum(w[3] < 50 for w in r.words) / len(r.words)
            stats.append((group_of(pid, meta) == "hand", pid, variant, model, r.mean_conf(), low))
        md += ["| Statistic | Handwritten (min-max) | Printed (min-max) | Printed pages under the handwritten max |", "|---|---|---|---|"]
        for idx, name in ((4, "mean word confidence"), (5, "% words under 50")):
            hv = [s[idx] for s in stats if s[0]]
            pv = [s[idx] for s in stats if not s[0]]
            if not hv:
                continue
            if idx == 4:
                over = [s for s in stats if not s[0] and s[idx] <= max(hv)]
            else:
                over = [s for s in stats if not s[0] and s[idx] >= min(hv)]
            md.append(f"| {name} | {min(hv):.1f}-{max(hv):.1f} | {min(pv):.1f}-{max(pv):.1f} | {len(over)} of {len(pv)} |")
        hv = [s[4] for s in stats if s[0]]
        pv = sorted(s[4] for s in stats if not s[0])
        if hv:
            md += ["", "Lowest printed readings (mean word confidence):", "", "| Page | Rule | Model | Mean conf | % under 50 |",
                   "|---|---|---|---|---|"]
            for s in sorted((s for s in stats if not s[0]), key=lambda s: s[4])[:8]:
                md.append(f"| {s[1]} | {s[2]} | {s[3]} | {s[4]:.1f} | {s[5]:.1f} |")
            md += ["", "Handwritten readings: mean conf " + ", ".join(sorted(f"{s[4]:.0f}" for s in stats if s[0])) + ".", ""]
            for t in (50, 55, 60, 65, 70):
                fp = sum(v < t for v in pv)
                fn = sum(v >= t for v in hv)
                md.append(f"- Flag below {t}: printed readings flagged {fp} of {len(pv)}, handwritten readings missed {fn} of {len(hv)}.")
            md.append("")

    # ---- question 8
    q8 = sorted({k[0] for k in runs if k[1].endswith("-grayscale")})
    if q8:
        rule = next(k[1] for k in runs if k[1].endswith("-grayscale")).rsplit("-grayscale", 1)[0]
        md += ["## 8. OCR input image", "",
               f"Rule {rule}. plain = BT.601 grey of the warp (question 1); grayscale = our Grayscale filter (0.5% auto-levels) on it; stored = "
               "the page as the PDF holds it with the default Original filter (warp capped at 2400 px, JPEG q85), then grey and scaled to the "
               "same size.", ""]
        for group in ["photo", "shots", "books", "hand"]:
            pids = [p for p in groups.get(group, []) if p in q8]
            if not pids:
                continue
            md += [f"### {GROUPS[group]}", ""] + header(group, False, extra="Input")
            for model in models:
                for name, v in (("plain", rule), ("grayscale", f"{rule}-grayscale"), ("stored", f"{rule}-stored")):
                    a = agg(pids, v, model)
                    if a.pages:
                        md.append(f"| {name} | {model} | {a.pages} | " + " | ".join(a.cells()) + " |")
            md.append("")
        md += ["Receipts, per input (CER / missed words for the corpus receipt; words / mean conf for the photographed one):", "",
               "| Page | Model | plain | grayscale | stored |", "|---|---|---|---|---|"]
        for pid in ("ph-d-receipt", "sh-receipt"):
            for model in models:
                cells = []
                for v in (rule, f"{rule}-grayscale", f"{rule}-stored"):
                    r = runs.get((pid, v, model))
                    if r is None:
                        cells.append("-")
                    elif pid in truth:
                        s = FastScore().add(truth[pid], r.text)
                        cells.append(f"{s.cer():.1f} / {s.missed():.1f}")
                    else:
                        cells.append(f"{len(r.words)} / {r.mean_conf():.1f}")
                md.append(f"| {pid} | {model} | " + " | ".join(cells) + " |")
        md.append("")

    device_ms = sum(r.ms for r in runs.values())
    md += [f"Recognition time summed over every run above: {device_ms / 60000:.0f} min.", ""]
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(md).rstrip() + "\n")
    print(f"wrote {args.out}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("prepare")
    p.add_argument("--work", required=True)
    p.add_argument("--photos", required=True)
    p.add_argument("--labels", required=True)
    p.add_argument("--detect", required=True)
    p.add_argument("--set", required=True)
    p.add_argument("--rule", default="dpi300")
    s = sub.add_parser("score")
    s.add_argument("--work", required=True)
    s.add_argument("--out", default=os.path.join(HERE, "questions-results.md"))
    args = ap.parse_args()
    if args.cmd == "prepare":
        prepare(args)
    else:
        score(args)


if __name__ == "__main__":
    main()
