# Scanner benchmark: ScanKnife+ against OSS-DocumentScanner

A harness, not a feature: it scores ScanKnife+'s scanner against [OSS-DocumentScanner](https://github.com/ossappscollective/OSS-DocumentScanner) (MIT, Copyright (c) 2022 Martin Guillon) at commit `ff05fc52b601d1cccb4076f3f6c3318f74e72f0c`, using ports of their real algorithms as the reference, on five things: outline detection, the book gutter, colour filters, OCR and auto-capture. Nothing here ships: the Python ports and the Kotlin test double exist only to measure, and no app or scanner-core main code changes.

Results: [results.md](results.md) (PC side) and [ocr-results.md](ocr-results.md) (after the device run).

## Pieces

| File | What it does |
|---|---|
| `oss_port.py` | Python/OpenCV ports of their `DocumentDetector.cpp` (scanPoint, findSquares, detectGutterAndSplit, applyTransforms), `WhitePaperTransform.cpp`, `WhitePaperTransform2.cpp`, `ColorSimplificationTransform.cpp` with `Utils.cpp` getPalette, and the preprocessing of `DocumentOCR.cpp`. MIT header. |
| `oss_ocr_filter.py` | Their OCR result filter (which recognised lines the app keeps). Standard library only, so `docs/ocr-bench/score.py` imports it. MIT header. |
| `bench.py` | The harness: `prepare` (upright PNGs, labels), `oss-detect` (their detection), `score` (all scoring, their filters, OCR images, results.md). |
| `run.sh` | Runs the PC side in order, the JVM half through the shared `gradle-run.sh`. |
| `scanner-core/src/test/.../bench/ScannerBenchTest.kt` | The JVM half: our detection and the hybrid, the labelled warps and our six filters, the auto-capture simulation. Skipped unless `SCANNER_BENCH_DIR` is set, so CI never runs it. |
| `scanner-core/src/test/.../bench/AutoScanHandlerDouble.kt` | Their `AutoScanHandler.kt` timing logic on a virtual clock. MIT header. |
| `docs/ocr-bench/run.sh --images`, `score.py --images`, `OcrBenchmarkTest.imagesFast/imagesBest` | OCR of the prepared images on the phone (step 4). |

Their sources are fetched for reading only (`gh api repos/ossappscollective/OSS-DocumentScanner/contents/<path>?ref=<sha>`) and are not in this repository.

## Photos and labels

The user's photos are never committed or uploaded; every derived image, crop, label and per-photo output stays in the work folder (`WORK`) outside the repository. The photo folder holds `books/` (nine open-book photos `book-01..09` and two handwritten sheets `hand-01..02`, with `field.tsv` from the OCR field mode), `corpus/` (the five printed OCR corpus pages `a-english..e-layout`, upright, ground truth in `docs/ocr-bench/corpus/truth/`) and `shots/camera/` (11 stock-camera shots) plus `shots/glasscam/` (2 GlassCam shots). EXIF orientation is applied everywhere: `prepare` writes upright PNGs for the JVM, and Python reads through `cv2.imread`, which applies it.

The answer key is `labels.json`, marked by hand on the upright photos and never derived from either detector: `{"<set>/<file>": {"quad": [[x, y] x4: TL, TR, BR, BL], "fold": [[x, y], [x, y]] or null, "note": str}}`. `prepare` matches keys to photos by file name.

## Method

**1. Outline detection.** Each photo is detected three ways at full resolution, upright:
- *Ours*: `DocumentDetector.detectFromRgba`, the one-shot path (`OcrBenchmarkTest` uses the same), after `StoredImage.flattenOntoWhite`.
- *Theirs*: their app's one-shot path. `processCameraImage` calls `getJSONDocumentCornersFromFile(imagePath, { resizeThreshold: previewResizeThreshold * 1.5, areaScaleMinFactor })` (app/utils/ui/index.common.ts:1874) with `PREVIEW_RESIZE_THRESHOLD = 200` and `AREA_SCALE_MIN_FACTOR = 0.1` (app/utils/constants.ts:72-73). Android decodes the photo to a long edge of 300 and turns it upright; with `shrunkImageHeight` 0 the detector does not resize again and sets `medianBlurValue = 3` (plugin-nativeprocessor/platforms/android/cpp/document_detector.cpp:194-202); everything else is the `DetectOptions` default in DocumentDetector.h, which leaves the Hough refinement off (`houghLinesThreshold = 0`). The port then follows scanPoint exactly: 10 px black border, median blur, per channel of the RGBA bitmap (B, G, R) a threshold-160 mask and Canny at 120/240 down to 20/40, findSquares on each (contour area 10%-92% of the frame, approxPolyDP at 2% of the arc, convex, 10 px from the border, max |cos| < 0.4), stopping early once the best has |cos| < 0.3 and area > 20%; the result is the best `area + weight * (1 - maxCos)` with weight 3,000,000 minus one per mask, sorted by `sortPoints`.
- *Hybrid*: our detector's candidate pool (all three threshold masks of `detectQuadFromGrayscale`, before our clustering) ranked by their sort factor, with their per-mask weights and the area converted to their 300 px scale. Since the weight term dominates, this picks the most rectangular candidate.

Scores per photo: IoU of the detected quad with the label (>= 0.9 counts as correct, no quad counts as 0) and the largest corner error under the best one-to-one corner matching, as % of the label's longer diagonal. Time is PC time for detection on the decoded photo (decode excluded, their downscale included), Python/OpenCV for theirs and the JVM for ours; it is not device time.

