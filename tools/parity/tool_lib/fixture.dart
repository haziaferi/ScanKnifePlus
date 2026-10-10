import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// Writes a parity fixture: `case<TAB>name` starts a case, then `key<TAB>value` lines.
/// Byte values are `b64:` (raw), `gz:` (gzip then base64) or `sha256:` (hex digest, for large outputs).
class FixtureWriter {
  final StringBuffer _out = StringBuffer();

  void startCase(String name) => _out.writeln('case\t$name');

  void value(String key, Object v) => _out.writeln('$key\t$v');

  /// Small buffers are stored whole; large ones are stored as a digest only.
  void bytes(String key, Uint8List b) {
    if (b.length <= 4096) {
      _out.writeln('$key\tb64:${base64.encode(b)}');
    } else {
      _out.writeln('$key\tsha256:${sha256.convert(b)}');
    }
  }

  /// Inputs are always stored whole (gzipped when large) so the Kotlin side can replay them.
  void input(String key, Uint8List b) {
    if (b.length <= 4096) {
      _out.writeln('$key\tb64:${base64.encode(b)}');
    } else {
      _out.writeln('$key\tgz:${base64.encode(gzip.encode(b))}');
    }
  }

  void save(String dir, String fileName) {
    File('$dir/$fileName').writeAsStringSync(_out.toString());
    stdout.writeln('wrote $dir/$fileName');
  }
}

/// Uniform RGBA noise; alpha is noise too, so code that must ignore alpha is exercised.
Uint8List noiseRgba(int w, int h, int seed) {
  final r = Random(seed);
  return Uint8List.fromList(List.generate(w * h * 4, (_) => r.nextInt(256)));
}

/// A bright, slightly rotated sheet on a dark, textured background. Noise is drawn per 4x4 block so large inputs gzip well.
Uint8List documentRgba(int w, int h, int seed) {
  final r = Random(seed);
  final blockNoise = _blockNoise(r, w, h);
  final corners = [
    [0.18 * w, 0.12 * h],
    [0.84 * w, 0.20 * h],
    [0.78 * w, 0.90 * h],
    [0.12 * w, 0.82 * h],
  ];
  return _sheetRgba(w, h, corners, blockNoise);
}

/// Like [documentRgba] but with each sheet corner moved by up to [jitter] (fraction of the frame), so cases cover rotated and skewed sheets.
Uint8List jitteredDocumentRgba(int w, int h, int seed, double jitter) {
  final r = Random(seed);
  double j() => (r.nextDouble() * 2 - 1) * jitter;
  // The corners are drawn before the noise here, the other way round from documentRgba; both orders are kept so the fixtures stay the same.
  final corners = [
    [(0.18 + j()) * w, (0.12 + j()) * h],
    [(0.84 + j()) * w, (0.20 + j()) * h],
    [(0.78 + j()) * w, (0.90 + j()) * h],
    [(0.12 + j()) * w, (0.82 + j()) * h],
  ];
  return _sheetRgba(w, h, corners, _blockNoise(r, w, h));
}

List<int> _blockNoise(Random r, int w, int h) => List.generate(((w + 3) ~/ 4) * ((h + 3) ~/ 4), (_) => r.nextInt(13) - 6);

/// Rasterises the sheet inside [corners] (clockwise) over the background, adding one [blockNoise] value per 4x4 block.
Uint8List _sheetRgba(int w, int h, List<List<double>> corners, List<int> blockNoise) {
  final bw = (w + 3) ~/ 4;
  bool inside(double x, double y) {
    for (int i = 0; i < 4; i++) {
      final a = corners[i], b = corners[(i + 1) % 4];
      if ((b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]) < 0) return false;
    }
    return true;
  }

  final out = Uint8List(w * h * 4);
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      final n = blockNoise[(y ~/ 4) * bw + (x ~/ 4)];
      final paper = inside(x + 0.5, y + 0.5);
      final i = (y * w + x) * 4;
      out[i] = ((paper ? 212 : 46) + n).clamp(0, 255);
      out[i + 1] = ((paper ? 208 : 52) + n).clamp(0, 255);
      out[i + 2] = ((paper ? 198 : 61) + n).clamp(0, 255);
      out[i + 3] = 255;
    }
  }
  return out;
}

/// Serializes a quad as 8 comma-separated doubles (TL, TR, BR, BL; x then y), using Dart's shortest round-trip formatting.
String quadToString(dynamic q) => q == null
    ? 'null'
    : [q.topLeft, q.topRight, q.bottomRight, q.bottomLeft].expand((p) => [p.x, p.y]).join(',');
