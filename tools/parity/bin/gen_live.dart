// Parity fixtures for scanner-core layer 5: quad_smoother.dart, auto_capture_detector.dart and live_scan_controller.dart.
// Scenarios are scripted (time in microseconds, quad or null) and every observable output is recorded after each step.
import 'dart:math';
import 'dart:typed_data';

import 'package:openscan/core/cv/edge_detection.dart' as ed;
import 'package:openscan/core/cv/models/point.dart';
import 'package:openscan/core/cv/models/quad.dart';
import 'package:openscan/view/screens/live_scan/auto_capture_detector.dart';
import 'package:openscan/view/screens/live_scan/live_scan_controller.dart';
import 'package:openscan/view/screens/live_scan/quad_smoother.dart';

import '../tool_lib/fixture.dart';

final epoch = DateTime.utc(2026);

Quad jittered(Quad base, Random r, double amount) {
  Pt j(Pt p) => Pt(p.x + (r.nextDouble() * 2 - 1) * amount, p.y + (r.nextDouble() * 2 - 1) * amount);
  return Quad(topLeft: j(base.topLeft), topRight: j(base.topRight), bottomRight: j(base.bottomRight), bottomLeft: j(base.bottomLeft));
}

Quad qd(double l, double t, double r, double b) =>
    Quad(topLeft: Pt(l, t), topRight: Pt(r, t + 0.01), bottomRight: Pt(r - 0.02, b), bottomLeft: Pt(l + 0.01, b - 0.02));

/// A scripted stream: each step is (microseconds since start, quad or null).
List<(int, Quad?)> scenario(int seed) {
  final r = Random(seed);
  final a = qd(0.1, 0.12, 0.9, 0.88);
  final b = qd(0.3, 0.25, 0.75, 0.7);
  final steps = <(int, Quad?)>[];
  var t = 0;
  void add(Quad? q) {
    t += 120000 + r.nextInt(250000);
    steps.add((t, q));
  }

  for (int i = 0; i < 6; i++) add(jittered(a, r, 0.004)); // held still with noise
  add(jittered(b, r, 0.003)); // one-off wrong detection
  for (int i = 0; i < 3; i++) add(jittered(a, r, 0.004));
  for (int i = 0; i < 4; i++) add(jittered(b, r, 0.003)); // real jump, confirmed after 3
  add(null); // short miss within grace
  for (int i = 0; i < 3; i++) add(jittered(b, r, 0.003));
  t += 600000; // long gap, then a miss past the grace period
  steps.add((t, null));
  for (int i = 0; i < 5; i++) add(jittered(a, r, 0.03)); // noisy re-acquire
  // A slow, steady drift (continued tracking) with labels rotated by one slot.
  for (int i = 0; i < 6; i++) {
    final s = 0.01 * i;
    final q = qd(0.12 + s, 0.1 + s, 0.88 + s / 2, 0.9);
    add(Quad(topLeft: q.topRight, topRight: q.bottomRight, bottomRight: q.bottomLeft, bottomLeft: q.topLeft));
  }
  return steps;
}

/// Grayscale frame with up to two bright sheets on a dark textured background: sheet A (left, slightly bigger) and sheet B (right).
/// With both visible, A scores higher on its own; the worker's remembered previous quad decides whether B wins instead.
Uint8List sheetsGray(int w, int h, {required bool a, required bool b}) {
  final r = Random(140);
  final sheets = <List<List<double>>>[
    if (a) [[0.04 * w, 0.10 * h], [0.47 * w, 0.08 * h], [0.48 * w, 0.92 * h], [0.05 * w, 0.90 * h]],
    if (b) [[0.55 * w, 0.14 * h], [0.95 * w, 0.12 * h], [0.94 * w, 0.86 * h], [0.56 * w, 0.88 * h]],
  ];
  bool inside(List<List<double>> c, double x, double y) {
    for (int i = 0; i < 4; i++) {
      final p = c[i], q = c[(i + 1) % 4];
      if ((q[0] - p[0]) * (y - p[1]) - (q[1] - p[1]) * (x - p[0]) < 0) return false;
    }
    return true;
  }

  final bw = (w + 3) ~/ 4;
  final noise = List.generate(bw * ((h + 3) ~/ 4), (_) => r.nextInt(9) - 4);
  final out = Uint8List(w * h);
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      final paper = sheets.any((c) => inside(c, x + 0.5, y + 0.5));
      out[y * w + x] = ((paper ? 205 : 50) + noise[(y ~/ 4) * bw + (x ~/ 4)]).clamp(0, 255);
    }
  }
  return out;
}

