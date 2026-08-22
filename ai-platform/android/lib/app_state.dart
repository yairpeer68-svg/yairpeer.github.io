import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'core/api_client.dart';
import 'core/app_info.dart';
import 'core/config.dart';
import 'core/preferences.dart';
import 'core/storage.dart';

class AppState extends ChangeNotifier {
  final ApiClient api;
  final SecureTokenStore store;
  final Preferences preferences;
  bool ready = false;
  bool authenticated = false;
  bool serverConfigured = false;
  String serverBaseUrl = AppConfig.defaultApiBaseUrl;
  bool darkMode = false;
  String language = 'he';
  Map<String, dynamic>? user;

  AppState(this.api, this.store) : preferences = Preferences(store) {
    api.onAuthenticationLost = () {
      authenticated = false;
      user = null;
      notifyListeners();
    };
  }

  Future<void> initialize() async {
    darkMode = await preferences.darkMode();
    language = await preferences.language();

    // Resolve the server before any request: a stored address wins over the build-time
    // default, so one APK can serve any deployment.
    serverBaseUrl = await AppConfig.resolveBaseUrl(store);
    api.updateBaseUrl(serverBaseUrl);
    final stored = await AppConfig.storedBaseUrl(store);
    serverConfigured =
        (stored != null && stored.isNotEmpty) || AppConfig.hasBuiltInServer;
    if (!serverConfigured) {
      ready = true;
      notifyListeners();
      return;
    }

    final token = await store.accessToken();
    if (token != null) {
      try {
        final r = await api.dio.get('/users/me');
        user = Map<String, dynamic>.from(r.data as Map);
        authenticated = true;
        api.resetAuthenticationLatch();
        await _registerDevice();
      } catch (_) {
        await store.clearTokens();
      }
    }
    ready = true;
    notifyListeners();
  }

  Future<void> login(String email, String password) async {
    try {
      final knownDevice = await store.serverDeviceId();
      final r = await api.refreshDio.post('/auth/login', data: {
        'email': email,
        'password': password,
        if (knownDevice != null) 'device_id': knownDevice
      });
      final d = Map<String, dynamic>.from(r.data as Map);
      await store.writeTokens(
          d['access_token'] as String, d['refresh_token'] as String);
      final me = await api.dio.get('/users/me');
      user = Map<String, dynamic>.from(me.data as Map);
      authenticated = true;
      api.resetAuthenticationLatch();
      await _registerDevice();
      notifyListeners();
    } catch (e) {
      throw api.mapError(e);
    }
  }

  Future<void> register(String email, String password, String? name) async {
    try {
      await api.refreshDio.post('/auth/register',
          data: {'email': email, 'password': password, 'display_name': name});
      await login(email, password);
    } catch (e) {
      throw api.mapError(e);
    }
  }

  Future<void> _registerDevice() async {
    try {
      final installationId = await api.installation.get();
      final r = await api.dio.post('/devices/register', data: {
        'device_id': installationId,
        'installation_id': installationId,
        'platform': 'android',
        'device_name': 'Android device',
        'app_version': AppInfo.version,
        'os_version': Platform.operatingSystemVersion,
      });
      final d = Map<String, dynamic>.from(r.data as Map);
      final serverId = d['id'] as String?;
      if (serverId != null) await store.writeServerDeviceId(serverId);
    } catch (_) {
      // Device registration failure is non-fatal for sign-in; the Devices screen exposes the state.
    }
  }

  Future<void> revokeAllSessions() async {
    try {
      await api.dio.post('/auth/revoke-all');
    } catch (e) {
      throw api.mapError(e);
    }
    await store.clearTokens();
    authenticated = false;
    user = null;
    notifyListeners();
  }

  Future<void> logout() async {
    final refresh = await store.refreshToken();
    try {
      if (refresh != null) {
        await api.dio.post('/auth/logout', data: {'refresh_token': refresh});
      }
    } catch (_) {}
    await store.clearTokens();
    authenticated = false;
    user = null;
    notifyListeners();
  }

  Future<void> setDark(bool value) async {
    darkMode = value;
    notifyListeners();
    await preferences.setDarkMode(value);
  }

  /// Persist a new server address and retarget the client.
  Future<void> setServer(String raw) async {
    final value = AppConfig.normalize(raw);
    await AppConfig.saveBaseUrl(store, value);
    serverBaseUrl = value;
    api.updateBaseUrl(value);
    serverConfigured = true;
    notifyListeners();
  }

  /// Forget the server and every credential bound to it.
  Future<void> resetServer() async {
    await store.clearTokens();
    await store.clearServerDeviceId();
    await AppConfig.clearBaseUrl(store);
    serverBaseUrl = AppConfig.defaultApiBaseUrl;
    api.updateBaseUrl(serverBaseUrl);
    serverConfigured = AppConfig.hasBuiltInServer;
    authenticated = false;
    user = null;
    notifyListeners();
  }

  /// Probe a candidate server before saving it.
  Future<String> probeServer(String raw) async {
    final value = AppConfig.normalize(raw);
    final origin = value.replaceAll(RegExp(r'/api/v1$'), '');
    final probe = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
    ));
    try {
      final r = await probe.get<dynamic>('$origin/version');
      final data = Map<String, dynamic>.from(r.data as Map);
      return (data['version'] ?? 'unknown').toString();
    } finally {
      probe.close(force: true);
    }
  }

  Future<void> setLanguage(String value) async {
    if (!Preferences.supportedLanguages.contains(value)) return;
    language = value;
    notifyListeners();
    await preferences.setLanguage(value);
  }
}
