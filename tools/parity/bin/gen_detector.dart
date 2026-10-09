// Parity fixtures for scanner-core layer 3: document_detector.dart.
// The one-shot entry point reads an image file, so inputs are written as lossless PNGs: the decoded pixels are exactly the stored input.
import 'dart:io';
import 'dart:typed_data';

import 'package:image/image.dart' as img;
import 'package:openscan/core/cv/document_detector.dart' as dd;
import 'package:openscan/core/cv/edge_detection.dart' as ed;
import 'package:openscan/core/cv/models/detection_result.dart';
import 'package:openscan/core/cv/models/point.dart';
import 'package:openscan/core/cv/models/quad.dart';

import '../tool_lib/fixture.dart';

Future<void> main(List<String> args) async {
  final w = FixtureWriter();
  final tmp = Directory.systemTemp.createTempSync('parity_detector');
  final cases = <(String, int, int, Uint8List)>[
    ('small_160x120', 160, 120, jitteredDocumentRgba(160, 120, 80, 0.1)),
    ('exact_700x400', 700, 400, jitteredDocumentRgba(700, 400, 81, 0.08)),
    ('landscape_900x675', 900, 675, jitteredDocumentRgba(900, 675, 82, 0.1)),
    ('portrait_360x1000', 360, 1000, jitteredDocumentRgba(360, 1000, 83, 0.06)),
    ('odd_701x301', 701, 301, jitteredDocumentRgba(701, 301, 84, 0.05)),
    ('blank_200x150', 200, 150, Uint8List.fromList(List.filled(200 * 150 * 4, 128))),
  ];
  for (final (name, width, height, rgba) in cases) {
    w.startCase(name);
    w.value('width', width);
    w.value('height', height);
    w.input('rgba', rgba);

    final file = File('${tmp.path}/$name.png');
    file.writeAsBytesSync(img.encodePng(img.Image.fromBytes(
      width: width,
      height: height,
      bytes: rgba.buffer,
      numChannels: 4,
      order: img.ChannelOrder.rgba,
    )));
    final result = await dd.detectDocumentIsolateEntry(file.path);
    switch (result) {
      case DetectionSuccess(:final quad, :final imageWidth, :final imageHeight):
        w.value('result', 'success');
        w.value('quad', quadToString(quad));
        w.value('result_size', '$imageWidth,$imageHeight');
      case DetectionNotFound(:final imageWidth, :final imageHeight):
        w.value('result', 'not_found');
        w.value('result_size', '$imageWidth,$imageHeight');
      case DetectionFailure(:final message):
        w.value('result', 'failure:$message');
    }

    // The shared grayscale pipeline directly, with and without a previous quad, at the input's own size.
    final gray = ed.rgbaToGrayscale(rgba, width, height);
    w.value('gray_quad', quadToString(dd.detectQuadFromGrayscale(gray, width, height)));
    final prev = Quad(
      topLeft: Pt(width * 0.9, height * 0.15),
      topRight: Pt(width * 0.85, height * 0.85),
      bottomRight: Pt(width * 0.1, height * 0.9),
      bottomLeft: Pt(width * 0.15, height * 0.1),
    );
    w.value('previous', quadToString(prev));
    w.value('gray_quad_with_previous', quadToString(dd.detectQuadFromGrayscale(gray, width, height, previousQuad: prev)));
  }
  tmp.deleteSync(recursive: true);
  w.save(args[0], 'detector.tsv');
}
