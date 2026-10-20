"""Scores OcrBenchmarkTest output against the ground truth and writes results.md.

Usage:
  python score.py [--results DIR] [--truth DIR] [--out FILE]
  python score.py --check-images DIR     list which images in DIR match a ground-truth page (for adding real scans)

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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--results", default=os.path.join(HERE, "results"))
    ap.add_argument("--truth", default=os.path.join(HERE, "corpus", "truth"))
    ap.add_argument("--out", default=os.path.join(HERE, "results.md"))
    ap.add_argument("--check-images", metavar="DIR")
    args = ap.parse_args()
    if args.check_images:
        sys.exit(0 if check_images(args.check_images, args.truth) else 1)

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
