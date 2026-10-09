// Parity fixtures for scanner-core layer 4: perspective_crop.dart (and the image-package resize/rotate it relies on).
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:image/image.dart' as img;
import 'package:openscan/core/cv/models/point.dart';
import 'package:openscan/core/cv/models/quad.dart';
import 'package:openscan/core/cv/perspective_crop.dart' as pc;

import '../tool_lib/fixture.dart';

/// Smooth gradients in red/green and a fine diagonal pattern in blue, so bilinear sampling and box averaging both have detail to get wrong.
Uint8List texturedRgba(int w, int h) {
  final out = Uint8List(w * h * 4);
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      final i = (y * w + x) * 4;
      out[i] = (x * 255 ~/ max(1, w - 1));
      out[i + 1] = (y * 255 ~/ max(1, h - 1));
      out[i + 2] = (x * 7 + y * 13) % 256;
      out[i + 3] = 255;
    }
  }
  return out;
}

img.Image toImage(Uint8List rgba, int w, int h) =>
    img.Image.fromBytes(width: w, height: h, bytes: Uint8List.fromList(rgba).buffer, numChannels: 4, order: img.ChannelOrder.rgba);

Uint8List bytesOf(img.Image i) => i.getBytes(order: img.ChannelOrder.rgba);

Quad q(List<double> c) => Quad(topLeft: Pt(c[0], c[1]), topRight: Pt(c[2], c[3]), bottomRight: Pt(c[4], c[5]), bottomLeft: Pt(c[6], c[7]));

void main(List<String> args) {
  final w = FixtureWriter();
  for (final (sw, sh) in [(120, 90), (400, 300)]) {
    final rgba = texturedRgba(sw, sh);
    final source = toImage(rgba, sw, sh);
    // Not stored: the Kotlin test rebuilds it from the same formula and checks this digest.
    w.startCase('source_${sw}x$sh');
    w.value('width', sw);
    w.value('height', sh);
    w.value('rgba_sha256', sha256.convert(rgba).toString());
    final quads = <String, Quad>{
      'rotated': q([0.2 * sw, 0.1 * sh, 0.85 * sw, 0.18 * sh, 0.8 * sw, 0.9 * sh, 0.12 * sw, 0.8 * sh]),
      'outside': q([-0.1 * sw, -0.2 * sh, 0.7 * sw, 0.05 * sh, 1.2 * sw, 1.1 * sh, 0.1 * sw, 0.95 * sh]),
      'upright': q([10, 12, sw - 15.5, 12, sw - 15.5, sh - 9.25, 10, sh - 9.25]),
      'tiny': q([5, 5, 7, 5, 7, 7, 5, 7]),
      'collinear': q([0, 0, 10, 10, 20, 20, 30, 30]),
      'nan': q([double.nan, 0, 10, 0, 10, 10, 0, 10]),
    };
    for (final MapEntry(key: qname, value: quad) in quads.entries) {
      w.startCase('${sw}x${sh}_$qname');
      w.value('source', 'source_${sw}x$sh');
      w.value('quad', quadToString(quad));
      try {
        final size = pc.outputSize(quad);
        w.value('output_size', '${size.width},${size.height}');
      } catch (e) {
        w.value('output_size', 'throws');
      }
      final natural = max(sw, sh);
      for (final (label, maxEdge) in [
        ('none', null),
        ('huge', 100000),
        ('mild', (natural * 0.7).round()),
        ('half', natural ~/ 2),
        ('strong', (natural * 0.3).round()),
      ]) {
        try {
          final out = pc.warpToPage(source, quad, maxEdge: maxEdge);
          if (out == null) {
            w.value('warp_$label', 'null');
          } else {
            w.value('warp_${label}_size', '${out.width},${out.height}');
            w.bytes('warp_$label', bytesOf(out));
          }
        } catch (e) {
          w.value('warp_$label', 'throws');
        }
      }
      if (qname == 'rotated') {
        final warped = pc.warpToPage(source, quad)!;
        for (final turns in [1, 2, 3, -1, 5]) {
          // What _cropDecoded does before encoding.
          final rotated = img.copyRotate(warped, angle: 90 * (turns % 4));
          w.value('rotate_${turns}_size', '${rotated.width},${rotated.height}');
          w.bytes('rotate_$turns', bytesOf(rotated));
        }
      }
    }

    w.startCase('${sw}x${sh}_resize');
    w.value('source', 'source_${sw}x$sh');
    for (final (rw, rh) in [(sw ~/ 2, sh ~/ 2), (sw ~/ 3, sh ~/ 3 + 1), (37, 23), (1, 1), (sw, sh), (sw + 10, sh + 10)]) {
      w.bytes('resize_${rw}x$rh', bytesOf(img.copyResize(source, width: rw, height: rh, interpolation: img.Interpolation.average)));
    }
  }

  final r = Random(100);
  for (int i = 0; i < 20; i++) {
    w.startCase('normalized_$i');
    final n = q(List.generate(8, (_) => r.nextDouble()));
    w.value('quad', quadToString(n));
    w.value('pixels_landscape', quadToString(pc.quadInPixelsOf(n, 400, 300)));
    w.value('pixels_portrait', quadToString(pc.quadInPixelsOf(n, 300, 400)));
    w.value('pixels_square', quadToString(pc.quadInPixelsOf(n, 256, 256)));
  }
  w.save(args[0], 'crop.tsv');
}
