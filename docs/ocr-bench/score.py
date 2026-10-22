"""Scores OcrBenchmarkTest output against the ground truth and writes results.md.

Usage:
  python score.py [--results DIR] [--truth DIR] [--out FILE]
  python score.py --check-images DIR     list which images in DIR match a ground-truth page (for adding real scans)
  python score.py --field DIR [--out FILE]   field mode (photos without ground truth, run.sh --field): writes field-results.md

DIR (results) is the pulled `ocr-bench/` folder: <set>/<model>-<dpi>/ with runs.tsv, <page>.txt, <page>.words.tsv and optional
failures.tsv, and the page segmentation runs as <set>/psm/<model>-<dpi>-psm<n>/. Text is NFC-normalised and whitespace-collapsed before scoring;
CER and WER are Levenshtein distance over characters or words, divided by the reference length, summed over pages (micro average).
"Missed words" ignores order (multiset difference), so a page read in the wrong order scores high CER/WER but low missed words.
Standard library only.
"""

import argparse
import csv
import os
import re
import sys
import unicodedata
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
THRESHOLDS = list(range(0, 100, 10))
IMAGE_NAME = re.compile(r"^([^.]+)(?:\.(a4|letter|legal))?(\.photo)?(?:\.(\d+)dpi)?\.(?:png|jpe?g)$", re.IGNORECASE)
PSM_ORDER = ["3", "1", "4", "6"]
PSM_NAMES = {"3": "3 auto (default)", "1": "1 auto + OSD", "4": "4 single column", "6": "6 single block"}


def normalise(text):
    return " ".join(unicodedata.normalize("NFC", text).split())


def levenshtein(a, b):
    # A shared prefix and suffix never change the distance; trimming them makes near-perfect pages cheap.
    start = 0
    while start < len(a) and start < len(b) and a[start] == b[start]:
        start += 1
    end = 0
    while end < len(a) - start and end < len(b) - start and a[-1 - end] == b[-1 - end]:
        end += 1
    a, b = a[start:len(a) - end], b[start:len(b) - end]
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        append = cur.append
        for j, cb in enumerate(b, 1):
            sub = prev[j - 1] + (ca != cb)
            ins = cur[j - 1] + 1
            dele = prev[j] + 1
            append(sub if sub <= ins and sub <= dele else (ins if ins <= dele else dele))
        prev = cur
    return prev[-1]


class Score:
    """Accumulated edit distances and reference lengths, so pages add up to a micro average."""

    def __init__(self):
        self.char_err = self.chars = self.word_err = self.words = self.missing = 0

    def add(self, ref, hyp):
        ref, hyp = normalise(ref), normalise(hyp)
        self.char_err += levenshtein(ref, hyp)
        self.chars += len(ref)
        rw, hw = ref.split(), hyp.split()
        self.word_err += levenshtein(rw, hw)
        self.words += len(rw)
        self.missing += sum((Counter(rw) - Counter(hw)).values())
        return self

    def cer(self):
        return 100.0 * self.char_err / self.chars if self.chars else float("nan")

    def wer(self):
        return 100.0 * self.word_err / self.words if self.words else float("nan")

    def missed(self):
        """Share of reference words absent from the output, ignoring order: separates recognition from reading-order errors."""
        return 100.0 * self.missing / self.words if self.words else float("nan")


def read_tsv(path):
    if not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t", quoting=csv.QUOTE_NONE))


def read_tables(parent, name):
    """The rows of every <parent>/<run>/<name>, one table per configuration."""
    if not os.path.isdir(parent):
        return []
    return [r for run in sorted(os.listdir(parent)) for r in read_tsv(os.path.join(parent, run, name))]


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def read_words(path):
    return [(float(r["conf"]), r["text"]) for r in read_tsv(path)]


def check_images(folder, truth):
    pages = {os.path.splitext(n)[0] for n in os.listdir(truth) if n.endswith(".txt")}
    ok = True
    for name in sorted(os.listdir(folder)):
        m = IMAGE_NAME.match(name)
        page = m.group(1) if m else None
        kind = "raw photo, detected and warped" if m and m.group(3) else "cropped to the sheet"
        status = f"ok ({kind})" if page in pages else "NO MATCH (expected one of: " + ", ".join(sorted(pages)) + ")"
        ok &= page in pages
        print(f"{name}: {status}")
    return ok


