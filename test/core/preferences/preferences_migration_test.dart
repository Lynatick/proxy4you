import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/model/environment.dart';
import 'package:hiddify/core/preferences/preferences_migration.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<SharedPreferences> preferences() {
    SharedPreferences.setMockInitialValues({'preferences_version': 0, 'keep-me': 'value'});
    return SharedPreferences.getInstance();
  }

  // The old fallback cleared the whole store, which also removed preferences_version: the next
  // launch made a new LAN sharing password and every saved share link stopped working.
  test('a failed migration leaves the stored preferences alone', () async {
    final store = await preferences();

    await migratePreferences(store, env: Environment.prod, migrate: () async => throw StateError('step failed'));

    expect(store.getString('keep-me'), 'value');
    expect(store.getInt('preferences_version'), 0);
  });

  test('a failed migration is raised while debugging', () async {
    final store = await preferences();

    await expectLater(
      migratePreferences(store, env: Environment.dev, migrate: () async => throw StateError('step failed')),
      throwsA(isA<StateError>()),
    );
    expect(store.getString('keep-me'), 'value');
  });

  test('the migration runs when it can', () async {
    final store = await preferences();
    var ran = false;

    await migratePreferences(store, env: Environment.prod, migrate: () async => ran = true);

    expect(ran, isTrue);
  });
}