**2. Gutter.** Each spread with a marked fold is warped with its labelled quad (cv2, natural size) and turned so the fold is vertical (detectGutterAndSplit only looks for vertical folds; the app's quarter turns would do the same). Their `detectGutterAndSplit` is called nowhere in their app; it runs as in their desktop test app (cpp/cpp_test_app/scanner.cpp:159, 780-782): long edge 300 with a 10 px black border, `minPageWidthRatio` 0.4. It is compared with our fixed split at 0.5 (`BookSplit.DEFAULT_SPLIT`). Error = |predicted - marked fold| as % of the spread's width, the marked fold being the mean x of the two fold points after the warp.

**3. Colour filters.** Every labelled photo is warped with its label by our `PerspectiveCrop` (corpus pages to A4 at 300 DPI, 2480 x 3508; the rest at natural size capped at 3508 px), so detection plays no part. Our six `DocumentFilters` run on the JVM, their four transforms in Python with the options their app uses: the transform list offers `enhance`, `whitepaper` and `color` with no options (app/utils/localized_constant.ts:3-7), so every value is the C++ default; `whitepaper2` is not offered in the app and is included because it exists. Metrics on CIELAB L* (0-100): paper = pixels above the unfiltered page's Otsu threshold, eroded 5 x 5, ink = the rest, eroded the same, so every filter is judged on the same pixels; uniformity = standard deviation and p5-p95 spread of paper L*; contrast = median paper L* minus median ink L*. Medians over pages. These measure flat paper and dark ink, not legibility: a filter that wipes out faint strokes can still score well, which step 4 checks.

**4. OCR.** `bench.py score` writes three image sets (git-ignored, in `WORK/ocr/`): `sb-corpus` and `sb-books` hold every filter of every corpus page, book photo and handwritten sheet, turned upright and grey; `sb-oss` holds their OCR preprocessing of the unfiltered page: `ossocr-app` is what their app sends (app/services/ocr.ts:264-275 passes `detectContours: 0`, so the whole page goes through adaptive Gaussian threshold 339/47 and inversion, with a 15 px white border), `ossocr-blocks` the header defaults (text blocks from a 70 x 4 close, 40 x 40 dilation and external contours of more than 200 points, cropped, sorted by their `getYSortValue` key, each with a 15 px white border). `images.tsv` lists page, variant, language, DPI (300: their default `dpi`, and the corpus warps' true resolution) and block order. On the phone each image is recognised as it is (`imagesFast`, `imagesBest`): PSM 3 (their `PSM_AUTO` default), LSTM (their `OEM_DEFAULT` on LSTM-only traineddata), `user_defined_dpi` from the manifest; besides the text and words it writes lines.tsv, the text-line level their app filters at (`OCR_ITERATOR_LEVEL = 2`, app/utils/constants.ts:109). `score.py --images` stitches the blocks of a page in manifest order and scores the text against the corpus truth where the page has one (CER, WER, missed words), word counts and mean confidence otherwise; for their pipeline it also scores the text after their line filter (`oss_ocr_filter.py`). Their app defaults to tessdata_best (app/services/ocr.ts:152).

**5. Auto-capture.** Pure JVM, seeded, deterministic. `AutoScanHandlerDouble` ports their handler's logic with its defaults (distance 50 px between consecutive frames' corners, pre-delay 1000 ms, then a 101 x 10 ms progress job, per-quad hashing with `31 * x + y` point hashes and the hash bimap; a quad that stays matched after a capture never re-triggers). Our `AutoCaptureDetector` runs with its real defaults on a virtual `MicrosClock`. Both get the same synthetic sequences at 30 fps for 6 s, 20 runs each (scenarios in results.md): ours in normalised coordinates, theirs in pixels of a 480 x 640 analysis frame (CameraX's default 640 x 480 analysis size; the threshold is in pixels, so 960 x 1280 is shown too), quantised as their 200 px live detection quantises corners.

## Running

From the worktree root, with an interpreter that has OpenCV and numpy:

    PYTHON=/path/to/python bash docs/scanner-bench/run.sh PHOTOS LABELS WORK

`oss-detect` and the filter metrics resume from their JSON in `WORK` (delete it to recompute). The OCR device step, phone connected and the app not installed:

    bash docs/ocr-bench/run.sh --images sb-corpus=WORK/ocr/sb-corpus --images sb-oss=WORK/ocr/sb-oss
    IMAGES_METHODS=imagesFast bash docs/ocr-bench/run.sh --images sb-books=WORK/ocr/sb-books

## Where a port may not be faithful

- OpenCV 5.0.0 (Python) instead of their 4.13: no API change was needed; `findContours`, `approxPolyDP`, `Canny`, `detailEnhance`, `kmeans` and `adaptiveThreshold` behave the same as far as the bench can tell, but bit-exactness was not checked against their native build.
- Their one-shot input is an Android `BitmapFactory` downsample (power-of-two `inSampleSize`, then density scaling); the port uses `INTER_AREA` to the same size. Contour sorting ties may order differently (`std::sort` is not stable).
- `whitepaper`'s `colorBalance` searches a `cv::reduce`d histogram (one row with the pixel count), so both bounds are 0 and it returns the image unchanged; the port reproduces that, as it does `normalizeKernel`'s negative-scale quirk and `csWhitePer` 99.5 truncating to 99.
- `color` runs on the RGBA bitmap as if it were BGR; its HSV distance divides the integer hue difference by 180, so only S and V count. `kmeans` seeds from OpenCV's RNG, seeded here for repeatability.
- The auto-capture double runs its 1010 ms job exactly (the real dispatcher adds slack) and accepts at most one quad per frame (their detector returns one; with two, the original's remove-inside-forEach would throw on a standard ArrayList iterator).
