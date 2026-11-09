import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/localization/locale_preferences.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/gen/translations_ru.g.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('saved foreign locale cannot change the Russian interface', () async {
    SharedPreferences.setMockInitialValues({'locale': 'en'});
    final container = ProviderContainer();
    addTearDown(container.dispose);
    final preferences = await container.read(sharedPreferencesProvider.future);

    expect(container.read(localePreferencesProvider), AppLocale.ru);
    final translations = await container.read(translationsProvider.future);
    expect(translations, isA<TranslationsRu>());
    expect(translations.connection.waiting, 'в поисках света ...');
    expect(translations.connection.connected, 'Да будет свет');

    await container.read(localePreferencesProvider.notifier).changeLocale(AppLocale.en);
    expect(container.read(localePreferencesProvider), AppLocale.ru);
    expect(preferences.getString('locale'), AppLocale.ru.name);
  });
}
