import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../features/auth/data/auth_repository.dart';
import '../features/auth/presentation/auth_controller.dart';
import '../features/chat/data/chat_repository.dart';
import '../features/documents/data/documents_repository.dart';
import '../features/drafting/data/drafting_repository.dart';
import '../features/search/data/search_repository.dart';
import 'network/api_client.dart';
import 'network/sse_client.dart';
import 'storage/secure_store.dart';

/// Root providers: infrastructure and repositories.
///
/// [sharedPreferencesProvider] is overridden in `main` with the instance
/// resolved during start-up, so every consumer reads settings synchronously.

final sharedPreferencesProvider = Provider<SharedPreferences>(
  (ref) => throw UnimplementedError('override in main()'),
);

final secureStoreProvider = Provider<SecureStore>((ref) => SecureStore());

final apiClientProvider = Provider<ApiClient>((ref) {
  final client = ApiClient(store: ref.watch(secureStoreProvider));
  client.onSessionExpired = () {
    // Drop the session; the router redirect then sends the user to login.
    ref.read(authControllerProvider.notifier).handleSessionExpired();
  };
  return client;
});

final sseClientProvider = Provider<SseClient>(
  (ref) => SseClient(store: ref.watch(secureStoreProvider)),
);

final authRepositoryProvider = Provider<AuthRepository>(
  (ref) => AuthRepository(
    client: ref.watch(apiClientProvider),
    store: ref.watch(secureStoreProvider),
  ),
);

final chatRepositoryProvider = Provider<ChatRepository>(
  (ref) => ChatRepository(
    client: ref.watch(apiClientProvider),
    sse: ref.watch(sseClientProvider),
  ),
);

final documentsRepositoryProvider = Provider<DocumentsRepository>(
  (ref) => DocumentsRepository(client: ref.watch(apiClientProvider)),
);

final draftingRepositoryProvider = Provider<DraftingRepository>(
  (ref) => DraftingRepository(client: ref.watch(apiClientProvider)),
);

final searchRepositoryProvider = Provider<SearchRepository>(
  (ref) => SearchRepository(client: ref.watch(apiClientProvider)),
);

// --------------------------------------------------------------- preferences
/// Theme mode, persisted locally.
class ThemeModeController extends StateNotifier<ThemeMode> {
  ThemeModeController(this._prefs) : super(_read(_prefs));

  final SharedPreferences _prefs;
  static const _key = 'theme_mode';

  static ThemeMode _read(SharedPreferences prefs) => switch (prefs.getString(_key)) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      };

  Future<void> set(ThemeMode mode) async {
    state = mode;
    await _prefs.setString(_key, mode.name);
  }
}

final themeModeProvider =
    StateNotifierProvider<ThemeModeController, ThemeMode>(
  (ref) => ThemeModeController(ref.watch(sharedPreferencesProvider)),
);

/// Whether answers stream token-by-token. Off is useful on a poor connection.
class StreamingPreference extends StateNotifier<bool> {
  StreamingPreference(this._prefs) : super(_prefs.getBool(_key) ?? true);

  final SharedPreferences _prefs;
  static const _key = 'streaming_enabled';

  Future<void> set(bool value) async {
    state = value;
    await _prefs.setBool(_key, value);
  }
}

final streamingEnabledProvider =
    StateNotifierProvider<StreamingPreference, bool>(
  (ref) => StreamingPreference(ref.watch(sharedPreferencesProvider)),
);
