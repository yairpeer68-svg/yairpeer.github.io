import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class HistoryScreen extends StatefulWidget {
  final AppState state;
  const HistoryScreen({super.key, required this.state});
  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  List items = [];
  String error = '';

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    try {
      final r = await widget.state.api.dio.get('/ai/history?limit=50');
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

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    if (error.isNotEmpty) return Center(child: Text(error));
    if (items.isEmpty) return Center(child: Text(t.t('noHistory')));
    return RefreshIndicator(
      onRefresh: load,
      child: ListView.builder(
        itemCount: items.length,
        itemBuilder: (context, i) {
          final x = Map<String, dynamic>.from(items[i] as Map);
          final cached = x['cache_hit'] == true ? ' • ${t.t('cache')}' : '';
          return ListTile(
            leading: Icon(x['status'] == 'success'
                ? Icons.check_circle_outline
                : Icons.error_outline),
            title: Text('${x['model']} • ${x['status']}'),
            subtitle: Text(
                '${x['created_at']} • ${x['total_tokens']} ${t.t('tokens')}$cached'),
          );
        },
      ),
    );
  }
}
