// Parity fixtures for the live-scan frame adapter: frame_adapter.dart. Only the YUV420 (Y plane) path is ported: BGRA8888 is iOS-only and CameraX never delivers it.
import 'dart:math';
import 'dart:typed_data';

import 'package:camera_platform_interface/camera_platform_interface.dart';
import 'package:openscan/core/cv/edge_detection.dart' as ed;
import 'package:openscan/core/cv/frame_adapter.dart';

import '../tool_lib/fixture.dart';

/// A Y plane (or BGRA buffer) with [padding] extra bytes per row filled with noise, as camera HALs pad rows; the adapter must never read them.
Uint8List padRows(Uint8List packed, int rowBytes, int rows, int padding, int seed) {
  final r = Random(seed);
  final stride = rowBytes + padding;
  final out = Uint8List(stride * rows);
  for (int y = 0; y < rows; y++) {
    out.setRange(y * stride, y * stride + rowBytes, packed, y * rowBytes);
    for (int p = 0; p < padding; p++) {
      out[y * stride + rowBytes + p] = r.nextInt(256);
    }
  }
  return out;
}

/// The size live_scan_screen.dart computes for submitFrame (it repeats the adapter's maths without the clamp).
(int, int) callerSize(int width, int height) {
  final scale = kLiveDetectionMaxDimension / (width > height ? width : height);
  return scale < 1.0 ? ((width * scale).round(), (height * scale).round()) : (width, height);
}

void main(List<String> args) {
  final w = FixtureWriter();
  w.startCase('constants');
  w.value('live_detection_max_dimension', kLiveDetectionMaxDimension);

  // (name, width, height, row padding, seed). Covers the camera sizes CameraX commonly delivers, no-downscale sizes, odd sizes and tiny frames.
  final sizes = <(String, int, int, int, int)>[
    ('hd_1280x720', 1280, 720, 64, 1),
    ('fhd_1920x1080', 1920, 1080, 0, 2),
    ('vga_640x480', 640, 480, 32, 3),
    ('exact_320x240', 320, 240, 0, 4),
    ('small_200x150', 200, 150, 8, 5),
    ('portrait_480x640', 480, 640, 16, 6),
    ('odd_1001x563', 1001, 563, 7, 7),
    ('tiny_3x2', 3, 2, 5, 8),
    ('one_1x1', 1, 1, 3, 9),
  ];
  for (final (name, width, height, padding, seed) in sizes) {
    final rgba = width >= 16 && height >= 16 ? jitteredDocumentRgba(width, height, seed, 0.05) : noiseRgba(width, height, seed);
    final y = ed.rgbaToGrayscale(rgba, width, height);

    w.startCase('y_$name');
    w.value('width', width);
    w.value('height', height);
    w.value('bytes_per_row', width + padding);
    w.input('plane', padRows(y, width, height, padding, seed + 100));
    final gray = grayscaleFromFrame(
      yPlaneOrBgraBytes: padRows(y, width, height, padding, seed + 100),
      bytesPerRow: width + padding,
      width: width,
      height: height,
      format: ImageFormatGroup.yuv420,
      targetLongEdge: kLiveDetectionMaxDimension,
    )!;
    final (cw, ch) = callerSize(width, height);
    w.value('out_size', '$cw,$ch');
    w.value('out_len', gray.length);
    w.bytes('gray', gray);

  }

  // Other target edges, to pin the size maths (the screen always passes 320).
  w.startCase('target_sizes');
  final targets = <String>[];
  for (final (width, height) in [(1280, 720), (1001, 563), (7, 3), (3000, 1), (1, 3000)]) {
    for (final target in [1, 50, 320, 1000, 5000]) {
      final out = grayscaleFromFrame(
        yPlaneOrBgraBytes: Uint8List((width + 1) * height),
        bytesPerRow: width + 1,
        width: width,
        height: height,
        format: ImageFormatGroup.yuv420,
        targetLongEdge: target,
      )!;
      targets.add('$width,$height,$target=${out.length}');
    }
  }
  w.value('lengths', targets.join('|'));

  // Formats and sizes the adapter refuses (callers skip the frame).
  w.startCase('rejected');
  final rejected = <String>[];
  for (final f in [ImageFormatGroup.jpeg, ImageFormatGroup.nv21, ImageFormatGroup.unknown]) {
    final out = grayscaleFromFrame(yPlaneOrBgraBytes: Uint8List(16), bytesPerRow: 4, width: 4, height: 4, format: f, targetLongEdge: 320);
    rejected.add('${f.name}=${out == null ? 'null' : out.length}');
  }
  for (final (width, height) in [(0, 4), (4, 0), (-1, 4)]) {
    final out = grayscaleFromFrame(yPlaneOrBgraBytes: Uint8List(16), bytesPerRow: 4, width: width, height: height, format: ImageFormatGroup.yuv420, targetLongEdge: 320);
    rejected.add('${width}x$height=${out == null ? 'null' : out.length}');
  }
  w.value('results', rejected.join('|'));

  w.save(args[0], 'frame.tsv');
}
