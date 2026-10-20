"""Generates the OCR ground-truth corpus: a printable A4 PDF, one .txt per page and clean PNG renders.

Usage: python -I make_corpus.py [--out DIR] [--fonts DIR]

Layout is done in points, so line breaks are identical at every DPI; the text of each line is drawn exactly as written to the .txt.
Outputs (default DIR = corpus/ next to this script):
  corpus.pdf                     printable A4, 600 DPI raster, one page per ground-truth page
  truth/<page>.txt               exact text in the reading order Tesseract is expected to use
  clean/<page>.<dpi>dpi.png      8-bit grey renders at 300 and 400 DPI
  pages.tsv                      page id, Tesseract language, paper size (pushed to the device with the images)
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

MM_PER_IN = 25.4
PT_PER_IN = 72.0
A4_MM = (210.0, 297.0)
RENDER_DPIS = (300, 400)
PDF_DPI = 600

FONT_FILES = {
    "serif": "LiberationSerif-Regular.ttf",
    "sans": "LiberationSans-Regular.ttf",
    "sans-bold": "LiberationSans-Bold.ttf",
    "mono": "DejaVuSansMono.ttf",
}
FONT_DIRS = [r"C:\Windows\Fonts", "/usr/share/fonts/truetype/liberation", "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/TTF"]

# Reference size for width measurement: widths are measured once at this pixel size and scaled to points.
MEASURE_PX = 1000


def mm(v):
    return v / MM_PER_IN * PT_PER_IN


class Fonts:
    def __init__(self, font_dir):
        self.paths = {}
        for key, name in FONT_FILES.items():
            dirs = [font_dir] if font_dir else FONT_DIRS
            path = next((os.path.join(d, name) for d in dirs if os.path.isfile(os.path.join(d, name))), None)
            if path is None:
                sys.exit(f"font {name} not found in {dirs}; pass --fonts")
            self.paths[key] = path
        self._measure = {k: ImageFont.truetype(p, MEASURE_PX) for k, p in self.paths.items()}
        self._cache = {}

    def width_pt(self, key, size_pt, text):
        return self._measure[key].getlength(text) / MEASURE_PX * size_pt

    def at(self, key, size_px):
        k = (key, size_px)
        if k not in self._cache:
            self._cache[k] = ImageFont.truetype(self.paths[key], size_px)
        return self._cache[k]


class Page:
    """A page as positioned text runs (x_pt, baseline_pt, font, size_pt, text, align) plus its ground-truth blocks."""

    def __init__(self, page_id, lang, title):
        self.id, self.lang, self.title = page_id, lang, title
        self.runs = []
        self.truth = []

    def add_truth(self, lines):
        self.truth.append("\n".join(lines))


def wrap(fonts, key, size, text, width):
    lines, cur = [], ""
    for word in text.split():
        trial = f"{cur} {word}" if cur else word
        if cur and fonts.width_pt(key, size, trial) > width:
            lines.append(cur)
            cur = word
        else:
            cur = trial
    if cur:
        lines.append(cur)
    return lines


def flow(page, fonts, y, x, width, key, size, paragraphs, leading=1.25, gap=0.6):
    """Lays out paragraphs from baseline y downwards; returns the y below the last one."""
    for para in paragraphs:
        lines = wrap(fonts, key, size, para, width)
        for line in lines:
            y += size * leading
            page.runs.append((x, y, key, size, line, "l"))
        page.add_truth(lines)
        y += size * gap
    return y


def lines_block(page, y, x, key, size, lines, leading=1.3):
    """Places pre-broken lines (headings, receipt rows) without wrapping."""
    for line in lines:
        y += size * leading
        if isinstance(line, tuple):
            left, right, right_x = line
            page.runs.append((x, y, key, size, left, "l"))
            page.runs.append((right_x, y, key, size, right, "r"))
        else:
            page.runs.append((x, y, key, size, line, "l"))
    page.add_truth([f"{l[0]} {l[1]}" if isinstance(l, tuple) else l for l in lines])
    return y


LEFT = mm(25)
TEXT_W = mm(160)
TOP = mm(25)

EN_BODY = [
    "The reading room opens at half past eight, and by nine most of the long oak tables are taken. Students arrive with laptops, "
    "retired engineers with folders of yellowed drawings, and a few visitors who only want to see the painted ceiling. The rules are "
    "simple: no food, no loud calls, and every book that leaves the shelves must be signed out at the front desk.",
    "Behind the desk, two librarians keep a ledger that has been in use since 1952. Each entry records the title, the shelf mark, the "
    "time of loan and the initials of the reader. Over seventy years the handwriting changes from fountain pen to ballpoint, and the "
    "shelf marks change from Roman numerals to a decimal scheme introduced in 1987.",
    "Last spring the library began to scan the ledger. A volunteer photographs each page under a pair of daylight lamps, and a small "
    "program straightens the image, removes the shadow of the binding and stores the result as a searchable document. The hardest pages "
    "are those where ink has faded to a pale brown, or where a later reader added notes in pencil between the lines.",
    "So far 4,316 pages have been processed, about 61 percent of the total. The volunteers estimate that the remaining volumes will take "
    "another eighteen months, provided the lamps survive. Anyone who wishes to help may ask at the desk; training takes about an hour, "
    "and no previous experience is needed.",
    "Questions about the project, the opening hours (Monday to Saturday, 8:30 to 19:00) or access for wheelchair users can be sent to "
    "the address printed on the back of every library card.",
]

IT_HEADING = "CITTÀ, UNIVERSITÀ E LIBERTÀ: PERCHÉ È COSÌ PIÙ FACILE? PERÒ..."
IT_BODY = [
    "La biblioteca civica è aperta dal lunedì al sabato. Chi entra per la prima volta riceve una tessera gratuita, valida per tre anni, "
    "che permette di prendere in prestito fino a sei libri alla volta. È possibile rinnovare il prestito anche per telefono, purché "
    "nessun altro lettore abbia prenotato lo stesso volume.",
    "Il fondo più antico conserva circa duemila manoscritti, perlopiù atti notarili e registri parrocchiali. Molti sono stati restaurati "
    "negli ultimi vent'anni; altri, però, sono ancora troppo fragili per essere consultati. Per questo motivo la città ha avviato un "
    "progetto di digitalizzazione che durerà fino al 2028.",
    "Perché un documento sia leggibile anche dopo la scansione, la pagina deve essere illuminata in modo uniforme e fotografata ben "
    "dritta. Così il programma riesce a riconoscere le lettere accentate: à, è, é, ì, ò e ù, ma anche le maiuscole come À, È, É, Ì, Ò "
    "e Ù, che nei testi più vecchi compaiono spesso nei titoli.",
    "Né il personale né i volontari possono garantire tempi certi: ciò dipende dallo stato di ogni volume. Tuttavia, entro la fine "
    "dell'anno, più di metà del fondo sarà consultabile in rete, affinché chiunque possa studiarlo da casa.",
]

FOOT_EN = [
    "1. The figures quoted above refer to the calendar year 2025 and exclude inter-library loans, which are counted separately by the regional office.",
    "2. Opening hours may change during public holidays; a notice is posted at the entrance at least one week in advance.",
    "3. Readers who lose a card can request a replacement at the desk for a fee of 2.50 euros, payable in cash or by card.",
    "4. Digitised pages are published under an open licence; the original volumes remain the property of the municipality.",
]
FOOT_IT = [
    "5. I dati citati si riferiscono all'anno 2025 e non comprendono i prestiti interbibliotecari, conteggiati a parte dall'ufficio regionale.",
    "6. Gli orari possono variare nei giorni festivi; l'avviso è affisso all'ingresso almeno una settimana prima.",
    "7. Chi smarrisce la tessera può chiederne un duplicato allo sportello, al costo di 2,50 euro, pagabili in contanti o con carta.",
    "8. Le pagine digitalizzate sono pubblicate con licenza aperta; perché siano riutilizzabili, è richiesta la citazione della fonte.",
]

RECEIPT_LEFT = mm(65)
RECEIPT_W = mm(80)
RECEIPT_ROWS = [
    "SUPERMERCATO DA ROSA S.R.L.",
    "VIA GARIBALDI 14 - 40121 BOLOGNA",
    "P.IVA 01234567890",
    "DOCUMENTO COMMERCIALE",
    "di vendita o prestazione",
    ("DESCRIZIONE", "PREZZO(€)"),
    ("PANE INTEGRALE", "2,40"),
    ("LATTE INTERO 1L", "1,29"),
    ("CAFFÈ MOKA 250G", "3,85"),
    ("MOZZARELLA 125G x2", "2,58"),
    ("POMODORI KG 1,140", "2,26"),
    ("OLIO EXTRAVERGINE", "8,90"),
    ("SCONTO FEDELTÀ", "-1,00"),
    ("TOTALE COMPLESSIVO", "20,28"),
    ("DI CUI IVA", "1,94"),
    ("PAGAMENTO CARTA", "20,28"),
    ("RESTO", "0,00"),
    "20-10-2026 18:42",
    "DOC.N. 0187-0042",
    "RT 99MEY012345",
    "GRAZIE E ARRIVEDERCI",
]

TWO_COL_HEADING = "Annual Report on Reading Habits"
TWO_COL_SUB = "Summary for the board, October 2026"
COL_A_HEAD = "Visitors"
COL_A = [
    "Visits rose for the third year in a row. Most of the growth came from weekday afternoons, when school groups use the study rooms.",
    "Saturday mornings remain the busiest time, with queues at the desk before ten o'clock.",
]
COL_B_HEAD = "Collections"
COL_B = [
    "The library bought 1,204 new titles and withdrew 830 damaged ones. Children's books and travel guides were borrowed most often.",
    "The scanning project added 4,316 pages to the online archive.",
]
TABLE_HEAD = "Table 1. Loans by section"
TABLE = [
    ("Section", "2024", "2025", "Change"),
    ("Fiction", "18,240", "19,105", "+4.7%"),
    ("Non-fiction", "9,870", "10,312", "+4.5%"),
    ("Children", "12,455", "13,980", "+12.2%"),
    ("Periodicals", "3,102", "2,744", "-11.5%"),
    ("Total", "43,667", "46,141", "+5.7%"),
]


def build_pages(fonts):
    pages = []

    p = Page("a-english", "eng", "English body text, 11 pt Liberation Serif")
    y = lines_block(p, TOP, LEFT, "serif", 14, ["The Reading Room"])
    flow(p, fonts, y + 4, LEFT, TEXT_W, "serif", 11, EN_BODY)
    pages.append(p)

    p = Page("b-italian", "ita", "Italian body text with accents, 11 pt Liberation Serif")
    y = lines_block(p, TOP, LEFT, "serif", 11, [IT_HEADING])
    flow(p, fonts, y + 4, LEFT, TEXT_W, "serif", 11, IT_BODY)
    pages.append(p)

    p = Page("c-footnotes", "eng+ita", "Footnote-size English and Italian, 8 pt Liberation Sans")
    y = flow(p, fonts, TOP, LEFT, TEXT_W, "sans", 8, FOOT_EN, leading=1.2, gap=0.4)
    flow(p, fonts, y + 6, LEFT, TEXT_W, "sans", 8, FOOT_IT, leading=1.2, gap=0.4)
    pages.append(p)

    p = Page("d-receipt", "ita", "Receipt-like narrow column, 9 pt DejaVu Sans Mono")
    lines_block(p, TOP, RECEIPT_LEFT, "mono", 9, [r if isinstance(r, str) else (r[0], r[1], RECEIPT_LEFT + RECEIPT_W) for r in RECEIPT_ROWS])
    pages.append(p)

    p = Page("e-layout", "eng", "Headings, two columns and a table, Liberation Sans")
    y = lines_block(p, TOP, LEFT, "sans-bold", 16, [TWO_COL_HEADING])
    y = lines_block(p, y, LEFT, "sans", 11, [TWO_COL_SUB])
    col_w, col_gap = mm(75), mm(10)
    top = y + 8
    ya = lines_block(p, top, LEFT, "sans-bold", 12, [COL_A_HEAD])
    ya = flow(p, fonts, ya, LEFT, col_w, "sans", 10, COL_A)
    xb = LEFT + col_w + col_gap
    yb = lines_block(p, top, xb, "sans-bold", 12, [COL_B_HEAD])
    yb = flow(p, fonts, yb, xb, col_w, "sans", 10, COL_B)
    y = lines_block(p, max(ya, yb) + 10, LEFT, "sans-bold", 11, [TABLE_HEAD])
    col_x = [LEFT, LEFT + mm(55), LEFT + mm(85), LEFT + mm(115)]
    rows = []
    for i, row in enumerate(TABLE):
        y += 10 * 1.4
        key = "sans-bold" if i == 0 else "sans"
        for cx, cell in zip(col_x, row):
            p.runs.append((cx, y, key, 10, cell, "l"))
        rows.append(" ".join(row))
    p.add_truth(rows)
    pages.append(p)
    return pages


def render(page, fonts, dpi, mode="L"):
    w, h = round(A4_MM[0] / MM_PER_IN * dpi), round(A4_MM[1] / MM_PER_IN * dpi)
    img = Image.new("L", (w, h), 255)
    draw = ImageDraw.Draw(img)
    k = dpi / PT_PER_IN
    for x, y, key, size, text, align in page.runs:
        draw.text((x * k, y * k), text, font=fonts.at(key, size * k), fill=0, anchor="ls" if align == "l" else "rs")
    return img if mode == "L" else img.convert(mode)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "corpus"))
    ap.add_argument("--fonts", default=None, help="directory holding the Liberation and DejaVu TTFs")
    args = ap.parse_args()
    fonts = Fonts(args.fonts)
    pages = build_pages(fonts)
    for sub in ("truth", "clean"):
        os.makedirs(os.path.join(args.out, sub), exist_ok=True)

    with open(os.path.join(args.out, "pages.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("page\tlang\tpaper\tdescription\n")
        for p in pages:
            f.write(f"{p.id}\t{p.lang}\ta4\t{p.title}\n")
    for p in pages:
        with open(os.path.join(args.out, "truth", p.id + ".txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n\n".join(p.truth) + "\n")
        for dpi in RENDER_DPIS:
            render(p, fonts, dpi).save(os.path.join(args.out, "clean", f"{p.id}.{dpi}dpi.png"), dpi=(dpi, dpi), optimize=True)

    sheets = [render(p, fonts, PDF_DPI, mode="1") for p in pages]
    sheets[0].save(os.path.join(args.out, "corpus.pdf"), save_all=True, append_images=sheets[1:], resolution=PDF_DPI,
                   title="ScanKnife+ OCR benchmark corpus")
    print(f"wrote {len(pages)} pages to {args.out}")


if __name__ == "__main__":
    main()
