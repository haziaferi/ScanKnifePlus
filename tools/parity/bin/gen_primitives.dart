// Parity fixtures for scanner-core layer 1: edge_detection.dart and image_filter_utils.dart.
import 'dart:typed_data';

import 'package:openscan/core/cv/edge_detection.dart' as ed;
import 'package:openscan/core/image_filter/utils/image_filter_utils.dart' as fu;

import '../tool_lib/fixture.dart';

void main(List<String> args) {
  final w = FixtureWriter();
  final cases = <(String, int, int, Uint8List)>[
    ('noise_1x1', 1, 1, noiseRgba(1, 1, 11)),
    ('noise_3x2', 3, 2, noiseRgba(3, 2, 12)),
    ('noise_31x17', 31, 17, noiseRgba(31, 17, 13)),
    ('document_64x48', 64, 48, documentRgba(64, 48, 14)),
    // Over 92,682 pixels, so Otsu's wB * wF product exceeds a 32-bit int.
    ('document_360x280', 360, 280, documentRgba(360, 280, 15)),
  ];

  for (final (name, width, height, rgba) in cases) {
    w.startCase(name);
    w.value('width', width);
    w.value('height', height);
    w.input('rgba', rgba);

    final gray = ed.rgbaToGrayscale(rgba, width, height);
    final blur = ed.gaussianBlur3(gray, width, height);
    final sobel = ed.sobelMagnitude(blur, width, height);
    final otsu = ed.otsuThreshold(sobel);
    final mask = ed.threshold(sobel, otsu);
    w.bytes('gray', gray);
    w.bytes('blur', blur);
    w.bytes('sobel', sobel);
    w.value('otsu', otsu);
    w.bytes('mask', mask);
    w.bytes('dilate1', ed.dilate(mask, width, height, 1));
    w.bytes('dilate4', ed.dilate(mask, width, height, 4));
    w.value('otsu_gray', ed.otsuThreshold(gray));

    for (final s in [-1.5, -0.3, 0.0, 0.6, 1.0, 2.0]) {
      final b = Uint8List.fromList(rgba);
      fu.saturation(b, s);
      w.bytes('saturation_$s', b);
    }
    final g = Uint8List.fromList(rgba);
    fu.grayscale(g);
    w.bytes('grayscale', g);
    for (final a in [-1.0, -0.4, 0.0, 0.25, 0.99, 1.0]) {
      final b = Uint8List.fromList(rgba);
      fu.contrast(b, a);
      w.bytes('contrast_$a', b);
    }
  }
  w.save(args[0], 'primitives.tsv');
}
