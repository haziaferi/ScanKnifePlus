// Parity fixtures for scanner-core layer 3b: document_filter_utils.dart and document_filters.dart.
import 'dart:typed_data';

import 'package:openscan/core/cv/edge_detection.dart' as ed;
import 'package:openscan/core/image_filter/filters/document_filters.dart' as df;
import 'package:openscan/core/image_filter/utils/document_filter_utils.dart' as du;

import '../tool_lib/fixture.dart';

/// A document photo with a warm colour cast and a lighting gradient across it, so auto-levels, lighten and the illumination-based filters all have work to do.
Uint8List castDocumentRgba(int w, int h, int seed) {
  final out = jitteredDocumentRgba(w, h, seed, 0.06);
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      final i = (y * w + x) * 4;
      final light = 0.55 + 0.45 * (x / w); // darker on the left
      out[i] = (out[i] * light + 18).round().clamp(0, 255);
      out[i + 1] = (out[i + 1] * light + 6).round().clamp(0, 255);
      out[i + 2] = (out[i + 2] * light - 10).round().clamp(0, 255);
      // Some "ink": thin dark strokes every 9 rows inside the sheet area.
      if (y % 9 == 0 && x > w * 0.25 && x < w * 0.7 && y > h * 0.25 && y < h * 0.75) {
        out[i] = 30;
        out[i + 1] = 32;
        out[i + 2] = 60;
      }
    }
  }
  return out;
}

void main(List<String> args) {
  final w = FixtureWriter();
  final cases = <(String, int, int, Uint8List)>[
    ('noise_1x1', 1, 1, noiseRgba(1, 1, 90)),
    ('noise_3x2', 3, 2, noiseRgba(3, 2, 91)),
    ('noise_40x30', 40, 30, noiseRgba(40, 30, 92)),
    ('cast_96x72', 96, 72, castDocumentRgba(96, 72, 93)),
    ('cast_640x400', 640, 400, castDocumentRgba(640, 400, 94)),
    // Longer than 640, so the B&W and Whiteboard fields are computed on a downscaled copy.
    ('cast_900x500', 900, 500, castDocumentRgba(900, 500, 95)),
    ('cast_portrait_300x700', 300, 700, castDocumentRgba(300, 700, 96)),
    ('flat_50x50', 50, 50, Uint8List.fromList(List.filled(50 * 50 * 4, 77))),
  ];
  for (final (name, width, height, rgba) in cases) {
    w.startCase(name);
    w.value('width', width);
    w.value('height', height);
    w.input('rgba', rgba);
    for (final filter in df.documentFiltersList) {
      final b = Uint8List.fromList(rgba);
      filter.apply(b, width, height);
      w.bytes('filter_${filter.name}', b);
    }

    // The utilities directly.
    final gray = ed.rgbaToGrayscale(rgba, width, height);
    w.bytes('box_blur_3', du.boxBlur(gray, width, height, 3));
    w.value('bounds_r', du.percentileBounds(du.channelHistogram(rgba, 0), 0.005, 0.005).join(','));
    w.value('bounds_gray', du.percentileBounds(du.grayHistogram(gray), 0.001, 0.05).join(','));
    final nw = (width ~/ 3).clamp(1, width), nh = (height ~/ 2).clamp(1, height);
    w.value('down_size', '$nw,$nh');
    w.bytes('down', du.downscaleGray(gray, width, height, nw, nh));
  }

  w.startCase('luts');
  for (final (low, high) in [(0, 255), (10, 240), (100, 101), (0, 1), (200, 255), (37, 180)]) {
    w.bytes('lut_${low}_$high', du.stretchLut(low, high));
  }
  w.value('bounds_empty', du.percentileBounds(List.filled(256, 0), 0.01, 0.01).join(','));
  w.value('by_name', ['Original', 'Auto', 'Lighten', 'Grayscale', 'B&W', 'Whiteboard', 'bogus'].map((n) => df.documentFilterByName(n).name).join(','));
  w.save(args[0], 'filters.tsv');
}
