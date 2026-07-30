import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../../shared/widgets/states.dart';
import '../../search/presentation/search_screen.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final themeMode = ref.watch(themeModeProvider);
    final streaming = ref.watch(streamingEnabledProvider);
    final corpus = ref.watch(corpusStatsProvider);
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('הגדרות'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          _Section(
            title: 'תצוגה',
            child: Column(
              children: [
                RadioListTile<ThemeMode>(
                  value: ThemeMode.system,
                  groupValue: themeMode,
                  onChanged: (mode) =>
                      ref.read(themeModeProvider.notifier).set(mode!),
                  title: const Text('לפי הגדרות המכשיר'),
                  secondary: const Icon(Icons.brightness_auto_outlined),
                ),
                RadioListTile<ThemeMode>(
                  value: ThemeMode.light,
                  groupValue: themeMode,
                  onChanged: (mode) =>
                      ref.read(themeModeProvider.notifier).set(mode!),
                  title: const Text('מצב בהיר'),
                  secondary: const Icon(Icons.light_mode_outlined),
                ),
                RadioListTile<ThemeMode>(
                  value: ThemeMode.dark,
                  groupValue: themeMode,
                  onChanged: (mode) =>
                      ref.read(themeModeProvider.notifier).set(mode!),
                  title: const Text('מצב כהה'),
                  secondary: const Icon(Icons.dark_mode_outlined),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          _Section(
            title: 'תשובות',
            child: SwitchListTile(
              value: streaming,
              onChanged: (value) =>
                  ref.read(streamingEnabledProvider.notifier).set(value),
              title: const Text('תשובה מילה-אחר-מילה'),
              subtitle: const Text(
                'הצגת התשובה תוך כדי כתיבתה. כבה אם החיבור איטי.',
              ),
              secondary: const Icon(Icons.bolt_outlined),
            ),
          ),
          const SizedBox(height: 16),

          _Section(
            title: 'מאגר המקורות',
            child: corpus.when(
              loading: () => const ListTile(
                leading: Icon(Icons.library_books_outlined),
                title: Text('בודק…'),
              ),
              error: (_, __) => const ListTile(
                leading: Icon(Icons.library_books_outlined),
                title: Text('לא ניתן לבדוק את המאגר'),
              ),
              data: (stats) => ListTile(
                leading: Icon(
                  Icons.library_books_outlined,
                  color: stats.isEmpty ? theme.colorScheme.error : null,
                ),
                title: Text(
                  stats.isEmpty
                      ? 'המאגר ריק'
                      : '${stats.sources} מקורות · ${stats.chunks} קטעים',
                ),
                // A user is entitled to know when answers cannot be sourced.
                subtitle: Text(
                  stats.isEmpty
                      ? 'לא נטענו חקיקה ופסיקה. תשובות יינתנו ברמה עקרונית '
                          'וללא אסמכתאות.'
                      : 'תשובות מגובות באסמכתאות מהמאגר בלבד',
                ),
                isThreeLine: stats.isEmpty,
              ),
            ),
          ),
          const SizedBox(height: 16),

          _Section(
            title: 'אודות',
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.balance_outlined),
                  title: const Text('הבהרה משפטית'),
                  onTap: () => showDialog<void>(
                    context: context,
                    builder: (context) => AlertDialog(
                      title: const Text('הבהרה משפטית'),
                      content: SingleChildScrollView(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(AppConfig.disclaimer),
                            const SizedBox(height: 14),
                            const Text(
                              'המערכת מציגה אסמכתאות רק כאשר הן קיימות במאגר '
                              'המקורות שנטען לשרת. אם לא נמצאה אסמכתה, התשובה '
                              'תינתן ברמה עקרונית ותסומן ככזו — המערכת אינה '
                              'ממציאה חוקים, סעיפים או פסקי דין.',
                            ),
                            const SizedBox(height: 14),
                            const Text(
                              'לפני כל פעולה משפטית — הגשה, חתימה, ויתור או '
                              'עמידה במועד — יש להתייעץ עם עורך דין מוסמך.',
                            ),
                          ],
                        ),
                      ),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(),
                          child: const Text('סגירה'),
                        ),
                      ],
                    ),
                  ),
                ),
                ListTile(
                  leading: const Icon(Icons.privacy_tip_outlined),
                  title: const Text('פרטיות ואבטחה'),
                  onTap: () => showDialog<void>(
                    context: context,
                    builder: (context) => const AlertDialog(
                      title: Text('פרטיות ואבטחה'),
                      content: SingleChildScrollView(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text('• אסימוני ההתחברות נשמרים באחסון מוצפן במכשיר.'),
                            SizedBox(height: 8),
                            Text('• טקסט המסמכים שלך מוצפן במנוחה בשרת.'),
                            SizedBox(height: 8),
                            Text('• מחיקת מסמך מוחקת גם את הקובץ מהשרת.'),
                            SizedBox(height: 8),
                            Text('• כל התקשורת מוצפנת ב-HTTPS.'),
                            SizedBox(height: 8),
                            Text('• מסמכיך ושיחותיך נגישים לחשבונך בלבד.'),
                          ],
                        ),
                      ),
                      actions: [_CloseButton()],
                    ),
                  ),
                ),
                ListTile(
                  leading: const Icon(Icons.info_outline),
                  title: const Text('גרסה'),
                  trailing: const Text('1.0.0'),
                  onTap: () => showMessage(context, AppConfig.appName),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CloseButton extends StatelessWidget {
  const _CloseButton();

  @override
  Widget build(BuildContext context) => TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('סגירה'),
      );
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 4, bottom: 8),
            child: Text(title, style: Theme.of(context).textTheme.labelLarge),
          ),
          Card(child: child),
        ],
      );
}