Future<void> main(List<String> args) async {
  final w = FixtureWriter();

  for (int seed = 0; seed < 6; seed++) {
    final steps = scenario(110 + seed);
    w.startCase('smoother_$seed');
    w.value('steps', steps.map((s) => '${s.$1}:${quadToString(s.$2)}').join('|'));
    var now = epoch;
    final smoother = QuadSmoother(now: () => now);
    var notifications = 0;
    smoother.smoothedQuad.addListener(() => notifications++);
    final outputs = <String>[];
    for (final (micros, quad) in steps) {
      now = epoch.add(Duration(microseconds: micros));
      smoother.onRawQuad(quad);
      outputs.add('${quadToString(smoother.smoothedQuad.value)}#$notifications');
    }
    smoother.reset();
    outputs.add('${quadToString(smoother.smoothedQuad.value)}#$notifications');
    w.value('outputs', outputs.join('|'));

    // Auto-capture on the smoother's output, capturing whenever it fires.
    now = epoch;
    final events = <String>[];
    late AutoCaptureDetector detector;
    detector = AutoCaptureDetector(
      now: () => now,
      onStable: () {
        events.add('stable@${now.difference(epoch).inMicroseconds}');
        detector.notifyCaptured();
      },
      onImminentChanged: (v) => events.add('imminent=$v@${now.difference(epoch).inMicroseconds}'),
    );
    final smoother2 = QuadSmoother(now: () => now);
    for (final (micros, quad) in steps) {
      now = epoch.add(Duration(microseconds: micros));
      smoother2.onRawQuad(quad);
      detector.onQuadUpdate(smoother2.smoothedQuad.value);
      events.add('cooldown=${detector.isInCooldown}');
    }
    detector.enabled = false;
    events.add('disabled');
    w.value('auto_events', events.join('|'));
  }

  // Live detection worker: the real controller (worker isolate keeps previousQuad and forgets it after 5 misses).
  final r = Random(130);
  final frames = <(String, Uint8List)>[];
  Uint8List grayDoc(int seed) => ed.rgbaToGrayscale(jitteredDocumentRgba(320, 240, seed, 0.06), 320, 240);
  final blank = Uint8List.fromList(List.filled(320 * 240, 90));
  for (int i = 0; i < 3; i++) frames.add(('doc131', grayDoc(131)));
  frames.add(('doc132', grayDoc(132)));
  for (int i = 0; i < 6; i++) frames.add(('blank', blank));
  frames.add(('doc132', grayDoc(132)));
  frames.add(('doc133', grayDoc(133)));
  // The previous-quad bias and its expiry: B alone, then both (B is remembered); a long miss streak, then both (forgotten, A wins);
  // B again, a short miss streak, then both (still remembered).
  final onlyB = sheetsGray(320, 240, a: false, b: true);
  final both = sheetsGray(320, 240, a: true, b: true);
  for (final f in ['onlyB', 'onlyB', 'both', 'blank', 'blank', 'blank', 'blank', 'blank', 'blank', 'both', 'onlyB', 'blank', 'blank', 'both']) {
    frames.add((f, f == 'onlyB' ? onlyB : f == 'both' ? both : blank));
  }
  final controller = LiveScanController();
  await controller.start();
  w.startCase('live_worker');
  final stored = <String>{};
  final results = <String>[];
  // latestQuad is a ValueNotifier: count its notifications so the Kotlin delivery semantics (null after null is not repeated) are pinned too.
  var quadNotifications = 0;
  controller.latestQuad.addListener(() => quadNotifications++);
  final notificationCounts = <int>[];
  for (final (name, gray) in frames) {
    if (stored.add(name)) w.input('frame_$name', gray);
    controller.submitFrame(gray, 320, 240);
    while (controller.isBusy) {
      await Future.delayed(const Duration(milliseconds: 1));
    }
    results.add('$name=${quadToString(controller.latestQuad.value)}');
    notificationCounts.add(quadNotifications);
  }
  await controller.dispose();
  w.value('frames', frames.map((f) => f.$1).join(','));
  w.value('results', results.join('|'));
  w.value('quad_notifications', notificationCounts.join(','));

  for (int i = 0; i < 20; i++) {
    w.startCase('rotate_$i');
    final q = Quad(
      topLeft: Pt(r.nextDouble() * 320, r.nextDouble() * 240),
      topRight: Pt(r.nextDouble() * 320, r.nextDouble() * 240),
      bottomRight: Pt(r.nextDouble() * 320, r.nextDouble() * 240),
      bottomLeft: Pt(r.nextDouble() * 320, r.nextDouble() * 240),
    );
    w.value('quad', quadToString(q));
    w.value('rotated', quadToString(rotateQuadForPortrait(q, 320, 240)));
  }
  w.save(args[0], 'live.tsv');
}
