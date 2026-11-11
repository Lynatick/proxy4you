import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/db/db.dart';
import 'package:hiddify/features/profile/data/profile_data_source.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';

void main() {
  late Db db;
  late ProfileDao dao;

  setUp(() {
    db = Db(NativeDatabase.memory());
    dao = ProfileDao(db);
  });

  tearDown(() => db.close());

  Future<void> add(String id, String url) => dao.insert(
    ProfileEntriesCompanion.insert(
      id: id,
      type: ProfileType.remote,
      active: id == 'a',
      name: id,
      url: Value(url),
      lastUpdate: DateTime(2026),
    ),
  );

  // A refresh resolves the profile it updates by its URL. Substring matching
  // made `.../sub/abc` find `.../sub/abcdef`: the wrong profile was updated, and
  // the one the user added was never stored.
  test('a URL that is the start of another profile does not find it', () async {
    await add('a', 'https://panel.example/sub/abcdef');
    await add('b', 'https://panel.example/sub/abc');

    expect((await dao.getByUrl('https://panel.example/sub/abc'))?.id, 'b');
    expect(await dao.getByUrl('https://panel.example/sub/abcd'), isNull);
  });

  test('the case of a URL is part of it', () async {
    await add('a', 'https://panel.example/sub/AbCdEf');

    expect(await dao.getByUrl('https://panel.example/sub/abcdef'), isNull);
    expect((await dao.getByUrl('https://panel.example/sub/AbCdEf'))?.id, 'a');
  });
}
