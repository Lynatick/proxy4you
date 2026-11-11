import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/utils/preferences_utils.dart';
import 'package:logging/logging.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('a preference is saved, and its value never reaches the log', () async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    final entry = PreferencesEntry<String, String>(
      preferences: preferences,
      key: 'lan_sharing_password',
      defaultValue: '',
    );

    final previousLevel = Logger.root.level;
    Logger.root.level = Level.ALL;
    final messages = <String>[];
    final subscription = Logger.root.onRecord.listen((record) => messages.add(record.message));
    addTearDown(() async {
      await subscription.cancel();
      Logger.root.level = previousLevel;
    });

    expect(await entry.write('s3cret-value'), isTrue);

    expect(preferences.getString('lan_sharing_password'), 's3cret-value');
    expect(messages.any((message) => message.contains('lan_sharing_password')), isTrue, reason: 'the key is logged');
    expect(messages.any((message) => message.contains('s3cret-value')), isFalse, reason: 'the value is not logged');
  });
}
