import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class HomeScreen extends StatelessWidget {
  final AppState state;
  const HomeScreen({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    final name = state.user?['display_name'];
    return ListView(padding: const EdgeInsets.all(20), children: [
      Text(name != null ? '${t.t('greeting')}, $name' : t.t('greeting'),
          style: Theme.of(context).textTheme.headlineMedium),
      const SizedBox(height: 16),
      Wrap(spacing: 12, runSpacing: 12, children: [
        _card(context, Icons.smart_toy_outlined, t.t('aiCardTitle'),
            t.t('aiCardBody')),
        _card(
            context, Icons.security, t.t('security'), t.t('securityCardBody')),
        _card(
            context, Icons.data_usage, t.t('privacy'), t.t('privacyCardBody')),
      ]),
    ]);
  }

  Widget _card(
          BuildContext context, IconData icon, String title, String text) =>
      SizedBox(
        width: 280,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child:
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(icon),
              const SizedBox(height: 8),
              Text(title, style: Theme.of(context).textTheme.titleLarge),
              Text(text),
            ]),
          ),
        ),
      );
}
