<h1 align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/design/launcher/wordmark-dark.svg">
    <img src="docs/design/launcher/wordmark-light.svg" alt="ScanKnife+" width="300">
  </picture>
</h1>

A privacy-first PDF utility and document scanner for Android. Works 100% offline - no internet, no trackers.

ScanKnife+ is a personal-use fork of PaperKnife+ with a document scanner ported from OpenScan. See [Credits](#credits) and [NOTICE.md](NOTICE.md).

---

## Features

- Merge & Split PDFs
- Password protect your PDFs
- Convert Images to PDF
- Export PDF pages as Images (ZIP)
- Rotate and rearrange pages
- Scan documents with the camera: automatic edge detection, perspective crop and document filters (ported from OpenScan)
- And more!

All processing happens on your device. Your files never leave your phone.

---

## Download

There are no published releases yet. Every CI run of the [Build workflow](https://github.com/haziaferi/ScanKnifePlus/actions/workflows/build.yml) uploads the release APK and AAB as run artifacts, signed when the repository has a signing key (otherwise unsigned, with an "-unsigned" suffix on the artifact name): open a run and download them from its Artifacts section (you need to be signed in to GitHub). Tagged releases will appear on the [releases page](https://github.com/haziaferi/ScanKnifePlus/releases) once they are published.

---

## Credits

ScanKnife+ builds on two open-source projects. Thanks to their authors and contributors:

- **PaperKnife+** (GPL-3.0-or-later, https://github.com/potatameister/PaperKnifePlus): ScanKnife+ is a fork of it and keeps the app and its PDF tools. Written by potatameister; its contributors are listed on [GitHub](https://github.com/potatameister/PaperKnifePlus/graphs/contributors).
- **OpenScan** (BSD-3-Clause, https://github.com/ethereal-developers/OpenScan): the document scanner (edge detection, perspective crop, filters and live scan logic) is ported from it to Kotlin. Written by Vijay T S (veejayts) and Vikram H (vikram0230) of Ethereal Developers; its contributors are listed on [GitHub](https://github.com/ethereal-developers/OpenScan/graphs/contributors).

ScanKnife+ is an independent fork and is not affiliated with or endorsed by the PaperKnife+ or OpenScan projects or their authors.

[NOTICE.md](NOTICE.md) has the full licence texts and the notices of the other bundled third-party components; the app shows the same text under About > Open Source Libraries.

---

## License

GPL-3.0-or-later
