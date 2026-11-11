import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/db/db.dart';
import 'package:path/path.dart' as p;

void main() {
  // A second process (a launch from the tray) or a reader beside a writer used to fail with
  // "database is locked", and startup offered to wipe the database for it.
  test('the database opens in WAL mode with a busy timeout', () async {
    final directory = await Directory.systemTemp.createTemp('hiddify-db-pragmas');
    addTearDown(() => directory.delete(recursive: true));
    final db = Db(NativeDatabase(File(p.join(directory.path, 'db.sqlite'))));
    addTearDown(db.close);

    final journal = await db.customSelect('PRAGMA journal_mode').getSingle();
    expect(journal.data['journal_mode'], 'wal');

    final busy = await db.customSelect('PRAGMA busy_timeout').getSingle();
    expect(busy.data.values.first, 5000);
  });
}
