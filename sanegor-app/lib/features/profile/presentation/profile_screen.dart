import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/config/app_config.dart';
import '../../../core/router/app_router.dart';
import '../../../shared/widgets/disclaimer_banner.dart';
import '../../../shared/widgets/states.dart';
import '../../auth/presentation/auth_controller.dart';

class ProfileScreen extends ConsumerWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider);
    final user = auth.user;
    final theme = Theme.of(context);

    if (user == null) return const LoadingState();

    return Scaffold(
      appBar: AppBar(
        title: const Text('פרופיל'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: 'הגדרות',
            onPressed: () => context.pushNamed(Routes.settings),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Row(
                children: [
                  CircleAvatar(
                    radius: 32,
                    backgroundColor: theme.colorScheme.primaryContainer,
                    child: Text(
                      user.initials,
                      style: theme.textTheme.titleLarge?.copyWith(
                        color: theme.colorScheme.onPrimaryContainer,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          user.fullName.isEmpty ? 'משתמש' : user.fullName,
                          style: theme.textTheme.titleMedium,
                        ),
                        const SizedBox(height: 2),
                        Text(
                          user.email,
                          style: theme.textTheme.bodySmall,
                          textDirection: TextDirection.ltr,
                        ),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 6,
                          children: [
                            Chip(
                              label: Text(user.role.label),
                              visualDensity: VisualDensity.compact,
                              padding: EdgeInsets.zero,
                            ),
                            if (!user.isEmailVerified)
                              Chip(
                                avatar: Icon(
                                  Icons.warning_amber_outlined,
                                  size: 14,
                                  color: theme.colorScheme.error,
                                ),
                                label: const Text('דוא״ל לא אומת'),
                                visualDensity: VisualDensity.compact,
                                padding: EdgeInsets.zero,
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),

          _Group(
            title: 'הכלים שלי',
            children: [
              _Tile(
                icon: Icons.description_outlined,
                title: 'יצירת חוזה',
                subtitle: '9 סוגי חוזים',
                onTap: () => context.pushNamed(Routes.contracts),
              ),
              _Tile(
                icon: Icons.mail_outline,
                title: 'מכתבים משפטיים',
                subtitle: '10 סוגי מכתבים ומסמכים',
                onTap: () => context.pushNamed(Routes.letters),
              ),
              _Tile(
                icon: Icons.folder_outlined,
                title: 'המסמכים שלי',
                onTap: () => context.goNamed(Routes.documents),
              ),
              _Tile(
                icon: Icons.history,
                title: 'היסטוריית שיחות',
                onTap: () => context.goNamed(Routes.history),
              ),
            ],
          ),
          const SizedBox(height: 16),

          _Group(
            title: 'חשבון',
            children: [
              _Tile(
                icon: Icons.person_outline,
                title: 'עריכת פרטים',
                onTap: () => _editProfile(context, ref),
              ),
              _Tile(
                icon: Icons.lock_outline,
                title: 'שינוי סיסמה',
                onTap: () => _changePassword(context, ref),
              ),
              _Tile(
                icon: Icons.settings_outlined,
                title: 'הגדרות',
                onTap: () => context.pushNamed(Routes.settings),
              ),
              _Tile(
                icon: Icons.logout,
                title: 'התנתקות',
                isDestructive: true,
                onTap: () => _logout(context, ref),
              ),
            ],
          ),
          const SizedBox(height: 20),

          const DisclaimerBanner(margin: EdgeInsets.zero),
          const SizedBox(height: 16),
          Center(
            child: Text(
              '${AppConfig.appName} · גרסה 1.0.0',
              style: theme.textTheme.labelSmall,
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _editProfile(BuildContext context, WidgetRef ref) async {
    final user = ref.read(authControllerProvider).user;
    if (user == null) return;

    final nameController = TextEditingController(text: user.fullName);
    final phoneController = TextEditingController(text: user.phone ?? '');

    final saved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('עריכת פרטים'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              decoration: const InputDecoration(labelText: 'שם מלא'),
              textCapitalization: TextCapitalization.words,
            ),
            const SizedBox(height: 12),
            TextField(
              controller: phoneController,
              decoration: const InputDecoration(labelText: 'טלפון'),
              keyboardType: TextInputType.phone,
              textDirection: TextDirection.ltr,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('ביטול'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('שמירה'),
          ),
        ],
      ),
    );

    if (saved ?? false) {
      await ref.read(authControllerProvider.notifier).updateProfile(
            fullName: nameController.text.trim(),
            phone: phoneController.text.trim(),
          );
      if (context.mounted) showMessage(context, 'הפרטים עודכנו');
    }
    nameController.dispose();
    phoneController.dispose();
  }

  Future<void> _changePassword(BuildContext context, WidgetRef ref) async {
    final currentController = TextEditingController();
    final newController = TextEditingController();

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('שינוי סיסמה'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: currentController,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'סיסמה נוכחית'),
            ),
            const SizedBox(height: 12),
            TextField(
              controller: newController,
              obscureText: true,
              decoration: const InputDecoration(labelText: 'סיסמה חדשה'),
            ),
            const SizedBox(height: 12),
            Text(
              'לאחר שינוי הסיסמה תתנתק מכל המכשירים ותידרש להתחבר מחדש.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('ביטול'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('שינוי'),
          ),
        ],
      ),
    );

    if (confirmed ?? false) {
      final success =
          await ref.read(authControllerProvider.notifier).changePassword(
                currentPassword: currentController.text,
                newPassword: newController.text,
              );
      if (context.mounted && !success) {
        final error = ref.read(authControllerProvider).error;
        showMessage(context, error ?? 'שינוי הסיסמה נכשל', isError: true);
      }
    }
    currentController.dispose();
    newController.dispose();
  }

  Future<void> _logout(BuildContext context, WidgetRef ref) async {
    final choice = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('התנתקות'),
        content: const Text('להתנתק מהחשבון?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('ביטול'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop('all'),
            child: const Text('מכל המכשירים'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop('this'),
            child: const Text('התנתקות'),
          ),
        ],
      ),
    );
    if (choice == null) return;
    await ref
        .read(authControllerProvider.notifier)
        .logout(allDevices: choice == 'all');
  }
}

class _Group extends StatelessWidget {
  const _Group({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(right: 4, bottom: 8),
            child: Text(title, style: Theme.of(context).textTheme.labelLarge),
          ),
          Card(child: Column(children: children)),
        ],
      );
}

class _Tile extends StatelessWidget {
  const _Tile({
    required this.icon,
    required this.title,
    required this.onTap,
    this.subtitle,
    this.isDestructive = false,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final VoidCallback onTap;
  final bool isDestructive;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final color = isDestructive ? scheme.error : null;

    return ListTile(
      leading: Icon(icon, color: color),
      title: Text(title, style: TextStyle(color: color)),
      subtitle: subtitle == null ? null : Text(subtitle!),
      trailing: const Icon(Icons.chevron_left, size: 20),
      onTap: onTap,
    );
  }
}
