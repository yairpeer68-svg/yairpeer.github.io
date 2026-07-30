import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/app_router.dart';

/// Persistent navigation shell around the five primary destinations.
class HomeShell extends StatelessWidget {
  const HomeShell({super.key, required this.location, required this.child});

  final String location;
  final Widget child;

  static const _destinations = <({String path, String label, IconData icon, IconData active})>[
    (
      path: '/chat',
      label: 'צ׳אט',
      icon: Icons.forum_outlined,
      active: Icons.forum,
    ),
    (
      path: '/documents',
      label: 'מסמכים',
      icon: Icons.folder_outlined,
      active: Icons.folder,
    ),
    (
      path: '/search',
      label: 'חיפוש',
      icon: Icons.search_outlined,
      active: Icons.search,
    ),
    (
      path: '/history',
      label: 'היסטוריה',
      icon: Icons.history_outlined,
      active: Icons.history,
    ),
    (
      path: '/profile',
      label: 'פרופיל',
      icon: Icons.person_outline,
      active: Icons.person,
    ),
  ];

  int get _selectedIndex {
    final index = _destinations.indexWhere((d) => location.startsWith(d.path));
    return index < 0 ? 0 : index;
  }

  @override
  Widget build(BuildContext context) {
    final isWide = MediaQuery.sizeOf(context).width >= 720;

    // On a tablet a bottom bar wastes the height that document text needs, so
    // the same destinations become a side rail.
    if (isWide) {
      return Scaffold(
        body: Row(
          children: [
            NavigationRail(
              selectedIndex: _selectedIndex,
              onDestinationSelected: (index) => _go(context, index),
              labelType: NavigationRailLabelType.all,
              destinations: [
                for (final destination in _destinations)
                  NavigationRailDestination(
                    icon: Icon(destination.icon),
                    selectedIcon: Icon(destination.active),
                    label: Text(destination.label),
                  ),
              ],
            ),
            const VerticalDivider(width: 1),
            Expanded(child: child),
          ],
        ),
      );
    }

    return Scaffold(
      body: child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (index) => _go(context, index),
        destinations: [
          for (final destination in _destinations)
            NavigationDestination(
              icon: Icon(destination.icon),
              selectedIcon: Icon(destination.active),
              label: destination.label,
              tooltip: destination.label,
            ),
        ],
      ),
    );
  }

  void _go(BuildContext context, int index) {
    final destination = _destinations[index];
    if (location.startsWith(destination.path)) return;
    context.go(destination.path);
  }
}

/// Quick-action grid shown at the top of an empty chat.
class QuickActions extends StatelessWidget {
  const QuickActions({super.key, this.onPrompt});

  /// Invoked with a starter question when a suggestion chip is tapped.
  final void Function(String prompt)? onPrompt;

  static const _actions = <({String label, IconData icon, String route})>[
    (label: 'יצירת חוזה', icon: Icons.description_outlined, route: '/contracts'),
    (label: 'מכתב משפטי', icon: Icons.mail_outline, route: '/letters'),
    (label: 'ניתוח מסמך', icon: Icons.fact_check_outlined, route: '/documents'),
    (label: 'חיפוש בחוק', icon: Icons.gavel_outlined, route: '/search'),
  ];

  static const _prompts = [
    'מהן זכויותיי כשוכר דירה כשהמשכיר לא מתקן ליקוי?',
    'מה חשוב לבדוק בחוזה עבודה לפני חתימה?',
    'כמה זמן יש לי להגיש ערעור על החלטה?',
    'מה ההבדל בין פיצוי מוסכם לפיצוי בגין נזק?',
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('מה נעשה היום?', style: theme.textTheme.titleMedium),
        const SizedBox(height: 12),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
          childAspectRatio: 2.4,
          children: [
            for (final action in _actions)
              Card(
                child: InkWell(
                  onTap: () => context.push(action.route),
                  borderRadius: BorderRadius.circular(20),
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Row(
                      children: [
                        Icon(action.icon, color: theme.colorScheme.primary),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            action.label,
                            style: theme.textTheme.labelLarge,
                            maxLines: 2,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 24),
        Text('שאלות לדוגמה', style: theme.textTheme.titleMedium),
        const SizedBox(height: 12),
        for (final prompt in _prompts)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: InkWell(
              onTap: () => onPrompt?.call(prompt),
              borderRadius: BorderRadius.circular(14),
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(color: theme.colorScheme.outlineVariant),
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.help_outline,
                      size: 18,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(prompt, style: theme.textTheme.bodyMedium),
                    ),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }
}
