import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class NotificationsScreen extends StatefulWidget {
  final AppState state;
  const NotificationsScreen({super.key, required this.state});
  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  List items = [];
  String error = '';

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final r = await widget.state.api.dio.get('/notifications');
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

  Future<void> read(String id) async {
    try {
      await widget.state.api.dio.post('/notifications/$id/read');
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
        if (error.isNotEmpty) ListTile(title: Text(error)),
        if (error.isEmpty && items.isEmpty)
          ListTile(title: Text(t.t('noNotifications'))),
        ...items.map((raw) {
          final x = Map<String, dynamic>.from(raw as Map);
          final unread = x['read_at'] == null;
          return ListTile(
            leading: Icon(unread
                ? Icons.notifications_active_outlined
                : Icons.notifications_none),
            title: Text(x['title'].toString()),
            subtitle: Text(x['body'].toString()),
            trailing: unread
                ? Text(t.t('markRead'),
                    style: Theme.of(context).textTheme.labelSmall)
                : null,
            onTap: unread ? () => read(x['id'].toString()) : null,
          );
        }),
      ]),
    );
  }
}
