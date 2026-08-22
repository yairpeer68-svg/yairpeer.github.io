import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class SecurityScreen extends StatefulWidget {
  final AppState state;
  const SecurityScreen({super.key, required this.state});
  @override
  State<SecurityScreen> createState() => _SecurityScreenState();
}

class _SecurityScreenState extends State<SecurityScreen> {
  String message = '';
  bool busy = false;

  Future<void> revokeAll() async {
    setState(() => busy = true);
    try {
      await widget.state.revokeAllSessions();
    } catch (e) {
      if (mounted) setState(() => message = e.toString());
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return ListView(padding: const EdgeInsets.all(20), children: [
      ListTile(
        leading: const Icon(Icons.lock),
        title: Text(t.t('secureStorage')),
        subtitle: Text(t.t('secureStorageBody')),
      ),
      ListTile(
        leading: const Icon(Icons.sync_lock),
        title: Text(t.t('refreshRotation')),
        subtitle: Text(t.t('refreshRotationBody')),
      ),
      FilledButton.tonalIcon(
        onPressed: busy ? null : revokeAll,
        icon: const Icon(Icons.logout),
        label: Text(t.t('revokeAll')),
      ),
      if (message.isNotEmpty)
        Padding(padding: const EdgeInsets.only(top: 12), child: Text(message)),
    ]);
  }
}
