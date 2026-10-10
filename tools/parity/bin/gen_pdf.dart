// Parity fixtures for PDF export: where file_operations.dart createPdf places each page image (the pdf package's Center + Image layout on a page
// with a 5 pt margin), and document_naming.dart exportFileName.
import 'dart:convert';

import 'package:image/image.dart' as img;
import 'package:openscan/core/data/document_naming.dart';
import 'package:pdf/pdf.dart';
import 'package:pdf/widgets.dart' as pw;

import '../tool_lib/fixture.dart';

Future<void> main(List<String> args) async {
  final w = FixtureWriter();
  final formats = {'a4': PdfPageFormat.a4, 'letter': PdfPageFormat.letter, 'legal': PdfPageFormat.legal};
  // Pages taller, wider and smaller than the printable area, a square, and both extreme aspects.
  final sizes = [(2400, 3200), (3200, 2400), (1700, 2200), (100, 80), (900, 900), (12, 2400), (2400, 12)];
  for (final MapEntry(key: name, value: format) in formats.entries) {
    for (final (width, height) in sizes) {
      final jpeg = img.encodeJpg(img.Image(width: width, height: height), quality: 85);
      // The same widget tree as createPdf, uncompressed so the content stream can be read.
      final doc = pw.Document(compress: false);
      doc.addPage(pw.Page(
        pageFormat: format,
        build: (pw.Context context) => pw.Center(child: pw.Image(pw.MemoryImage(jpeg))),
        margin: pw.EdgeInsets.all(5.0),
      ));
      final text = latin1.decode(await doc.save());
      // The page is drawn as `1 0 0 1 tx ty cm` (the margin), then the image as `a 0 0 d e f cm /I.. Do`: a and d are the drawn width and
      // height, e and f its lower left corner inside the margin.
      final m = RegExp(r'1 0 0 1 ([-\d.]+) ([-\d.]+) cm .* ([-\d.]+) 0 0 ([-\d.]+) ([-\d.]+) ([-\d.]+) cm /I\w* Do').firstMatch(text);
      final box = RegExp(r'/MediaBox\[([^\]]+)\]').firstMatch(text);
      w.startCase('${name}_${width}x$height');
      w.value('format', name);
      w.value('width', width);
      w.value('height', height);
      w.value('media_box', box!.group(1)!.trim());
      w.value('margin', '${m!.group(1)},${m.group(2)}');
      w.value('draw', '${m.group(3)},${m.group(4)},${m.group(5)},${m.group(6)}');
      w.value('page_width', format.width);
      w.value('page_height', format.height);
    }
  }
  final names = ['Invoice March', '  Tax  return\t2026 ', 'Facture été: n°3/4', r'a\b*c?"d<e>f|g', '...', '', 'ScanKnife-2026-10-10-1791590400000', 'x' * 300, '日本語のメモ'];
  for (var i = 0; i < names.length; i++) {
    w.startCase('name_$i');
    w.value('name_b64', base64.encode(utf8.encode(names[i])));
    w.value('export_name', exportFileName(names[i], now: DateTime.fromMillisecondsSinceEpoch(0, isUtc: true)));
  }
  w.save(args[0], 'pdf.tsv');
}

