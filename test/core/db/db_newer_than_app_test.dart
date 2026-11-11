import 'dart:io';

import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/db/db.dart';
import 'package:hiddify/core/db/db_newer_than_app.dart';
import 'package:path/path.dart' as p;
import 'package:sqlite3/sqlite3.dart';

void main() {
  test('the exception itself is recognized', () {
    expect(dbNewerThanAppError(const DbNewerThanAppException(99, 7))?.dbVersion, 99);
  });

  test('the text of the exception, as a remote error carries it, is recognized', () {
    final error = StateError('DbNewerThanAppException(db schema v99, app schema v7)');
    expect(dbNewerThanAppError(error)?.dbVersion, 99);
    expect(dbNewerThanAppError(error)?.appVersion, 7);
  });

  test('another failure is not taken for a newer database', () {
    expect(dbNewerThanAppError(StateError('database disk image is malformed')), isNull);
  });

  // The database of the app is opened in a background isolate: the migration runs there and
  // its error reaches the app wrapped, which is what the old `on DbNewerThanAppException`
  // never saw.
  test('a database from a newer version, opened in a background isolate, is recognized', () async {
    final directory = await Directory.systemTemp.createTemp('hiddify-db-newer');
    addTearDown(() => directory.delete(recursive: true));
    final file = File(p.join(directory.path, 'db.sqlite'));
    final raw = sqlite3.open(file.path);
    raw.execute('PRAGMA user_version = 99');
    raw.dispose();

    final db = Db(NativeDatabase.createBackgroundConnection(file));
    addTearDown(() async {
      try {
        await db.close();
      } catch (_) {}
    });

    Object? caught;
    try {
      await db.customSelect('SELECT 1').get();
    } catch (error) {
      caught = error;
    }

    expect(caught, isNotNull, reason: 'the database opened, and a newer one must be refused');
    expect(dbNewerThanAppError(caught!)?.dbVersion, 99);
  });
}
