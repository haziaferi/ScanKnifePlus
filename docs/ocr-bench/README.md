# OCR benchmark

A harness, not a feature: it measures Tesseract4Android on a phone so the OCR design in `docs/research/ocr-resolution.md` can be decided from data. It answers, per language, tessdata_fast vs tessdata_best at 300 and 400 DPI of the page: recognition time per page, peak memory, character and word error rate against exact ground truth, and how the error changes when low-confidence words are dropped. Real pages are measured the way the planned pipeline would read them: from the full-resolution camera photo, detected and warped by the scanner's own code straight to the target DPI in 8-bit grey, never from the 2400 px stored page. No OCR ships to users: the library is on the androidTest classpath only.

Results: [results.md](results.md).

## Pieces

| File | What it does |
|---|---|
| `make_corpus.py` | Generates the corpus below (Pillow; Liberation and DejaVu fonts from the system). |
| `corpus/corpus.pdf` | **The printable test sheet**: 5 A4 pages, 600 DPI raster. Print at 100% / "actual size", not "fit to page". |
| `corpus/truth/<page>.txt` | Exact text of each page, in the expected reading order. |
| `corpus/clean/<page>.<dpi>dpi.png` | Clean 8-bit grey renders at 300 and 400 DPI (the baseline set `clean`). |
| `corpus/pages.tsv` | Page id, Tesseract language, paper size. Pushed to the phone with the images. |
| `fetch_tessdata.py` | Downloads the language data from pinned commits and checks every SHA-256 (below). |
| `app/src/androidTest/.../ocr/OcrBenchmarkTest.kt` | The instrumented benchmark (skipped when no images were pushed). |
| `run.sh` | Fetches the data, pushes image sets, runs each test method through the shared `device-test.sh`, collects and scores. |
| `score.py` | CER/WER, the threshold sweep and the page segmentation table; writes `results.md`. Standard library only. |
| `results/` | Raw output of the last run: per set and configuration the recognised text, words.tsv, runs.tsv and failures.tsv. |
| `field-results.md` | Field mode results (real photos without ground truth); the raw output stays in the git-ignored `field-out/`. |
| `images-out/` | Images mode output (git-ignored): pre-processed images recognised as they are, see "Images mode". |
| `questions.py` | Prepares the image sets for the open OCR questions (resolution, model, layout, confidence filter, rotation, crop, handwriting, input image) and scores them into `questions-results.md`; the answers are in [questions.md](questions.md). |

## Corpus

| Page | Language run | Content |
|---|---|---|
| `a-english` | eng | English body text, 11 pt Liberation Serif, 14 pt title |
| `b-italian` | ita | Italian body text, 11 pt Liberation Serif; all-caps heading with À È É Ì Ò Ù, and the lowercase accents in the text |
| `c-footnotes` | eng+ita | Footnote-size 8 pt Liberation Sans, four English then four Italian notes |
| `d-receipt` | ita | Receipt-like 80 mm column, 9 pt DejaVu Sans Mono, prices with decimal commas, a date and time, € |
| `e-layout` | eng | 16 pt bold heading, subtitle, two 75 mm columns with headings, then a 4-column table |

Layout is computed in points, so line breaks are identical at every DPI and in the PDF. Expected reading order is top to bottom on every page except `e-layout`, where it is: heading, subtitle, the whole left column (heading "Visitors" and its two paragraphs), the whole right column ("Collections"), the table caption, then the table row by row, left to right. Receipt rows put the price at the right edge; the truth joins them with one space (whitespace is collapsed before scoring anyway).

Fonts used (SHA-256 of the files the committed corpus was made with, Windows 11 copies; Pillow 12.2.0): LiberationSerif-Regular `058ea808…fb74`, LiberationSans-Regular `76d04c18…e8ee8`, LiberationSans-Bold `788abee4…9173`, DejaVuSansMono `b4a6c3e4…c582`. Regenerating with other font versions can move line breaks; the committed truth and renders stay the reference.

