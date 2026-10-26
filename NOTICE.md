# Notices and credits

ScanKnife+ is a personal-use fork that combines two open-source projects. Both original projects and their authors are credited here.

## PaperKnife+

ScanKnife+ is a fork of [PaperKnife+](https://github.com/potatameister/PaperKnifePlus) by potatameister, licensed under GPL-3.0-or-later (see `LICENSE`). ScanKnife+ as a whole is distributed under the same license.

## OpenScan

The document scanner (edge detection, perspective crop, filters and live scan logic) is ported from [OpenScan](https://github.com/ethereal-developers/OpenScan) by Vijay T S and Vikram H, used under the BSD 3-Clause License reproduced below.

To give results identical to OpenScan, the port also reproduces Dart's `List.sort` algorithm, ported from the [Dart SDK](https://github.com/dart-lang/sdk) (`sdk/lib/internal/sort.dart`, Copyright (c) 2011, the Dart project authors, BSD 3-Clause License, same terms as below).

The perspective crop also reproduces two operations OpenScan calls from the Dart [image](https://pub.dev/packages/image) package 4.2.0 (box-average resize and quarter-turn rotation), used under the MIT License reproduced at the end of this file.

### OpenScan license

BSD 3-Clause License

Copyright (c) 2021, Vijay T S and Vikram H
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## image package license

The MIT License

Copyright (c) 2013-2022 Brendan Duncan. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## OSS-DocumentScanner

The document detector's first candidate search (per-channel brightness and Canny edge masks on a bordered working image, the square search and its ranking) is ported from [OSS-DocumentScanner](https://github.com/ossappscollective/OSS-DocumentScanner) by Martin Guillon, commit `ff05fc52b601d1cccb4076f3f6c3318f74e72f0c` (`cpp/src/DocumentDetector.cpp`), used under the MIT License reproduced below. The scanner benchmark in `docs/scanner-bench` also ports parts of it for comparison.

### OSS-DocumentScanner license

The MIT License (MIT)

Copyright (c) 2022 Martin Guillon

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Bundled third-party components

These libraries ship inside the ScanKnife+ APK.

### pdfbox-android

[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) 2.0.27.0 by Tom Roush, licensed under the Apache License, Version 2.0, is a port of [Apache PDFBox](https://pdfbox.apache.org/) 2.0.27 (also Apache License 2.0), whose NOTICE is reproduced below. PDFBox's own licence lists external components; the parts that cover code and resources in pdfbox-android (the original pdfbox.org BSD licence, the Adobe Core 14 AFM permission notice and the Adobe CMaps BSD licence) are reproduced verbatim in [NOTICES/PDFBox-2.0.27-external-components.txt](NOTICES/PDFBox-2.0.27-external-components.txt). Its library carries these third-party resources:

- **Liberation Sans** 2.1.5 (`LiberationSans-Regular.ttf`): "Digitized data copyright (c) 2010 Google Corporation. Copyright (c) 2012 Red Hat, Inc." Liberation is a trademark of Red Hat, Inc. registered in U.S. Patent and Trademark Office and certain other jurisdictions. Licensed under the SIL Open Font License, Version 1.1, reproduced verbatim in [NOTICES/LiberationSans-OFL-1.1.txt](NOTICES/LiberationSans-OFL-1.1.txt) (source: https://github.com/liberationfonts/liberation-fonts/blob/2.1.5/LICENSE).
- **CMaps for PDF fonts** (92 Adobe CMap files): Copyright 1990-2009 Adobe Systems Incorporated, under the BSD licence in the PDFBox external-components file linked above.
- **Adobe Core 14 font metrics** (the 14 standard `.afm` files), under the AFM permission notice in the PDFBox external-components file linked above, carrying these notices:
  - Copyright (c) 1985, 1987, 1988, 1989, 1997 Adobe Systems Incorporated. All Rights Reserved. ITC Zapf Dingbats is a registered trademark of International Typeface Corporation.
  - Copyright (c) 1985, 1987, 1989, 1990, 1993, 1997 Adobe Systems Incorporated. All Rights Reserved. Times is a trademark of Linotype-Hell AG and/or its subsidiaries.
  - Copyright (c) 1985, 1987, 1989, 1990, 1997 Adobe Systems Incorporated. All Rights Reserved. Helvetica is a trademark of Linotype-Hell AG and/or its subsidiaries.
  - Copyright (c) 1985, 1987, 1989, 1990, 1997 Adobe Systems Incorporated. All rights reserved.
  - Copyright (c) 1989, 1990, 1991, 1992, 1993, 1997 Adobe Systems Incorporated. All Rights Reserved.
  - Copyright (c) 1989, 1990, 1991, 1993, 1997 Adobe Systems Incorporated. All Rights Reserved.
- **Adobe Glyph List** (`glyphlist.txt`, Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated) and the ITC Zapf Dingbats glyph list (`zapfdingbats.txt`, Copyright 2002, 2010 Adobe Systems Incorporated), both under the BSD 3-Clause terms below.
- **Unicode Character Database** data files `Scripts.txt` (Scripts-10.0.0, © 2017 Unicode, Inc.) and `BidiMirroring.txt` (BidiMirroring-8.0.0, Copyright (c) 1991-2015 Unicode, Inc.), used under the Unicode terms of use (http://www.unicode.org/terms_of_use.html). Unicode and the Unicode Logo are registered trademarks of Unicode, Inc. in the U.S. and other countries.

#### Apache PDFBox 2.0.27 NOTICE

Source: https://github.com/apache/pdfbox/blob/2.0.27/NOTICE.txt

```
Apache PDFBox
Copyright 2014 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).

Based on source code originally developed in the PDFBox and
FontBox projects.

Copyright (c) 2002-2007, www.pdfbox.org

Based on source code originally developed in the PaDaF project.
Copyright (c) 2010 Atos Worldline SAS

Includes the Adobe Glyph List
Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated.

Includes the Zapf Dingbats Glyph List
Copyright 2002, 2010 Adobe Systems Incorporated.

Includes OSXAdapter
Copyright (C) 2003-2007 Apple, Inc., All Rights Reserved
```

#### Unicode license

Source: https://www.unicode.org/license.txt

```
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 1991-2026 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.
```

#### Adobe Glyph List license

Copyright 1997, 1998, 2002, 2007, 2010 Adobe Systems Incorporated. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

Neither the name of Adobe Systems Incorporated nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### Bouncy Castle

pdfbox-android depends on Bouncy Castle 1.72 (`bcprov-jdk15to18`, `bcpkix-jdk15to18`, `bcutil-jdk15to18`), used under the Bouncy Castle Licence:

Copyright (c) 2000-2022 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Tesseract4Android

[Tesseract4Android](https://github.com/haziaferi/Tesseract4Android) 4.11.0 (`tesseract4android-openmp`), the app owner's fork of [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) by Adaptech s.r.o. (Copyright 2019 Adaptech s.r.o., Robert Pösel), whose Java and JNI wrappers are based on [tess-two](https://github.com/rmtheis/tess-two) and [Tesseract Tools for Android](https://github.com/alanv/tesseract-android-tools). Licensed under the Apache License, Version 2.0, reproduced in [NOTICES/Apache-License-2.0.txt](NOTICES/Apache-License-2.0.txt). It bundles the native libraries below.

### Tesseract OCR

[Tesseract](https://github.com/tesseract-ocr/tesseract) 5.5.3, originally developed at Hewlett-Packard Laboratories, open-sourced by HP in 2005 and developed by Google from 2006 to 2017, now by the Tesseract contributors. Licensed under the Apache License, Version 2.0, reproduced in [NOTICES/Apache-License-2.0.txt](NOTICES/Apache-License-2.0.txt).

### tessdata_fast

The English, Italian and orientation (`osd`) language data from [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), commit `87416418657359cb625c412a48b6e1d6d41c29bd`, by the Tesseract contributors. Licensed under the Apache License, Version 2.0, reproduced in [NOTICES/Apache-License-2.0.txt](NOTICES/Apache-License-2.0.txt).

### Leptonica

[Leptonica](http://www.leptonica.org/) 1.87.0 by Dan Bloomberg, used under the BSD 2-Clause License:

Copyright (C) 2001-2020 Leptonica. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL ANY CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

### libjpeg and libpng

Tesseract4Android's image libraries: this software is based in part on the work of the Independent JPEG Group (libjpeg 9f, copyright (C) 1991-2024, Thomas G. Lane, Guido Vollbeding), and it uses [libpng](http://www.libpng.org/pub/png/libpng.html) 1.6.59 under the PNG Reference Library License, reproduced in [NOTICES/libpng-1.6.59-LICENSE.txt](NOTICES/libpng-1.6.59-LICENSE.txt).

### Other libraries

AndroidX (including Jetpack Compose, CameraX and ExifInterface), kotlinx, Coil, OkHttp and Okio are licensed under the Apache License, Version 2.0.
