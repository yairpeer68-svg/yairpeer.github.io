import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class ProfileScreen extends StatelessWidget {
  final AppState state;
  const ProfileScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    final u = state.user ?? {};
    final initial = (u['display_name'] ?? u['email'] ?? '?').toString();
    return ListView(padding: const EdgeInsets.all(20), children: [
      CircleAvatar(
        radius: 38,
        child: Text(initial.characters.first.toUpperCase()),
      ),
      const SizedBox(height: 16),
      ListTile(
          title: Text(t.t('email')),
          subtitle: Text((u['email'] ?? '').toString())),
      ListTile(
          title: Text(t.t('displayName')),
          subtitle: Text((u['display_name'] ?? '—').toString())),
      ListTile(
        title: Text(t.t('emailVerification')),
        subtitle: Text(u['email_verified_at'] == null
            ? t.t('notVerified')
            : t.t('verified')),
      ),
    ]);
  }
}
