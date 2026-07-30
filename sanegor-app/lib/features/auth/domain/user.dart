import 'package:flutter/foundation.dart';

enum UserRole {
  guest('guest', 'אורח'),
  user('user', 'משתמש'),
  lawyer('lawyer', 'עורך דין'),
  admin('admin', 'מנהל');

  const UserRole(this.key, this.label);

  final String key;
  final String label;

  static UserRole fromKey(String? key) =>
      UserRole.values.firstWhere((r) => r.key == key, orElse: () => UserRole.user);
}

@immutable
class AppUser {
  const AppUser({
    required this.id,
    required this.email,
    required this.fullName,
    required this.role,
    this.phone,
    this.provider = 'local',
    this.isEmailVerified = false,
    this.preferences = const {},
  });

  final String id;
  final String email;
  final String fullName;
  final UserRole role;
  final String? phone;
  final String provider;
  final bool isEmailVerified;
  final Map<String, dynamic> preferences;

  bool get isAdmin => role == UserRole.admin;

  /// Two-letter avatar initials, taken from the Hebrew name when present.
  String get initials {
    final parts = fullName.trim().split(RegExp(r'\s+'))
      ..removeWhere((part) => part.isEmpty);
    if (parts.isEmpty) {
      return email.isNotEmpty ? email[0].toUpperCase() : '?';
    }
    if (parts.length == 1) return _firstLetter(parts.first);
    return '${_firstLetter(parts.first)}${_firstLetter(parts[1])}';
  }

  /// First rune of [word] — correct for Hebrew and for surrogate pairs alike.
  static String _firstLetter(String word) =>
      word.isEmpty ? '' : String.fromCharCode(word.runes.first);

  factory AppUser.fromJson(Map<String, dynamic> json) => AppUser(
        id: (json['id'] ?? '').toString(),
        email: (json['email'] ?? '').toString(),
        fullName: (json['full_name'] ?? '').toString(),
        role: UserRole.fromKey(json['role']?.toString()),
        phone: json['phone']?.toString(),
        provider: (json['provider'] ?? 'local').toString(),
        isEmailVerified: json['is_email_verified'] == true,
        preferences: json['preferences'] is Map
            ? Map<String, dynamic>.from(json['preferences'] as Map)
            : const {},
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'email': email,
        'full_name': fullName,
        'role': role.key,
        'phone': phone,
        'provider': provider,
        'is_email_verified': isEmailVerified,
        'preferences': preferences,
      };
}
