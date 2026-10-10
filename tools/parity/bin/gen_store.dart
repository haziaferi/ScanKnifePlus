// Parity fixtures for page normalization: compress.dart's fitToMaxEdge (the image package's average copyResize, sized from one edge).
import 'dart:typed_data';

import 'package:image/image.dart' as img;
import 'package:openscan/core/cv/compress.dart';

import '../tool_lib/fixture.dart';

void main(List<String> args) {
  final w = FixtureWriter();
  // (name, width, height, maxEdge). Covers landscape, portrait, square (width wins), already-fitting, null, a .5 tie in the derived edge, and
  // an aspect so extreme the derived edge rounds to 0.
  final cases = <(String, int, int, int?)>[
    ('landscape_300x200_120', 300, 200, 120),
    ('portrait_200x300_77', 200, 300, 77),
    ('odd_101x99_50', 101, 99, 50),
    ('square_64_63', 64, 64, 63),
    ('fits_64_64', 64, 64, 64),
    ('null_cap', 40, 30, null),
    ('tie_200x5_100', 200, 5, 100),
    ('thin_7x500_300', 7, 500, 300),
    ('extreme_400x1_100', 400, 1, 100),
  ];
  for (final (name, width, height, maxEdge) in cases) {
    final rgba = noiseRgba(width, height, width * 31 + height);
    w.startCase(name);
    w.value('width', width);
    w.value('height', height);
    w.value('max_edge', maxEdge ?? 'null');
    w.input('rgba', rgba);
    try {
      final src = img.Image.fromBytes(width: width, height: height, bytes: rgba.buffer, numChannels: 4, order: img.ChannelOrder.rgba);
      final out = fitToMaxEdge(src, maxEdge);
      w.value('same', identical(out, src));
      w.value('out_size', '${out.width},${out.height}');
      w.bytes('out', Uint8List.fromList(out.getBytes(order: img.ChannelOrder.rgba)));
    } catch (e) {
      // Recorded so the Kotlin test fails loudly if OpenScan ever starts throwing here.
      w.value('out_size', 'throws');
    }
  }
  w.save(args[0], 'store.tsv');
}