## Language data

Not in git. `fetch_tessdata.py` (run by `run.sh`) downloads the raw files of the official repositories at the commits below into the git-ignored `app/src/androidTest/assets/tessdata/{fast,best}/`, from where the build packs them into the test APK only; a file whose SHA-256 differs is deleted and the script fails. The test copies them to `files/tess/<model>/tessdata/` on the phone, the layout `TessBaseAPI.init` expects, and stops with a pointer to the script if they are missing.

| Model | Commit | File | Bytes | SHA-256 |
|---|---|---|---|---|
| fast | tesseract-ocr/tessdata_fast `87416418657359cb625c412a48b6e1d6d41c29bd` | eng.traineddata | 4,113,088 | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| fast | same | ita.traineddata | 2,701,314 | `b8f89e1e785118dac4d51ae042c029a64edb5c3ee42ef73027a6d412748d8827` |
| best | tesseract-ocr/tessdata_best `e12c65a915945e4c28e237a9b52bc4a8f39a0cec` | eng.traineddata | 15,400,601 | `8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba` |
| best | same | ita.traineddata | 8,863,635 | `8df9c89176fb93f56bf4b2d4ede04c01c1f31d4b7697fbd76cc336df700f3f38` |
| both | either commit (identical file) | osd.traineddata | 10,562,727 | `9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff` |

`osd` is only used by page segmentation mode 1 (orientation and script detection).

## Library

`com.github.haziaferi.Tesseract4Android:tesseract4android-openmp:4.11.0` from JitPack (Tesseract 5.5.3, Leptonica 1.87.0). The fork's README says the OpenMP variant is faster on multi-core processors when one Tesseract instance is used, which is the app's case; the standard variant is meant for parallel instances. JitPack is limited to that group in `settings.gradle.kts`.

## Method

Test methods `fast300`, `fast400`, `best300`, `best400` take every image in `ocr-bench/in/<set>/` on the phone; `layoutPsm` takes only `e-layout`. `run.sh` runs each method as its own instrumentation: one run of all of them took over nine minutes and OxygenOS's background-drain detector (`BgDetect`) killed the process.

1. Prepare the page as 8-bit grey (BT.601 luma) at the target DPI of its paper size (A4 2480x3508 at 300, 3307x4677 at 400):
   - `<page>.<dpi>dpi.png` (clean renders): used as is, only for that DPI.
   - `<page>.jpg` / `.png` (already cropped to the sheet): scaled bilinearly to the page size, long edge to long edge.
   - `<page>.photo.jpg` (raw camera photo): decoded upright at full resolution (`ImageCodec.decodeScaled`, EXIF applied), flattened onto white, page found by `DocumentDetector.detectFromRgba` (the scanner's one-shot detection), warped by `PerspectiveCrop.warpToPage` capped at the target's long edge, then resampled bilinearly to the exact page size. If no page is found, or the decode or the warp fails, the page is written to failures.tsv with the reason and skipped. The page's resolution before scaling is recorded as "source DPI"; below the target it means the photo had less detail than the target and was upsampled.
2. New `TessBaseAPI`, `init(lang, OEM_LSTM_ONLY)` (timed separately), `user_defined_dpi` = target DPI, page segmentation set explicitly: PSM 3 (automatic) in the main runs, 3, 1, 4 and 6 in `layoutPsm`. Without the call Tesseract uses PSM 6 (single block), which is what the first runs of this harness measured by accident.
3. Baseline after `System.gc()`, then `setImage(byte[], w, h, 1, w)` + `getUTF8Text()` timed as "recognise". A sampler thread reads the native heap every 10 ms and PSS every 250 ms; VmHWM is reset through `/proc/self/clear_refs` before and read after.
4. The `ResultIterator` at word level gives words.tsv: block, paragraph, line, confidence, box, word.
5. Output goes to the additional test output directory, which the Android Gradle plugin pulls into `app/build/outputs/connected_android_test_additional_output/`; `run.sh` merges it into `results/`.

Scoring: NFC, whitespace collapsed to single spaces; CER = character Levenshtein distance / reference characters, WER the same over words, summed over pages; "missed words" = reference words absent from the output regardless of order. The threshold sweep rebuilds each page's text from the words with confidence at or above the threshold. Memory: "native +MB" is growth of the native heap over the post-init baseline (Tesseract's working memory for the page; the traineddata is in the baseline); PSS and VmHWM are for the whole instrumentation process (app + test + model + the page buffers).

