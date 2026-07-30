import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/tier/entitlements.dart';
import '../../../core/tier/tier.dart';
import '../../../shared/widgets/states.dart';

/// Shown when a gated feature is tapped.
///
/// [blocked] is the feature the user just reached for, so the sheet leads
/// with the reason they are here rather than a generic pitch.
Future<bool> showPaywall(BuildContext context, {Feature? blocked, String? reason}) async {
  final upgraded = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _PaywallSheet(blocked: blocked, reason: reason),
  );
  return upgraded ?? false;
}

class _PaywallSheet extends ConsumerWidget {
  const _PaywallSheet({this.blocked, this.reason});

  final Feature? blocked;
  final String? reason;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.85,
      maxChildSize: 0.95,
      builder: (context, controller) => Column(
        children: [
          Expanded(
            child: ListView(
              controller: controller,
              padding: const EdgeInsets.fromLTRB(24, 8, 24, 16),
              children: [
                Center(
                  child: Container(
                    width: 60,
                    height: 60,
                    decoration: BoxDecoration(
                      color: scheme.primaryContainer,
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: Icon(
                      blocked?.icon ?? Icons.workspace_premium_outlined,
                      size: 30,
                      color: scheme.onPrimaryContainer,
                    ),
                  ),
                ),
                const SizedBox(height: 18),
                Text(
                  blocked == null ? 'סנגור פרימיום' : blocked!.label,
                  style: theme.textTheme.headlineSmall,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 8),
                Text(
                  reason ?? blocked?.pitch ?? 'כל היכולות, בלי הגבלה',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 28),

                for (final feature in TierPolicy.sellingPoints)
                  _FeatureRow(
                    feature: feature,
                    highlighted: feature == blocked,
                  ),

                const SizedBox(height: 24),
                const _PlanCards(),
                const SizedBox(height: 20),

                // Stated plainly: the free tier is smaller, not less careful.
                Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest.withValues(alpha: 0.5),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(
                        Icons.verified_outlined,
                        size: 18,
                        color: scheme.onSurfaceVariant,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          'גם בגרסה הרגילה התשובות זהות באיכותן. פרימיום פותח '
                          'יותר שימושים ויכולות — הוא לא הופך את התשובות '
                          'למדויקות יותר, וההסתייגויות והאזהרות מוצגות תמיד.',
                          style: theme.textTheme.bodySmall?.copyWith(height: 1.5),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 12),
              child: Column(
                children: [
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: () => _purchase(context, ref),
                      child: const Text('שדרוג לפרימיום'),
                    ),
                  ),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(false),
                    child: const Text('אולי אחר כך'),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _purchase(BuildContext context, WidgetRef ref) async {
    // Billing is not wired yet. Rather than fake a purchase flow, say so and
    // unlock locally — this build is for the developer's own device.
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('רכישה בפיתוח'),
        content: const Text(
          'תשלום עדיין לא חובר. אפשר להפעיל פרימיום מקומית לבדיקה, '
          'ולחבר חיוב אמיתי בהמשך.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('ביטול'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('הפעל לבדיקה'),
          ),
        ],
      ),
    );

    if (confirmed ?? false) {
      await ref.read(entitlementProvider.notifier).grantPremium();
      if (context.mounted) {
        Navigator.of(context).pop(true);
        showMessage(context, 'פרימיום הופעל');
      }
    }
  }
}

class _FeatureRow extends StatelessWidget {
  const _FeatureRow({required this.feature, this.highlighted = false});

  final Feature feature;
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: highlighted ? scheme.primaryContainer.withValues(alpha: 0.5) : null,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          Icon(Icons.check_circle, size: 18, color: scheme.primary),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(feature.label, style: theme.textTheme.titleSmall),
                Text(feature.pitch, style: theme.textTheme.bodySmall),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PlanCards extends StatelessWidget {
  const _PlanCards();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        _PlanCard(
          title: 'שנתי',
          price: '₪349',
          period: 'לשנה',
          note: 'חיסכון של ₪119 — כמו חודשיים חינם',
          highlighted: true,
        ),
        const SizedBox(height: 10),
        const _PlanCard(title: 'חודשי', price: '₪39', period: 'לחודש'),
        const SizedBox(height: 10),
        const _PlanCard(
          title: 'מסמך בודד',
          price: '₪79',
          period: 'חד־פעמי',
          note: 'חוזה או מכתב אחד, כולל ייצוא',
        ),
        const SizedBox(height: 14),
        Text(
          'להשוואה: טיוטת חוזה אצל עורך דין — ₪800 ומעלה.',
          style: theme.textTheme.bodySmall,
          textAlign: TextAlign.center,
        ),
      ],
    );
  }
}

class _PlanCard extends StatelessWidget {
  const _PlanCard({
    required this.title,
    required this.price,
    required this.period,
    this.note,
    this.highlighted = false,
  });

  final String title;
  final String price;
  final String period;
  final String? note;
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: highlighted ? scheme.primary : scheme.outlineVariant,
          width: highlighted ? 2 : 1,
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(title, style: theme.textTheme.titleSmall),
                    if (highlighted) ...[
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: scheme.primary,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          'משתלם',
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: scheme.onPrimary,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
                if (note != null) ...[
                  const SizedBox(height: 3),
                  Text(note!, style: theme.textTheme.bodySmall),
                ],
              ],
            ),
          ),
          Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              Text(
                price,
                style: theme.textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w700,
                ),
              ),
              Text(period, style: theme.textTheme.labelSmall),
            ],
          ),
        ],
      ),
    );
  }
}
