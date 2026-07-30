/// Build-time configuration.
///
/// Values come from `--dart-define`, so a release build never carries a
/// development host and no secret is compiled into the APK.
library;

class AppConfig {
  const AppConfig._();

  /// Base URL of the Sanegor backend.
  ///
  /// Defaults to the Android-emulator loopback alias so a fresh checkout runs
  /// against a locally started backend without extra flags.
  static const String apiBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8000',
  );

  static const String apiPrefix = '/api/v1';

  static String get apiUrl => '$apiBaseUrl$apiPrefix';

  /// WebSocket endpoint, derived from [apiBaseUrl] so only one value is set.
  static String get webSocketUrl {
    final scheme = apiBaseUrl.startsWith('https') ? 'wss' : 'ws';
    final host = apiBaseUrl.replaceFirst(RegExp('^https?://'), '');
    return '$scheme://$host$apiPrefix/ws/chat';
  }

  /// DeepSeek key baked in at build time via `--dart-define`.
  ///
  /// Convenience for a personal build so the key need not be typed on the
  /// device. It is supplied on the build command line and never committed —
  /// see `scripts/run-local.sh.example`. A key entered in the app's settings
  /// takes priority over this one.
  ///
  /// Do not use this for a build you distribute: `--dart-define` values are
  /// plain strings inside the APK and anyone can read them out.
  static const String bundledApiKey = String.fromEnvironment('DEEPSEEK_API_KEY');

  static const bool isProduction = bool.fromEnvironment('dart.vm.product');

  /// Network timeouts. Model responses are slow by nature, so the receive
  /// timeout is generous; connecting should still fail fast.
  static const Duration connectTimeout = Duration(seconds: 15);
  static const Duration receiveTimeout = Duration(seconds: 120);
  static const Duration streamTimeout = Duration(minutes: 5);

  static const int pageSize = 20;
  static const int maxUploadBytes = 25 * 1024 * 1024;
  static const List<String> allowedExtensions = [
    'pdf',
    'docx',
    'txt',
    'jpg',
    'jpeg',
    'png',
  ];

  /// Shown wherever the app presents generated legal content.
  static const String disclaimer =
      'המידע כאן הוא מידע משפטי כללי בלבד ואינו מהווה ייעוץ משפטי, חוות דעת '
      'או תחליף לייעוץ פרטני מעורך דין מוסמך.';

  static const String appName = 'סנגור';
  static const String appTagline = 'עוזר משפטי דיגיטלי';
}