## Findings on the clean renders (OnePlus 6T, 2026-10-20)

Three full runs; timings moved by up to about 0.5-1 s per page between them, so smaller differences are noise.
- **Accuracy does not separate the models on clean pages.** Both models read pages a-d almost perfectly (CER 0-0.5%). Real photos are needed to tell fast from best.
- **Page segmentation matters more than the model.** With PSM 6 (the library default, first two runs) the Italian page scored CER 0.06%; with PSM 3 the isolated accented capitals in "À, È, É, Ì, Ò e Ù" lose their accents (CER 0.36-0.54%) and footnote numbers split ("5." read as ". 5"). The comparison was run on the layout page only; whether PSM 3 or 6 is better for single-column text needs the same comparison on all pages.
- **Reading order fails on the two-column page in every mode** (see results.md): PSM 3/1 keep the columns apart but read the table column by column (CER 34%); PSM 4/6 read the table by rows but merge the columns line by line (CER 41%). Missed words stay at 0-1%, so recognition is fine. PSM 1 gives the same text as 3 and costs 0.2-1.3 s more for the orientation detection.
- **tessdata_best costs about 2-3x the time** (4.3-4.6 s vs 1.4-2.3 s per A4 page) and up to 28 MB more native heap growth per page (most on the 8 pt eng+ita page); 400 DPI adds about 20-25 MB over 300 DPI.
- **The confidence filter only removes real words on clean pages**: up to 70 almost nothing is dropped; at 80-90 nearly every dropped word is correct. Thresholds need real photos with junk to be meaningful.

## Running

From the worktree root, phone connected, the app not installed (device-test.sh refuses otherwise):

    bash docs/ocr-bench/run.sh                                  # the clean renders only
    bash docs/ocr-bench/run.sh --camera 6t-daylight             # plus the five newest photos in the phone's Camera folder
    bash docs/ocr-bench/run.sh 6t-cropped=/path/to/folder       # plus a folder of named images

The clean set always runs as the baseline. Each set gets its own section in results.md. To regenerate the corpus: `python docs/ocr-bench/make_corpus.py`.

## Adding real photos

1. Print `corpus/corpus.pdf` on A4 at actual size (no "fit to page"), black on white.
2. Lay each sheet flat on a dark, plain surface (a table or a dark cloth) so all four edges contrast with the background, in even light without a hard shadow across the page.
3. With the 6T's own Camera app in **Photo** mode at full resolution (4:3, 16 MP; not Pro/RAW, Night or Portrait), flash off, phone held **portrait** with the top of the page at the top of the screen, take one photo of each page, the whole sheet in frame with a margin of background on all sides, front-on. Shoot them **in order a, b, c, d, e** (a-english first, e-layout last) and take no other photo in between. Retake a page by shooting all five again.
4. Run `bash docs/ocr-bench/run.sh --camera <name>`, for example `--camera 6t-daylight`. It takes the five newest `.jpg` files in `/sdcard/DCIM/Camera/` (where the Camera app saves), oldest first as a to e, copies them to the git-ignored `docs/ocr-bench/scans/<name>/` as `a-english.photo.jpg` … `e-layout.photo.jpg`, prints which photo became which page, and runs them with the clean baseline. Nothing on the phone is changed.
5. Check the "page not detected" table in results.md: such a page was skipped, and needs a retake with more contrast around the sheet.

