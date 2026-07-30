import '../../../core/network/api_client.dart';
import '../../../core/storage/secure_store.dart';
import '../domain/user.dart';

/// Authentication and profile operations.
class AuthRepository {
  const AuthRepository({required ApiClient client, required SecureStore store})
      : _client = client,
        _store = store;

  final ApiClient _client;
  final SecureStore _store;

  Future<AppUser> register({
    required String email,
    required String password,
    required String fullName,
    String? phone,
  }) async {
    final response = await _client.post(
      '/auth/register',
      skipAuth: true,
      body: {
        'email': email.trim(),
        'password': password,
        'full_name': fullName.trim(),
        if (phone != null && phone.trim().isNotEmpty) 'phone': phone.trim(),
      },
    );
    return _persistSession(response);
  }

  Future<AppUser> login({
    required String email,
    required String password,
  }) async {
    final response = await _client.post(
      '/auth/login',
      skipAuth: true,
      body: {'email': email.trim(), 'password': password},
    );
    return _persistSession(response);
  }

  /// Restore a session from secure storage.
  ///
  /// The cached user is returned immediately so the UI can render, and the
  /// server is consulted to confirm the session is still valid.
  Future<AppUser?> restore() async {
    if (!await _store.hasSession) return null;
    try {
      final response = await _client.get('/auth/me');
      final user = AppUser.fromJson(response);
      await _store.saveUser(user.toJson());
      return user;
    } on Object {
      // The interceptor already tried to refresh; a failure here means the
      // session is genuinely gone.
      final cached = await _store.readUser();
      return cached == null ? null : AppUser.fromJson(cached);
    }
  }

  Future<void> logout({bool allDevices = false}) async {
    try {
      final refreshToken = await _store.readRefreshToken();
      await _client.post(
        '/auth/logout',
        body: {
          if (refreshToken != null) 'refresh_token': refreshToken,
          'all_devices': allDevices,
        },
      );
    } on Object {
      // Local credentials are cleared regardless — a failed server call must
      // not leave the user logged in on the device.
    } finally {
      await _store.clear();
    }
  }

  Future<void> requestPasswordReset(String email) => _client.post(
        '/auth/forgot-password',
        skipAuth: true,
        body: {'email': email.trim()},
      );

  Future<void> resetPassword({
    required String token,
    required String newPassword,
  }) =>
      _client.post(
        '/auth/reset-password',
        skipAuth: true,
        body: {'token': token, 'new_password': newPassword},
      );

  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) =>
      _client.post(
        '/auth/change-password',
        body: {
          'current_password': currentPassword,
          'new_password': newPassword,
        },
      );

  Future<AppUser> verifyEmail(String token) async {
    final response = await _client.post(
      '/auth/verify-email',
      skipAuth: true,
      body: {'token': token},
    );
    final user = AppUser.fromJson(response);
    await _store.saveUser(user.toJson());
    return user;
  }

  Future<AppUser> updateProfile({
    String? fullName,
    String? phone,
    Map<String, dynamic>? preferences,
  }) async {
    final response = await _client.patch(
      '/auth/me',
      body: {
        if (fullName != null) 'full_name': fullName,
        if (phone != null) 'phone': phone,
        if (preferences != null) 'preferences': preferences,
      },
    );
    final user = AppUser.fromJson(response);
    await _store.saveUser(user.toJson());
    return user;
  }

  Future<AppUser> _persistSession(Map<String, dynamic> response) async {
    final tokens = response['tokens'] as Map<String, dynamic>;
    await _store.saveTokens(
      accessToken: tokens['access_token'] as String,
      refreshToken: tokens['refresh_token'] as String,
    );
    final user = AppUser.fromJson(response['user'] as Map<String, dynamic>);
    await _store.saveUser(user.toJson());
    return user;
  }
}
