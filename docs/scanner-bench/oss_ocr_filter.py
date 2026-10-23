# Python port of the result filter in cpp/src/DocumentOCR.cpp (detectTextImpl) of OSS-DocumentScanner
# (https://github.com/ossappscollective/OSS-DocumentScanner) at commit ff05fc52b601d1cccb4076f3f6c3318f74e72f0c.
# MIT License, Copyright (c) 2022 Martin Guillon. Standard library only, so docs/ocr-bench/score.py can import it.
"""OSS-DocumentScanner's OCR result filter: which recognised lines its app keeps, and how it joins them."""

WHITESPACE = " \t\n\r\f\v"


def is_repetitive(s):
    """isRepetitive: more than half of the characters are i, l or I."""
    return sum(1 for ch in s if ch in "ilI") > (len(s) + 1) // 2


def keep(conf, raw):
    """A line survives when its confidence is non-zero and none of detectTextImpl's rejections apply (sizes are UTF-8 bytes)."""
    if conf == 0:
        return False
    size = len(raw.encode("utf-8"))
    return not (size < 2 or conf < 51 or (size == 2 and raw[0] == raw[1]) or (size < 4 and conf < 60) or is_repetitive(raw))


def filtered_text(blocks):
    """[[(confidence, raw line text)] per block, in block order] -> the text the app shows (trim false: raw lines joined, then trimmed)."""
    return "".join(raw for lines in blocks for conf, raw in lines if keep(conf, raw)).strip(WHITESPACE)
