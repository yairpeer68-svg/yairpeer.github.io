import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/providers.dart';
import '../data/auth_repository.dart';
import '../domain/user.dart';

/// Session lifecycle states the router keys off.
enum AuthStatus { unknown, authenticated, unauthenticated }

class AuthState {
  const AuthState({
    this.status = AuthStatus.unknown,
    this.user,
    this.isSubmitting = false,
    this.error,
    this.fieldErrors = const {},
  });

  final AuthStatus status;
  final AppUser? user;
  final bool isSubmitting;
  final String? error;
  final Map<String, String> fieldErrors;

  bool get isAuthenticated => status == AuthStatus.authenticated;
  bool get isResolved => status != AuthStatus.unknown;

  AuthState copyWith({
    AuthStatus? status,
    AppUser? user,
    bool? isSubmitting,
    String? error,
    Map<String, String>? fieldErrors,
    bool clearError = false,
    bool clearUser = false,
  }) =>
      AuthState(
        status: status ?? this.status,
        user: clearUser ? null : (user ?? this.user),
        isSubmitting: isSubmitting ?? this.isSubmitting,
        error: clearError ? null : (error ?? this.error),
        fieldErrors: fieldErrors ?? (clearError ? const {} : this.fieldErrors),
      );
}

class AuthController extends StateNotifier<AuthState> {
  AuthController(this._repository) : super(const AuthState()) {
    // Restore a session on construction so the splash screen has something to
    // wait for rather than guessing a delay.
    unawaited(restore());
  }

  final AuthRepository _repository;

  Future<void> restore() async {
    try {
      final user = await _repository.restore();
      state = user == null
          ? const AuthState(status: AuthStatus.unauthenticated)
          : AuthState(status: AuthStatus.authenticated, user: user);
    } on Object {
      state = const AuthState(status: AuthStatus.unauthenticated);
    }
  }

  Future<bool> login({required String email, required String password}) =>
      _submit(() => _repository.login(email: email, password: password));

  Future<bool> register({
    required String email,
    required String password,
    required String fullName,
    String? phone,
  }) =>
      _submit(
        () => _repository.register(
          email: email,
          password: password,
          fullName: fullName,
          phone: phone,
        ),
      );

  Future<bool> _submit(Future<AppUser> Function() action) async {
    state = state.copyWith(isSubmitting: true, clearError: true);
    try {
      final user = await action();
      state = AuthState(status: AuthStatus.authenticated, user: user);
      return true;
    } on ApiException catch (error) {
      state = state.copyWith(
        isSubmitting: false,
        error: error.message,
        fieldErrors: error.fieldErrors,
      );
      return false;
    } on Object {
      state = state.copyWith(
        isSubmitting: false,
        error: 'אירעה שגיאה בלתי צפויה',
      );
      return false;
    }
  }

  Future<void> logout({bool allDevices = false}) async {
    await _repository.logout(allDevices: allDevices);
    state = const AuthState(status: AuthStatus.unauthenticated);
  }

  /// Called by the API client when a refresh fails irrecoverably.
  void handleSessionExpired() {
    if (state.status == AuthStatus.unauthenticated) return;
    state = const AuthState(
      status: AuthStatus.unauthenticated,
      error: 'תוקף ההתחברות פג. יש להתחבר מחדש',
    );
  }

  Future<bool> requestPasswordReset(String email) async {
    state = state.copyWith(isSubmitting: true, clearError: true);
    try {
      await _repository.requestPasswordReset(email);
      state = state.copyWith(isSubmitting: false);
      return true;
    } on ApiException catch (error) {
      state = state.copyWith(isSubmitting: false, error: error.message);
      return false;
    }
  }

  Future<bool> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    state = state.copyWith(isSubmitting: true, clearError: true);
    try {
      await _repository.changePassword(
        currentPassword: currentPassword,
        newPassword: newPassword,
      );
      // The backend revokes every session on a password change, so the user
      // must sign in again — reflect that rather than pretending otherwise.
      state = const AuthState(status: AuthStatus.unauthenticated);
      return true;
    } on ApiException catch (error) {
      state = state.copyWith(isSubmitting: false, error: error.message);
      return false;
    }
  }

  Future<void> updateProfile({
    String? fullName,
    String? phone,
    Map<String, dynamic>? preferences,
  }) async {
    state = state.copyWith(isSubmitting: true, clearError: true);
    try {
      final user = await _repository.updateProfile(
        fullName: fullName,
        phone: phone,
        preferences: preferences,
      );
      state = state.copyWith(user: user, isSubmitting: false);
    } on ApiException catch (error) {
      state = state.copyWith(isSubmitting: false, error: error.message);
    }
  }

  void clearError() => state = state.copyWith(clearError: true);
}

final authControllerProvider =
    StateNotifierProvider<AuthController, AuthState>(
  (ref) => AuthController(ref.watch(authRepositoryProvider)),
);
