import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class DevicesScreen extends StatefulWidget {
  final AppState state;
  const DevicesScreen({super.key, required this.state});
  @override
  State<DevicesScreen> createState() => _DevicesScreenState();
}

class _DevicesScreenState extends State<DevicesScreen> {
  List items = [];
  String error = '';

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final r = await widget.state.api.dio.get('/devices');
      if (mounted) {
        setState(() {
          items = r.data as List;
          error = '';
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = widget.state.api.mapError(e).message);
    }
  }

  Future<void> revoke(String id) async {
    try {
      await widget.state.api.dio.post('/devices/$id/revoke');
      await load();
    } catch (e) {
      if (mounted) setState(() => error = widget.state.api.mapError(e).message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return RefreshIndicator(
      onRefresh: load,
      child: ListView(children: [
        if (error.isNotEmpty)
          ListTile(
              title: Text(error,
                  style:
                      TextStyle(color: Theme.of(context).colorScheme.error))),
        if (error.isEmpty && items.isEmpty)
          ListTile(title: Text(t.t('noDevices'))),
        ...items.map((raw) {
          final x = Map<String, dynamic>.from(raw as Map);
          return ListTile(
            leading: const Icon(Icons.phone_android),
            title: Text((x['device_name'] ?? x['platform']).toString()),
            subtitle:
                Text('${x['app_version'] ?? ''} • ${x['os_version'] ?? ''}'),
            trailing: x['revoked_at'] == null
                ? IconButton(
                    tooltip: t.t('revokeDevice'),
                    icon: const Icon(Icons.block),
                    onPressed: () => revoke(x['id'].toString()),
                  )
                : Chip(label: Text(t.t('revoked'))),
          );
        }),
      ]),
    );
  }
}
