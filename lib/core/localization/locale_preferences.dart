import 'package:hiddify/gen/translations.g.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'locale_preferences.g.dart';

@Riverpod(keepAlive: true)
class LocalePreferences extends _$LocalePreferences {
  @override
  AppLocale build() {
    // This build speaks Russian only: the interface is not translated at runtime and the system
    // locale is ignored, so the stored or device language can not bring another one back.
    return AppLocale.ru;
  }

  Future<void> changeLocale(AppLocale value) async {
    state = AppLocale.ru;
  }
}
