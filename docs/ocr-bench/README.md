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

`com.github.haziaferi.Tesseract4Android:tesseract4android-openmp:4.10.1` from JitPack (Tesseract 5.5.3, Leptonica 1.87.0). The fork's README says the OpenMP variant is faster on multi-core processors when one Tesseract instance is used, which is the app's case; the standard variant is meant for parallel instances. JitPack is limited to that group in `settings.gradle.kts`.

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