def fmt(v, digits=2):
    return "-" if v != v else f"{v:.{digits}f}"


def mb(kb):
    return f"{int(kb) / 1024:.0f}"


def failures(rows, title):
    if not rows:
        return []
    lines = [f"### {title}", "", "| Page | Model | DPI | PSM | Reason |", "|---|---|---|---|---|"]
    lines += [f"| {r['page']} | {r['model']} | {r['dpi']} | {r['psm']} | {r['reason']} |" for r in rows]
    return lines + [""]


def psm_table(psm_dir, truth):
    """The layout page per page segmentation mode (OcrBenchmarkTest.layoutPsm)."""
    rows = [r for r in read_tables(psm_dir, "runs.tsv") if r["page"] in truth]
    lines = []
    if rows:
        lines = ["### Page segmentation modes on the layout page", "",
                 "Only `e-layout`; the expected order is column by column (README). Low missed words with high CER means the right words in "
                 "the wrong order.", "",
                 "| Model | DPI | PSM | Recognise ms | CER | WER | Missed words |", "|---|---|---|---|---|---|---|"]
        rank = {p: i for i, p in enumerate(PSM_ORDER)}
        for r in sorted(rows, key=lambda r: (r["model"], int(r["dpi"]), rank.get(r["psm"], 9))):
            hyp = read_text(os.path.join(psm_dir, f"{r['model']}-{r['dpi']}-psm{r['psm']}", r["page"] + ".txt"))
            s = Score().add(truth[r["page"]], hyp)
            lines.append(f"| {r['model']} | {r['dpi']} | {PSM_NAMES.get(r['psm'], r['psm'])} | {r['recognise_ms']} | {fmt(s.cer())} | "
                         f"{fmt(s.wer())} | {fmt(s.missed())} |")
        lines.append("")
    return lines + failures(read_tables(psm_dir, "failures.tsv"), "Page segmentation: pages that could not be prepared")


