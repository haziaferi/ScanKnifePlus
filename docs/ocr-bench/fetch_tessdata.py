"""Downloads the benchmark's Tesseract language data from pinned commits and verifies every file's SHA-256.

Usage: python fetch_tessdata.py [--dest DIR]

DEST defaults to app/src/androidTest/assets/tessdata/ (git-ignored), from where the files are packed into the test APK only.
Files already present with the right hash are kept, others are downloaded again; a download with the wrong hash is deleted and
the script exits with status 1.
"""

import argparse
import hashlib
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DEST = os.path.join(HERE, "..", "..", "app", "src", "androidTest", "assets", "tessdata")

COMMITS = {
    "fast": ("tesseract-ocr/tessdata_fast", "87416418657359cb625c412a48b6e1d6d41c29bd"),
    "best": ("tesseract-ocr/tessdata_best", "e12c65a915945e4c28e237a9b52bc4a8f39a0cec"),
}
# osd.traineddata is the same file in both repositories; it is only needed for page segmentation with orientation detection (PSM 1).
FILES = {
    ("fast", "eng"): "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    ("fast", "ita"): "b8f89e1e785118dac4d51ae042c029a64edb5c3ee42ef73027a6d412748d8827",
    ("fast", "osd"): "9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff",
    ("best", "eng"): "8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba",
    ("best", "ita"): "8df9c89176fb93f56bf4b2d4ede04c01c1f31d4b7697fbd76cc336df700f3f38",
    ("best", "osd"): "9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dest", default=DEFAULT_DEST)
    dest = os.path.abspath(ap.parse_args().dest)
    failed = False
    for (model, lang), expected in FILES.items():
        repo, commit = COMMITS[model]
        path = os.path.join(dest, model, lang + ".traineddata")
        if os.path.isfile(path) and sha256(path) == expected:
            print(f"ok      {model}/{lang}")
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        url = f"https://raw.githubusercontent.com/{repo}/{commit}/{lang}.traineddata"
        tmp = path + ".part"
        print(f"fetch   {model}/{lang} from {url}")
        urllib.request.urlretrieve(url, tmp)
        actual = sha256(tmp)
        if actual != expected:
            os.remove(tmp)
            print(f"SHA-256 MISMATCH for {model}/{lang}: expected {expected}, got {actual}", file=sys.stderr)
            failed = True
            continue
        os.replace(tmp, path)
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
