# Parity fixtures

The scanner in `scanner-core` is a line-by-line Kotlin port of OpenScan's pure-Dart scanner code. To prove the port gives the same results, OpenScan's original Dart code is run on fixed inputs and its outputs are saved as test fixtures in `scanner-core/src/test/resources/parity/`. The Kotlin tests replay the same inputs and must reproduce those outputs exactly.

## Regenerating

Requirements: a Dart SDK (3.10 or newer, no Flutter needed) and a checkout of [OpenScan](https://github.com/ethereal-developers/OpenScan).

```
tools/parity/gen.sh /path/to/OpenScan [/path/to/dart]
```

The script copies the needed OpenScan sources into `tools/parity/lib/` (ignored by git) and runs every `bin/gen_*.dart` generator. The current fixtures were generated from OpenScan commit `841d25ace0c9634b9919ac5da44a866e411c17cf`.

## Fixture format

Plain text, one `key<TAB>value` per line; a `case<TAB>name` line starts a new case. Buffers are `b64:` (raw bytes), `gz:` (gzipped, for large inputs) or `sha256:` (digest only, for large outputs).
