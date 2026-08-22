import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/app_info.dart';
import '../core/i18n.dart';

class VersionGate extends StatefulWidget {
  final AppState state;
  final Widget child;
  const VersionGate({super.key, required this.state, required this.child});
  @override
  State<VersionGate> createState() => _VersionGateState();
}

class _VersionGateState extends State<VersionGate> {
  bool checking = true, blocked = false;
  String? url;

  @override
  void initState() {
    super.initState();
    check();
  }

  Future<void> check() async {
    try {
      final r = await widget.state.api.dio
          .get('/system/app-version?platform=android');
      final d = Map<String, dynamic>.from(r.data as Map);
      if (d['configured'] == true) {
        // AppInfo.version is stamped from pubspec at build time; the previously
        // hard-coded literal drifted and could block an already-current client.
        const current = AppInfo.version;
        final min = d['minimum_supported_version'] as String?;
        final latest = d['latest_version'] as String?;
        final force = d['force_update'] == true;
        final belowMin = min != null && AppInfo.compare(current, min) < 0;
        final forcedBehind =
            force && latest != null && AppInfo.compare(current, latest) < 0;
        if (belowMin || forcedBehind) {
          blocked = true;
          url = (d['store_url'] ?? d['download_url']) as String?;
        }
      }
    } catch (_) {
      // App remains usable; backend health handles outages.
    } finally {
      if (mounted) setState(() => checking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!blocked) return widget.child;
    final t = Strings.of(context);
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            const Icon(Icons.system_update, size: 72),
            const SizedBox(height: 16),
            Text(t.t('mandatoryUpdate'),
                style: Theme.of(context).textTheme.headlineMedium),
            const SizedBox(height: 12),
            Text(t.t('updateMessage'), textAlign: TextAlign.center),
            const SizedBox(height: 8),
            Text('${t.t('currentVersion')}: ${AppInfo.displayVersion}',
                style: Theme.of(context).textTheme.bodySmall),
            if (url != null) ...[
              const SizedBox(height: 16),
              SelectableText(url!)
            ],
          ]),
        ),
      ),
    );
  }
}
