// Parity fixtures for scanner-core layer 2: contours.dart, plus Dart's List.sort order with ties.
import 'dart:math';
import 'dart:typed_data';

import 'package:openscan/core/cv/contours.dart' as ct;
import 'package:openscan/core/cv/edge_detection.dart' as ed;
import 'package:openscan/core/cv/models/point.dart';
import 'package:openscan/core/cv/models/quad.dart';

import '../tool_lib/fixture.dart';

Uint8List edgeMask(Uint8List rgba, int w, int h, int radius) {
  final gray = ed.rgbaToGrayscale(rgba, w, h);
  final sobel = ed.sobelMagnitude(ed.gaussianBlur3(gray, w, h), w, h);
  return ed.dilate(ed.threshold(sobel, ed.otsuThreshold(sobel)), w, h, radius);
}

Quad randomQuad(Random r, int w, int h) => Quad(
      topLeft: Pt(r.nextDouble() * w, r.nextDouble() * h),
      topRight: Pt(r.nextDouble() * w, r.nextDouble() * h),
      bottomRight: Pt(r.nextDouble() * w, r.nextDouble() * h),
      bottomLeft: Pt(r.nextDouble() * w, r.nextDouble() * h),
    );

/// Rectangle outlines that all have the same pixel count (same perimeter, different aspect ratios) plus [specks] isolated pixels, so the
/// component sort has ties among the biggest components and runs Dart's quicksort path (more than 33 components).
Uint8List tieMask(int w, int h, int seed, int specks) {
  final r = Random(seed);
  final m = Uint8List(w * h);
  void rect(int x0, int y0, int rw, int rh) {
    for (int x = x0; x < x0 + rw; x++) {
      m[y0 * w + x] = 1;
      m[(y0 + rh - 1) * w + x] = 1;
    }
    for (int y = y0; y < y0 + rh; y++) {
      m[y * w + x0] = 1;
      m[y * w + x0 + rw - 1] = 1;
    }
  }

  // Four outlines with rw + rh == 110, laid out in a 2x2 grid in a shuffled order.
  final sizes = [(60, 50), (50, 60), (70, 40), (40, 70)]..shuffle(r);
  final origins = [(5, 5), (105, 5), (5, 105), (105, 105)];
  for (int i = 0; i < 4; i++) {
    rect(origins[i].$1, origins[i].$2, sizes[i].$1, sizes[i].$2);
  }
  int placed = 0;
  while (placed < specks) {
    final x = 1 + r.nextInt(w - 2), y = 1 + r.nextInt(h - 2);
    bool free = true;
    for (int dy = -1; dy <= 1 && free; dy++) {
      for (int dx = -1; dx <= 1 && free; dx++) {
        if (m[(y + dy) * w + x + dx] == 1) free = false;
      }
    }
    if (!free) continue;
    m[y * w + x] = 1;
    placed++;
  }
  return m;
}

void main(List<String> args) {
  final w = FixtureWriter();

  // Masks: a few document shapes, plus noise masks with many equal-size components (exercises Dart's unstable sort).
  final masks = <(String, int, int, Uint8List)>[
    ('doc_64x48', 64, 48, edgeMask(documentRgba(64, 48, 21), 64, 48, 1)),
    for (int s = 0; s < 6; s++)
      ('jitter${s}_160x120', 160, 120, edgeMask(jitteredDocumentRgba(160, 120, 30 + s, 0.1), 160, 120, 1 + s % 3)),
    ('jitter_big_360x280', 360, 280, edgeMask(jitteredDocumentRgba(360, 280, 40, 0.08), 360, 280, 2)),
    for (int s = 0; s < 8; s++) ('ties${s}_220x220', 220, 220, tieMask(220, 220, 70 + s, 40 + 7 * s)),
    ('noise_80x60', 80, 60, ed.threshold(noiseRgba(80, 60, 50).sublist(0, 4800), 200)),
    ('noise_dilated_80x60', 80, 60, ed.dilate(ed.threshold(noiseRgba(80, 60, 51).sublist(0, 4800), 230), 80, 60, 1)),
  ];
  for (final (name, width, height, mask) in masks) {
    w.startCase(name);
    w.value('width', width);
    w.value('height', height);
    w.input('mask', mask);
    final candidates = ct.findDocumentQuadCandidates(mask, width, height);
    w.value('candidates', candidates.isEmpty ? 'none' : candidates.map(quadToString).join(';'));
    w.value('best', quadToString(ct.pickBestQuad(candidates, width, height)));
    final prev = Quad(
      topLeft: Pt(width * 0.8, height * 0.1),
      topRight: Pt(width * 0.9, height * 0.9),
      bottomRight: Pt(width * 0.1, height * 0.9),
      bottomLeft: Pt(width * 0.2, height * 0.1),
    );
    w.value('previous', quadToString(prev));
    w.value('best_with_previous', quadToString(ct.pickBestQuad(candidates, width, height, previousQuad: prev)));
    w.value('find', quadToString(ct.findDocumentQuad(mask, width, height)));
  }

  // Public helpers on random geometry.
  final r = Random(60);
  for (int i = 0; i < 40; i++) {
    w.startCase('geometry_$i');
    final q = randomQuad(r, 100, 80);
    final ref = randomQuad(r, 100, 80);
    w.value('points', quadToString(q));
    w.value('reference', quadToString(ref));
    w.value('sorted', quadToString(ct.sortCorners(q.points)));
    final m = ct.bestCornerAssignment(q.points, ref);
    w.value('assigned', quadToString(m.quad));
    w.value('assigned_distance', m.totalDistance);
    w.value('plausible', ct.isPlausibleQuad(q, 100, 80));
    w.value('plausible_sorted', ct.isPlausibleQuad(ct.sortCorners(q.points), 100, 80));
  }

  // Dart's List.sort with many ties: record the order of ids after sorting by key only.
  for (final n in [5, 32, 33, 34, 100, 1000]) {
    w.startCase('sort_$n');
    final keys = List.generate(n, (_) => r.nextInt(7));
    final ids = List.generate(n, (i) => i)..sort((a, b) => keys[a].compareTo(keys[b]));
    w.value('keys', keys.join(','));
    w.value('order', ids.join(','));
  }
  w.save(args[0], 'contours.tsv');
}