Photos from elsewhere work too: name them `<page>.photo.jpg` in one folder and pass `<name>=<folder>`; images already cropped to the sheet go without `.photo`. `python docs/ocr-bench/score.py --check-images <folder>` shows how each name will be read.

## Field mode

Real photos (books, notes) have no ground truth, so field mode measures what can be measured without it. Results: [field-results.md](field-results.md) (aggregated numbers and short labels only).

    bash docs/ocr-bench/run.sh --field <name>=<folder>

The folder holds raw camera photos named `<page>.photo.jpg` (any name before `.photo`; a `book-`/`hand-` prefix groups them in the summary) and an optional `field.tsv`:

| Column | Meaning |
|---|---|
| `page` | the name before `.photo` |
| `langs` | Tesseract languages, comma-separated; the first is the primary (e.g. `ita` or `ita,eng+ita`); default `ita` |
| `input` | `quad` (default): recognise the detected and warped quad; `frame`: the whole upright photo, for photos where detection picks the wrong region (check `fieldDetect`'s previews first) |
| `long_mm` | physical long edge in mm of that input (the page or spread the quad covers, or the whole frame); sets the target DPI; default 297 |
| `covers`, `label` | for the report only: what the quad covers, and a short neutral label (no page content) |

Per photo, `fieldDetect` records the scanner's one-shot detection (outcome, quad, its share of the photo, natural size) and writes previews: the photo with the quad, the warped page, and (from the fast 300 run) the upright grey page. Then `fieldFast300` … `fieldBest400`, per photo:

1. Decode, detect and warp as in the main runs; the page size comes from the quad's own aspect and `long_mm` at the target DPI. With `input` = `frame` the whole upright photo is scaled to `long_mm` at the target DPI instead.
2. **Orientation**: Tesseract OSD (legacy engine, `osd.traineddata`, a fresh instance) on the warped grey page. It is called through `TessBaseAPI.detectOrientationScript()`, which the fork added in 4.11.0. The results below were measured on 4.10.1 with a test-only JNI shim that called the same Tesseract function. The page is turned clockwise by Tesseract's own "Rotate" value (`(360 - orientation) % 360`) before recognition.
3. Recognition in every manifest language with PSM 3, measured as in the main runs (time, native heap growth, PSS, VmHWM, words.tsv). A page OSD turned is also recognised unturned (primary language), to show what the turn is worth.

`run.sh` pushes `FIELD_CHUNK` photos (default 4) per instrumentation and runs each method per chunk, so no run is long enough for BgDetect; `FIELD_METHODS` limits the methods (e.g. `FIELD_METHODS=fieldDetect` to check detection first and then fill in `field.tsv`). Everything lands in the git-ignored `docs/ocr-bench/field-out/<name>/`: `detect/` (detection rows and previews) and `<model>-<dpi>/` with `<page>.<lang>.txt`, `<page>.<lang>.words.tsv`, `<page>.<lang>.norot.txt` for turned pages, and `<page>.runs.tsv`. The photos are pushed from their folder and not copied into the repository.

### Findings on the field photos (OnePlus 6T, 2026-10-22)

Eleven photos: nine pages of two Italian textbooks (single pages, two-page spreads, figures with labels, a large table, four shot sideways) and two sheets of handwritten notes in block capitals. Details in field-results.md.
- **One-shot detection is not usable on open books.** It "found" a quad on every photo, but on books it was the right region only twice (whole page plus the facing page's edge); otherwise one text column, one text block, the gutter strip of the table page, or a degenerate shape across a spread. On the notes it found the whole sheet once and cut off the bottom sixth once. The OCR for 7 of 11 photos therefore ran on the whole frame (`input` = `frame`) as a stand-in for a corrected crop. An OCR flow from book photos needs the crop UI (or a detector that knows about spreads and gutters) before recognition.
- **Orientation**: OSD was right on the sideways prose spreads (270°, confidence 13-18) and the sideways table (90°, only 7-8), but returned 0° with a wrong script (6-11, "Hebrew"/"Arabic") on a sideways spread with figures, and on handwriting gave noise (confidence 0.1-1.3) that turned one sheet upside down at 300 DPI and the other at 400 DPI. PSM 3 reads sideways prose by itself (its vertical-text detection: turned and unturned outputs share 96-99% of their words), so the turn only mattered for the table page, where it is essential (mean word confidence 81-91 turned vs 22-26 unturned). A turn should be applied only above a confidence floor (about 5 would have kept every correct turn and stopped both wrong ones).
- **Fast vs best agree closely on printed pages** (1-5% CER at the same DPI) while **300 vs 400 DPI disagree far more** (10-12% of words differ, ignoring order): the two models make the same mistakes on the same pixels, so the image dominates the errors and fast-vs-best agreement is a weak accuracy proxy. Neither DPI is clearly better without ground truth. tessdata_best costs 2-5x the time (spreads: 24-41 s vs 6-17 s) for no visible gain.
- **Cost**: fast 300 averages 4.7 s per book photo (spreads 6-9 s), plus 0.7-2.6 s for OSD and 2-10 s to decode, detect and warp the 16 MP photo; native heap growth up to 100 MB (best 400 on a spread), PSS up to 421 MB.
- **Confidence is uninformative on print and saturated on handwriting**: book pages average 81-94 and a filter at 50 drops 1-7% of words (15% on the table page); on the notes the mean word confidence is 22-41 and the same filter drops 63-90%. Handwriting is not usable with these models (fast vs best disagree on 75-90% of words); only some large block-capital headings come through.
- eng+ita instead of ita changes about 1% of characters on Italian pages and costs 15-90% more time.

`score.py --field` reports per photo: detection and OSD (rotation, confidence, script); per configuration time, memory, word count and mean word confidence; how many words each confidence threshold 10-90 would drop; agreement between configurations as CER/WER of fast against best at the same DPI and of 300 against 400 for the same model (a proxy for accuracy: disagreement shows errors, agreement does not prove correctness); turned vs unturned outputs; and primary vs a second language. Its edit distance is a bit-vector implementation of the same Levenshtein distance, fast enough for whole spreads.

## Images mode

For images prepared elsewhere, at the resolution they should be read at (the scanner benchmark's filtered and pre-processed pages, docs/scanner-bench/README.md):

    bash docs/ocr-bench/run.sh --images <name>=<folder>

The folder holds the images and an `images.tsv` (`image`, `page`, `variant`, `lang`, `dpi`, `order`). `imagesFast` and `imagesBest` recognise each image exactly as it is, in grey, with PSM 3 and `user_defined_dpi` from the manifest: no detection, scaling or OSD. Besides the text, words.tsv and a runs row they write lines.tsv (confidence and raw text per text line). `run.sh` pushes `IMAGES_CHUNK` images (default 16) per instrumentation into `ocr-bench/images/<name>/`, collects into the git-ignored `images-out/<name>/<model>/`, and `score.py --images` writes `docs/scanner-bench/ocr-results.md`: the blocks of each page stitched in manifest order, scored against the corpus truth when the page is a corpus page, word counts and mean confidence otherwise.

## Open questions

[questions.md](questions.md) answers eight design questions from runs of images mode: `questions.py prepare` writes each set (pages warped with the hand-marked quads, the PDF pages as simulated scans) into a work folder outside git, `run.sh --images` reads it on the phone (`IMAGES_OUT=none`, since `questions.py score` does its own scoring), and `questions.py score` writes the full tables to [questions-results.md](questions-results.md). Two harness additions serve it: an optional `psm` column in images.tsv, and `imagesOsd`, which runs orientation detection on every image turned 0, 90, 180 and 270 degrees.

    python docs/ocr-bench/questions.py prepare --work WORK --photos PHOTOS --labels LABELS --detect DETECT --set q1-clean
    IMAGES_OUT=none bash docs/ocr-bench/run.sh --images q1-clean=WORK/img/q1-clean
    python docs/ocr-bench/questions.py score --work WORK
