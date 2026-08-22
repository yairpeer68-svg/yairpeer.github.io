import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';
import 'server_setup.dart';

class SettingsScreen extends StatelessWidget {
  final AppState state;
  const SettingsScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return ListView(children: [
      SwitchListTile(
        value: state.darkMode,
        onChanged: state.setDark,
        title: Text(t.t('darkMode')),
      ),
      ListTile(
        title: Text(t.t('language')),
        trailing: DropdownButton<String>(
          value: state.language,
          // Language names stay in their own language by convention.
          items: const [
            DropdownMenuItem(value: 'he', child: Text('עברית')),
            DropdownMenuItem(value: 'en', child: Text('English')),
          ],
          onChanged: (v) {
            if (v != null) state.setLanguage(v);
          },
        ),
      ),
      const Divider(),
      ListTile(
        leading: const Icon(Icons.dns_outlined),
        title: Text(t.t('serverAddress')),
        subtitle: Text(state.serverBaseUrl, textDirection: TextDirection.ltr),
        trailing: const Icon(Icons.edit_outlined),
        onTap: () => Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => ServerSetupScreen(state: state, allowCancel: true),
          ),
        ),
      ),
      ListTile(
        leading: const Icon(Icons.link_off),
        title: Text(t.t('serverReset')),
        onTap: () => _confirmReset(context, t),
      ),
      const Divider(),
      ListTile(
          title: Text(t.t('certPinning')),
          subtitle: Text(t.t('certPinningBody'))),
    ]);
  }

  Future<void> _confirmReset(BuildContext context, Strings t) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(t.t('serverReset')),
        content: Text(t.t('serverResetWarning')),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: Text(t.t('cancel')),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: Text(t.t('serverReset')),
          ),
        ],
      ),
    );
    // Clearing the server also drops the credentials issued by it.
    if (confirmed == true) await state.resetServer();
  }
}
