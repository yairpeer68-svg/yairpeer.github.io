import 'storage.dart';

/// Server connection settings.
///
/// The base URL is resolved at **runtime**, not baked in at build time. One APK has to
/// work against whichever VPS the operator deployed, and a compile-time-only URL made
/// every distributed build useless to anyone but the person who compiled it. The
/// `API_BASE_URL` dart-define remains the default, so an operator who builds their own
/// APK can still pin it and skip the setup screen.
class AppConfig {
  static const _serverKey = 'server_base_url';

  /// Compile-time default. `--dart-define=API_BASE_URL=https://api.example.com/api/v1`
  static const defaultApiBaseUrl = String.fromEnvironment('API_BASE_URL',
      defaultValue: 'http://10.0.2.2:8080/api/v1');

  /// When true the app opens the server setup screen before sign-in.
  static const requireRuntimeServer =
      bool.fromEnvironment('REQUIRE_RUNTIME_SERVER', defaultValue: true);

  static const pinningEnabled =
      bool.fromEnvironment('CERT_PINNING_ENABLED', defaultValue: false);
  static const currentPin =
      String.fromEnvironment('CERT_PIN_SHA256_CURRENT', defaultValue: '');
  static const nextPin =
      String.fromEnvironment('CERT_PIN_SHA256_NEXT', defaultValue: '');

  /// A build whose default still points at the emulator loopback has no real server.
  static bool get hasBuiltInServer =>
      !defaultApiBaseUrl.contains('10.0.2.2') &&
      !defaultApiBaseUrl.contains('example.invalid');

  static Future<String?> storedBaseUrl(SecureTokenStore store) =>
      store.readSetting(_serverKey);

  static Future<void> saveBaseUrl(SecureTokenStore store, String value) =>
      store.writeSetting(_serverKey, normalize(value));

  static Future<void> clearBaseUrl(SecureTokenStore store) =>
      store.writeSetting(_serverKey, '');

  /// Stored value first, then the compile-time default.
  static Future<String> resolveBaseUrl(SecureTokenStore store) async {
    final stored = await storedBaseUrl(store);
    if (stored != null && stored.isNotEmpty) return stored;
    return defaultApiBaseUrl;
  }

  /// Accepts `example.com`, `https://example.com` or a full API path and returns the
  /// canonical `https://host/api/v1` form.
  static String normalize(String raw) {
    var value = raw.trim();
    if (value.isEmpty) return value;
    if (!value.contains('://')) value = 'https://$value';
    value = value.replaceAll(RegExp(r'/+$'), '');
    if (!value.endsWith('/api/v1')) value = '$value/api/v1';
    return value;
  }

  /// Returns null when valid, otherwise a message key for the UI.
  static String? validationError(String raw) {
    final value = normalize(raw);
    final uri = Uri.tryParse(value);
    if (uri == null || !uri.hasScheme || !uri.hasAuthority) {
      return 'serverInvalid';
    }
    // The release manifest sets usesCleartextTraffic=false, so an http:// address
    // cannot connect at all. Failing here explains why instead of timing out later.
    if (uri.scheme != 'https') return 'serverNeedsHttps';
    return null;
  }

  static void validate() {
    final uri = Uri.tryParse(defaultApiBaseUrl);
    if (uri == null || !uri.hasScheme || !uri.hasAuthority) {
      throw StateError('API_BASE_URL is invalid');
    }
    if (pinningEnabled && currentPin.isEmpty && nextPin.isEmpty) {
      throw StateError('Certificate pinning enabled without a configured pin');
    }
  }
}