def score_set(set_dir, set_name, truth):
    runs = [r for r in read_tables(set_dir, "runs.tsv") if r["page"] in truth]
    configs = sorted({(r["model"], int(r["dpi"])) for r in runs})
    flavor = runs[0]["flavor"] if runs else "?"
    out = [f"## Set `{set_name}`", "",
           f"Tesseract4Android flavor: `{flavor}`. One fresh Tesseract instance per page. Source: `render` = clean render at that DPI, "
           "`cropped` = image cropped to the sheet and scaled, `photo` = raw camera photo detected and warped by the scanner's own code; the "
           "number is the page's resolution in DPI before scaling (below the target DPI means upsampled).", ""]
    out += failures(read_tables(set_dir, "failures.tsv"), "Pages that could not be prepared (skipped)")

    out += ["### Per page", "",
            "| Page | Lang | Model | DPI | Source (DPI) | Pixels | Init ms | Recognise ms | Native heap peak MB (+ over base) | PSS peak MB (+) | "
            "VmHWM MB | Mean conf | CER | WER | Missed words |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    by_group = defaultdict(lambda: {"score": Score(), "ms": 0, "pages": 0, "native": 0, "pss": 0, "hwm": 0})
    for r in sorted(runs, key=lambda r: (r["page"], r["model"], int(r["dpi"]))):
        hyp = read_text(os.path.join(set_dir, f"{r['model']}-{r['dpi']}", r["page"] + ".txt"))
        s = Score().add(truth[r["page"]], hyp)
        nb, np_, pb, pp = (int(r[k]) for k in ("native_base_kb", "native_peak_kb", "pss_base_kb", "pss_peak_kb"))
        out.append(f"| {r['page']} | {r['lang']} | {r['model']} | {r['dpi']} | {r['source']} ({r['source_dpi']}) | {r['width']}x{r['height']} | "
                   f"{r['init_ms']} | {r['recognise_ms']} | {mb(np_)} (+{mb(np_ - nb)}) | {mb(pp)} (+{mb(pp - pb)}) | {mb(r['vm_hwm_kb'])} | "
                   f"{r['mean_conf']} | {fmt(s.cer())} | {fmt(s.wer())} | {fmt(s.missed())} |")
        for lang in (r["lang"], "all"):
            g = by_group[(lang, r["model"], int(r["dpi"]))]
            g["score"].add(truth[r["page"]], hyp)
            g["ms"] += int(r["recognise_ms"])
            g["pages"] += 1
            g["native"] = max(g["native"], np_ - nb)
            g["pss"] = max(g["pss"], pp)
            g["hwm"] = max(g["hwm"], int(r["vm_hwm_kb"]))

    out += ["", "### By language", "",
            "Time is the mean recognition time per page; memory is the worst page (native heap growth over the post-init baseline, "
            "and absolute PSS / VmHWM of the test process).", "",
            "| Language | Model | DPI | Pages | Mean s/page | Max native +MB | Max PSS MB | Max VmHWM MB | CER | WER | Missed words |",
            "|---|---|---|---|---|---|---|---|---|---|---|"]
    order = {"eng": 0, "ita": 1, "eng+ita": 2, "all": 3}
    for (lang, model, dpi), g in sorted(by_group.items(), key=lambda kv: (order.get(kv[0][0], 9), kv[0][1], kv[0][2])):
        out.append(f"| {lang} | {model} | {dpi} | {g['pages']} | {g['ms'] / g['pages'] / 1000:.1f} | {mb(g['native'])} | {mb(g['pss'])} | "
                   f"{mb(g['hwm'])} | {fmt(g['score'].cer())} | {fmt(g['score'].wer())} | {fmt(g['score'].missed())} |")

    out += ["", "### Low-confidence word filter", "",
            "Words with Tesseract word confidence below the threshold are dropped (text rebuilt from words.tsv in reading order); "
            "CER over all pages of the set. `dropped` is the number of words removed, `real` how many of them occur in the ground "
            "truth of their page (dropping those is pure loss).", "",
            "| Threshold | " + " | ".join(f"{m} {d} CER | dropped (real)" for m, d in configs) + " |",
            "|---|" + "---|---|" * len(configs)]
    for t in THRESHOLDS:
        cells = []
        for model, dpi in configs:
            s, dropped, real = Score(), 0, 0
            for r in runs:
                if r["model"] != model or int(r["dpi"]) != dpi:
                    continue
                words = read_words(os.path.join(set_dir, f"{model}-{dpi}", r["page"] + ".words.tsv"))
                ref_words = set(normalise(truth[r["page"]]).split())
                gone = [w for c, w in words if c < t]
                dropped += len(gone)
                real += sum(1 for w in gone if unicodedata.normalize("NFC", w) in ref_words)
                s.add(truth[r["page"]], " ".join(w for c, w in words if c >= t))
            cells += [fmt(s.cer()), f"{dropped} ({real})"]
        out.append(f"| {t} | " + " | ".join(cells) + " |")
    out.append("")
    return out + psm_table(os.path.join(set_dir, "psm"), truth)


# Field mode: photos without ground truth (run.sh --field). Only aggregated numbers and the manifest's short labels go into the report.

def fast_levenshtein(a, b):
    """Levenshtein distance by Hyyrö's bit-vector algorithm (Python ints as bit vectors): same result as levenshtein(), fast on whole pages."""
    if len(a) < len(b):
        a, b = b, a
    if not b:
        return len(a)
    peq = {}
    for i, c in enumerate(b):
        peq[c] = peq.get(c, 0) | (1 << i)
    m = len(b)
    mask = (1 << m) - 1
    high = 1 << (m - 1)
    pv, mv, score = mask, 0, m
    for c in a:
        eq = peq.get(c, 0)
        xv = eq | mv
        xh = (((eq & pv) + pv) ^ pv) | eq
        ph = mv | (~(xh | pv) & mask)
        mh = pv & xh
        if ph & high:
            score += 1
        elif mh & high:
            score -= 1
        ph = ((ph << 1) | 1) & mask
        mh = (mh << 1) & mask
        pv = mh | (~(xv | ph) & mask)
        mv = ph & xv
    return score


def agreement(ref, hyp):
    """(CER, WER, unshared words) in % of hyp against ref, or NaN when either side is missing or ref is empty. Unshared words ignores
    order: the share of ref's words missing from hyp (multiset), so reading-order differences do not count."""
    if ref is None or hyp is None:
        return float("nan"), float("nan"), float("nan")
    ref, hyp = normalise(ref), normalise(hyp)
    rw, hw = ref.split(), hyp.split()
    cer = 100.0 * fast_levenshtein(ref, hyp) / len(ref) if ref else float("nan")
    wer = 100.0 * fast_levenshtein(rw, hw) / len(rw) if rw else float("nan")
    bag = 100.0 * sum((Counter(rw) - Counter(hw)).values()) / len(rw) if rw else float("nan")
    return cer, wer, bag


def field_set(set_dir, set_name):
    manifest = {r["page"]: r for r in read_tsv(os.path.join(set_dir, "field.tsv"))}
    detect = {}
    detect_dir = os.path.join(set_dir, "detect")
    if os.path.isdir(detect_dir):
        for n in sorted(os.listdir(detect_dir)):
            if n.endswith(".detect.tsv"):
                for r in read_tsv(os.path.join(detect_dir, n)):
                    detect[r["page"]] = r
    configs = sorted((d.split("-")[0], int(d.split("-")[1])) for d in os.listdir(set_dir)
                     if re.fullmatch(r"(fast|best)-\d+", d) and os.path.isdir(os.path.join(set_dir, d)))
    runs, fails = {}, []
    for model, dpi in configs:
        d = os.path.join(set_dir, f"{model}-{dpi}")
        for n in sorted(os.listdir(d)):
            if n.endswith(".runs.tsv"):
                for r in read_tsv(os.path.join(d, n)):
                    runs[(r["page"], model, dpi, r["lang"], r["variant"])] = r
            elif n.endswith(".failures.tsv"):
                fails += read_tsv(os.path.join(d, n))
    pages = sorted({k[0] for k in runs} | set(detect) | {f["page"] for f in fails})

    def primary(page):
        return manifest.get(page, {}).get("langs", "ita").split(",")[0].strip()

    def stem(page, model, dpi, lang, variant):
        name = f"{page}.{lang}" + ("" if variant == "upright" else f".{variant}")
        return os.path.join(set_dir, f"{model}-{dpi}", name)

    def text(page, model, dpi, lang, variant="upright"):
        p = stem(page, model, dpi, lang, variant) + ".txt"
        return read_text(p) if os.path.isfile(p) else None

    def words(page, model, dpi, lang, variant="upright"):
        p = stem(page, model, dpi, lang, variant) + ".words.tsv"
        return read_words(p) if os.path.isfile(p) else []

    def mean_conf(ws):
        return sum(c for c, _ in ws) / len(ws) if ws else float("nan")

    def label(page):
        m = manifest.get(page, {})
        return m.get("label", "") or "-"

    out = [f"## Set `{set_name}`", "",
           "Labels come from the manifest (`field.tsv`). The physical long edge of each OCR input sets the target DPI; it is an estimate "
           "(the books were not measured), so DPI values are approximate.", ""]

    out += ["### Page detection and orientation", "",
            "Detection = the scanner's one-shot detection on the full-resolution photo; quad % = its share of the photo area; covers = what the "
            "quad actually covers, judged from the preview. OCR input = what was recognised: `quad` (the warped detection) or `frame` (the "
            "whole photo, where the quad was the wrong region), with the assumed physical long edge of that input. OSD = Tesseract orientation "
            "detection on the grey page at 300 DPI: the page is turned clockwise by `rotate` before recognition; confidence above about 15 is "
            "reasonably sure (Tesseract's guide); 400 DPI shown when it differs.", "",
            "| Photo | Label | Detection | Quad % | Quad px | Covers | OCR input (long edge mm) | Source DPI | Page px @300 | Page px @400 | "
            "OSD rotate (conf, script) |",
            "|---|---|---|---|---|---|---|---|---|---|---|"]
    for page in pages:
        det = detect.get(page, {})
        ref = {dpi: runs.get((page, "fast", dpi, primary(page), "upright")) or runs.get((page, "best", dpi, primary(page), "upright"))
               for dpi in (300, 400)}
        any_run = ref[300] or ref[400] or {}
        fail = next((f["reason"] for f in fails if f["page"] == page), "")
        outcome = det.get("outcome") or ("found" if any_run else fail or "-")
        osd = []
        for dpi in (300, 400):
            r = ref[dpi]
            if r is None:
                continue
            o = f"{r['rotate']}° ({float(r['osd_conf']):.1f}, {r['script']})" if r["osd_deg"] else f"failed: {r['osd_error']}"
            if not osd or o != osd[0][1]:
                osd.append((dpi, o))
        osd_txt = "; ".join(o if i == 0 else f"@{dpi}: {o}" for i, (dpi, o) in enumerate(osd)) or "-"
        page_px = {dpi: f"{ref[dpi]['width']}x{ref[dpi]['height']}" if ref[dpi] else "-" for dpi in (300, 400)}
        covers = (manifest.get(page, {}).get("covers") or "-").removeprefix("quad: ")
        ocr_input = f"{any_run['input']} ({float(any_run['long_mm']):.0f})" if any_run else "-"
        out.append(f"| {page} | {label(page)} | {outcome} | {det.get('quad_area_pct') or '-'} | {det.get('natural') or '-'} | {covers} | "
                   f"{ocr_input} | {any_run.get('source_dpi', '-')} | {page_px[300]} | {page_px[400]} | {osd_txt} |")
    out.append("")
    if fails:
        out += failures(fails, "Photos that could not be prepared (skipped)")

    out += ["### Per photo and configuration", "",
            "Primary language, upright page. Prep = decode + detect + warp + grey; OSD = orientation detection alone; recognise = "
            "`setImage` + `getUTF8Text`. Native +MB is the native heap growth over the post-init baseline, PSS the whole test process. Mean word "
            "conf is the mean of the word confidences in words.tsv; Tess conf is Tesseract's own page mean.", "",
            "| Photo | Lang | Model | DPI | Prep s | OSD ms | Recognise s | Native +MB | PSS peak MB | Words | Mean word conf | Tess conf |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    by_config = defaultdict(lambda: {"ms": 0, "n": 0, "native": 0, "pss": 0, "words": 0, "conf": 0.0})
    for page in pages:
        for model, dpi in configs:
            r = runs.get((page, model, dpi, primary(page), "upright"))
            if r is None:
                continue
            ws = words(page, model, dpi, primary(page))
            native = int(r["native_peak_kb"]) - int(r["native_base_kb"])
            out.append(f"| {page} | {r['lang']} | {model} | {dpi} | {int(r['prep_ms']) / 1000:.1f} | {r['osd_ms']} | "
                       f"{int(r['recognise_ms']) / 1000:.1f} | {mb(native)} | {mb(r['pss_peak_kb'])} | {r['words']} | {fmt(mean_conf(ws), 1)} | "
                       f"{r['mean_conf']} |")
            for group in ("all", page.split("-")[0]):
                g = by_config[(group, model, dpi)]
                g["ms"] += int(r["recognise_ms"])
                g["n"] += 1
                g["native"] = max(g["native"], native)
                g["pss"] = max(g["pss"], int(r["pss_peak_kb"]))
                g["words"] += len(ws)
                g["conf"] += sum(c for c, _ in ws)

    out += ["", "### By configuration", "",
            "Group `book`/`hand` by the photo name's prefix. Mean word conf is over all words of the group.", "",
            "| Group | Model | DPI | Photos | Mean recognise s/page | Max native +MB | Max PSS MB | Words | Mean word conf |",
            "|---|---|---|---|---|---|---|---|---|"]
    for (group, model, dpi), g in sorted(by_config.items(), key=lambda kv: (kv[0][0] != "all", kv[0])):
        out.append(f"| {group} | {model} | {dpi} | {g['n']} | {g['ms'] / g['n'] / 1000:.1f} | {mb(g['native'])} | {mb(g['pss'])} | {g['words']} | "
                   f"{fmt(g['conf'] / g['words'] if g['words'] else float('nan'), 1)} |")

    out += ["", "### Low-confidence word filter", "",
            "How many words a filter at each threshold would drop (word confidence below it), primary language, upright page; in brackets the "
            "share of the page's words. Without ground truth it is not known whether the dropped words were right.", "",
            "| Photo | Model | DPI | Words | " + " | ".join(f"<{t}" for t in THRESHOLDS[1:]) + " |",
            "|---|---|---|---|" + "---|" * (len(THRESHOLDS) - 1)]
    for page in pages:
        for model, dpi in configs:
            ws = words(page, model, dpi, primary(page))
            if not ws:
                continue
            cells = [f"{sum(1 for c, _ in ws if c < t)} ({100 * sum(1 for c, _ in ws if c < t) / len(ws):.0f}%)" for t in THRESHOLDS[1:]]
            out.append(f"| {page} | {model} | {dpi} | {len(ws)} | " + " | ".join(cells) + " |")

    out += ["", "### Agreement between configurations", "",
            "A proxy for accuracy without ground truth: CER / WER / unshared words (%) of one output against another of the same photo, "
            "primary language. Fast vs best takes best as the reference; 300 vs 400 takes 400. Unshared words = the reference's words "
            "missing from the other output regardless of order, so it separates recognition from reading order. Low disagreement does not "
            "prove the text right (both can make the same mistake), high disagreement shows at least one of them is wrong.", "",
            "| Photo | Fast vs best @300 | Fast vs best @400 | Fast 300 vs 400 | Best 300 vs 400 |",
            "|---|---|---|---|---|"]
    totals = defaultdict(lambda: [None, 0])
    for page in pages:
        lang = primary(page)
        pairs = [(("best", 300), ("fast", 300)), (("best", 400), ("fast", 400)), (("fast", 400), ("fast", 300)), (("best", 400), ("best", 300))]
        cells = []
        for i, (a, b) in enumerate(pairs):
            ta, tb = text(page, *a, lang), text(page, *b, lang)
            scores = agreement(ta, tb)
            cells.append(" / ".join(fmt(v, 1) for v in scores))
            if scores[0] == scores[0]:
                t = totals[("hand" if page.startswith("hand") else "book", i)]
                t[0] = [a + b for a, b in zip(t[0], scores)] if t[1] else list(scores)
                t[1] += 1
        out.append(f"| {page} | " + " | ".join(cells) + " |")
    for g in ("book", "hand"):
        if any(totals[(g, i)][1] for i in range(4)):
            out.append(f"| mean, {g} | " + " | ".join(" / ".join(fmt(v / totals[(g, i)][1], 1) for v in totals[(g, i)][0])
                                                     if totals[(g, i)][1] else "-" for i in range(4)) + " |")

    rotated = [p for p in pages if any(k[0] == p and k[4] == "norot" for k in runs)]
    if rotated:
        out += ["", "### Rotation: with and without the OSD turn", "",
                "Pages where OSD asked for a turn, recognised both turned (upright) and as warped (no rotation). CER and unshared words "
                "(as above) of the unrotated output against the upright one.", "",
                "| Photo | Model | DPI | Rotate | Upright words / mean conf | Unrotated words / mean conf | CER / unshared unrotated vs upright | "
                "Unrotated recognise s |",
                "|---|---|---|---|---|---|---|---|"]
        for page in rotated:
            lang = primary(page)
            for model, dpi in configs:
                r = runs.get((page, model, dpi, lang, "norot"))
                u = runs.get((page, model, dpi, lang, "upright"))
                if r is None or u is None:
                    continue
                wu, wn = words(page, model, dpi, lang), words(page, model, dpi, lang, "norot")
                cer, _, bag = agreement(text(page, model, dpi, lang), text(page, model, dpi, lang, "norot"))
                out.append(f"| {page} | {model} | {dpi} | {u['rotate']}° | {len(wu)} / {fmt(mean_conf(wu), 1)} | {len(wn)} / "
                           f"{fmt(mean_conf(wn), 1)} | {fmt(cer, 1)} / {fmt(bag, 1)} | {int(r['recognise_ms']) / 1000:.1f} |")

    extra = sorted({(k[0], k[3]) for k in runs if k[3] != primary(k[0])})
    if extra:
        out += ["", "### Language: primary vs a second language setting", "",
                "The same upright page recognised with another language setting; CER of it against the primary-language output.", "",
                "| Photo | Lang | Model | DPI | Primary words / mean conf | Other words / mean conf | CER other vs primary | Recognise s primary / other |",
                "|---|---|---|---|---|---|---|---|"]
        for page, lang in extra:
            for model, dpi in configs:
                r = runs.get((page, model, dpi, lang, "upright"))
                p = runs.get((page, model, dpi, primary(page), "upright"))
                if r is None or p is None:
                    continue
                wp, wo = words(page, model, dpi, primary(page)), words(page, model, dpi, lang)
                cer, _, _ = agreement(text(page, model, dpi, primary(page)), text(page, model, dpi, lang))
                out.append(f"| {page} | {primary(page)} vs {lang} | {model} | {dpi} | {len(wp)} / {fmt(mean_conf(wp), 1)} | {len(wo)} / "
                           f"{fmt(mean_conf(wo), 1)} | {fmt(cer, 1)} | {int(p['recognise_ms']) / 1000:.1f} / {int(r['recognise_ms']) / 1000:.1f} |")
    return out + [""]


def field_report(field_dir, out_path):
    out = ["# OCR benchmark: field photos", "",
           "Generated by `docs/ocr-bench/score.py --field`; method in `docs/ocr-bench/README.md` (\"Field mode\"). Real photos without ground "
           "truth: the numbers are detection, orientation, cost, confidence and agreement between configurations, not accuracy. The photos and "
           "their recognised text stay out of git (`docs/ocr-bench/field-out/`, git-ignored).", ""]
    for set_name in sorted(os.listdir(field_dir)):
        set_dir = os.path.join(field_dir, set_name)
        if os.path.isdir(set_dir):
            out += field_set(set_dir, set_name)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    print(f"wrote {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default=os.path.join(HERE, "results"))
    ap.add_argument("--truth", default=os.path.join(HERE, "corpus", "truth"))
    ap.add_argument("--out", default=os.path.join(HERE, "results.md"))
    ap.add_argument("--check-images", metavar="DIR")
    ap.add_argument("--field", metavar="DIR", help="score field-mode output (run.sh --field) instead; --out defaults to field-results.md")
    args = ap.parse_args()
    if args.check_images:
        sys.exit(0 if check_images(args.check_images, args.truth) else 1)
    if args.field:
        out = args.out if args.out != ap.get_default("out") else os.path.join(HERE, "field-results.md")
        field_report(args.field, out)
        return

    truth = {os.path.splitext(n)[0]: read_text(os.path.join(args.truth, n)) for n in os.listdir(args.truth) if n.endswith(".txt")}
    out = ["# OCR benchmark results", "",
           "Generated by `docs/ocr-bench/score.py`; method and device in `docs/ocr-bench/README.md`. CER/WER in %, lower is better.", "",
           "Page segmentation is set explicitly: PSM 3 (fully automatic) for the main runs. Tesseract's own default when nothing is set is "
           "PSM 6 (single block).", "",
           "**Reading order fails on `e-layout`** (two columns 10 mm apart, then a table): no mode reads it in the expected order. PSM 3 and 1 "
           "keep the two columns apart but read the table column by column; PSM 4 and 6 read the table row by row but merge the two columns "
           "line by line. Its CER/WER therefore measure reading order, not recognition; \"Missed words\" ignores order. The page segmentation "
           "table at the end of each set compares the modes on that page.", ""]
    for set_name in sorted(os.listdir(args.results)):
        set_dir = os.path.join(args.results, set_name)
        if os.path.isdir(set_dir):
            out += score_set(set_dir, set_name, truth)

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out))
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
