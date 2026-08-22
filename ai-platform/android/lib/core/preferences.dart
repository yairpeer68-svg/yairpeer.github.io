import 'storage.dart';

/// Persisted UI preferences.
///
/// Theme and language used to live only in memory, so every cold start reset the
/// user's choice back to the Hebrew/light defaults.
class Preferences {
  static const _dark = 'pref_dark_mode';
  static const _language = 'pref_language';
  static const supportedLanguages = {'he', 'en'};

  final SecureTokenStore store;
  const Preferences(this.store);

  Future<bool> darkMode() async => (await store.readSetting(_dark)) == '1';
  Future<void> setDarkMode(bool value) =>
      store.writeSetting(_dark, value ? '1' : '0');

  Future<String> language() async {
    final value = await store.readSetting(_language);
    return supportedLanguages.contains(value) ? value! : 'he';
  }

  Future<void> setLanguage(String value) async {
    if (!supportedLanguages.contains(value)) return;
    await store.writeSetting(_language, value);
  }
}
