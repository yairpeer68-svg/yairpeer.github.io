import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class SecureTokenStore {
  static const _access = 'access_token';
  static const _refresh = 'refresh_token';
  static const _installation = 'installation_id';
  static const _serverDevice = 'server_device_id';
  final FlutterSecureStorage storage;
  const SecureTokenStore(this.storage);

  Future<String?> accessToken() => storage.read(key: _access);
  Future<String?> refreshToken() => storage.read(key: _refresh);
  Future<void> writeTokens(String access, String refresh) async {
    await storage.write(key: _access, value: access);
    await storage.write(key: _refresh, value: refresh);
  }

  Future<void> clearTokens() async {
    await storage.delete(key: _access);
    await storage.delete(key: _refresh);
  }

  Future<String?> installationId() => storage.read(key: _installation);
  Future<void> writeInstallationId(String value) =>
      storage.write(key: _installation, value: value);
  Future<String?> serverDeviceId() => storage.read(key: _serverDevice);
  Future<void> writeServerDeviceId(String value) =>
      storage.write(key: _serverDevice, value: value);
  Future<void> clearServerDeviceId() => storage.delete(key: _serverDevice);

  /// Non-secret UI settings share the same store so the app keeps a single
  /// persistence dependency.
  Future<String?> readSetting(String key) => storage.read(key: key);
  Future<void> writeSetting(String key, String value) =>
      storage.write(key: key, value: value);
}
