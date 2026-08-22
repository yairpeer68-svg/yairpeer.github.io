import 'package:flutter/material.dart';
import '../core/app_info.dart';
import '../core/i18n.dart';

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return ListView(padding: const EdgeInsets.all(24), children: [
      Text(t.t('app'), style: Theme.of(context).textTheme.headlineMedium),
      const SizedBox(height: 8),
      Text('${t.t('currentVersion')}: ${AppInfo.displayVersion}'),
      const SizedBox(height: 20),
      Text(t.t('aboutBody')),
    ]);
  }
}
