import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/providers.dart';
import '../../../shared/widgets/states.dart';

/// Where the user supplies their own DeepSeek key.
///
/// In server-less mode this screen is the only thing standing between the app
/// and the model, so it explains plainly whose key it is, where it is stored,
/// and who pays for the usage.
class ApiKeyScreen extends ConsumerStatefulWidget {
  const ApiKeyScreen({super.key});

  @override
  ConsumerState<ApiKeyScreen> createState() => _ApiKeyScreenState();
}

class _ApiKeyScreenState extends ConsumerState<ApiKeyScreen> {
  final _controller = TextEditingController();
  bool _obscure = true;
  bool _checking = false;
  bool? _valid;
  bool _hasStoredKey = false;

  @override
  void initState() {
    super.initState();
    _loadExisting();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _loadExisting() async {
    final stored = await ref.read(secureStoreProvider).readApiKey();
    if (mounted) {
      setState(() => _hasStoredKey = stored != null && stored.isNotEmpty);
    }
  }

  Future<void> _save() async {
    final key = _controller.text.trim();
    if (key.isEmpty) return;

    setState(() {
      _checking = true;
      _valid = null;
    });

    // Verify before storing, so a typo is caught here rather than surfacing
    // as a confusing failure in the middle of a legal question.
    final client = ref.read(deepSeekDirectProvider);
    final valid = await client.validateKey(key);

    if (!mounted) return;
    setState(() {
      _checking = false;
      _valid = valid;
    });

    if (!valid) return;

    await ref.read(secureStoreProvider).saveApiKey(key);
    if (!mounted) return;
    setState(() => _hasStoredKey = true);
    _controller.clear();
    showMessage(context, 'המפתח נשמר');
  }

  Future<void> _remove() async {
    await ref.read(secureStoreProvider).clearApiKey();
    if (!mounted) return;
    setState(() {
      _hasStoredKey = false;
      _valid = null;
    });
    showMessage(context, 'המפתח נמחק מהמכשיר');
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('מפתח DeepSeek'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 32),
        children: [
          if (_hasStoredKey)
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: scheme.primaryContainer.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Row(
                children: [
                  Icon(Icons.key, size: 20, color: scheme.primary),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'מפתח מוגדר ופעיל',
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                  TextButton(
                    onPressed: _remove,
                    child: const Text('מחיקה'),
                  ),
                ],
              ),
            )
          else
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: scheme.errorContainer.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Row(
                children: [
                  Icon(Icons.key_off, size: 20, color: scheme.error),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'אין מפתח — האפליקציה לא תוכל לענות',
                      style: theme.textTheme.titleSmall,
                    ),
                  ),
                ],
              ),
            ),

          const SizedBox(height: 24),
          TextField(
            controller: _controller,
            obscureText: _obscure,
            textDirection: TextDirection.ltr,
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: _hasStoredKey ? 'החלפת מפתח' : 'מפתח API',
              hintText: 'sk-...',
              hintTextDirection: TextDirection.ltr,
              prefixIcon: const Icon(Icons.vpn_key_outlined),
              errorText: _valid == false ? 'המפתח נדחה על ידי DeepSeek' : null,
              suffixIcon: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: 'הדבקה',
                    icon: const Icon(Icons.content_paste),
                    onPressed: () async {
                      final data = await Clipboard.getData(Clipboard.kTextPlain);
                      if (data?.text != null) {
                        _controller.text = data!.text!.trim();
                      }
                    },
                  ),
                  IconButton(
                    icon: Icon(
                      _obscure
                          ? Icons.visibility_outlined
                          : Icons.visibility_off_outlined,
                    ),
                    onPressed: () => setState(() => _obscure = !_obscure),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _checking ? null : _save,
            icon: _checking
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2.2),
                  )
                : const Icon(Icons.check),
            label: Text(_checking ? 'בודק…' : 'בדיקה ושמירה'),
          ),

          const SizedBox(height: 32),
          Text('איך משיגים מפתח', style: theme.textTheme.titleMedium),
          const SizedBox(height: 12),
          const _Step(
            number: 1,
            text: 'היכנס ל-platform.deepseek.com והירשם',
          ),
          const _Step(
            number: 2,
            text: 'טען קרדיט התחלתי — $5 מספיקים למאות שאלות',
          ),
          const _Step(
            number: 3,
            text: 'צור מפתח במסך API Keys והעתק אותו לכאן',
          ),

          const SizedBox(height: 28),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: scheme.surfaceContainerHighest.withValues(alpha: 0.5),
              borderRadius: BorderRadius.circular(14),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.lock_outline, size: 18, color: scheme.primary),
                    const SizedBox(width: 8),
                    Text('איפה המפתח נשמר', style: theme.textTheme.titleSmall),
                  ],
                ),
                const SizedBox(height: 10),
                Text(
                  'המפתח נשמר במאגר המוצפן של המכשיר (Android Keystore) ולא '
                  'נשלח לשום שרת שלנו — אין לנו שרת בשלב הזה. השאלות שלך '
                  'נשלחות ישירות מהטלפון ל-DeepSeek.\n\n'
                  'המשמעות: אתה משלם ל-DeepSeek לפי שימוש, והשיחות שלך אינן '
                  'עוברות דרך אף גורם ביניים.',
                  style: theme.textTheme.bodySmall?.copyWith(height: 1.6),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Step extends StatelessWidget {
  const _Step({required this.number, required this.text});

  final int number;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 24,
            height: 24,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: theme.colorScheme.primaryContainer,
              shape: BoxShape.circle,
            ),
            child: Text(
              '$number',
              style: theme.textTheme.labelSmall?.copyWith(
                fontWeight: FontWeight.w700,
                color: theme.colorScheme.onPrimaryContainer,
              ),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              text,
              style: theme.textTheme.bodyMedium?.copyWith(height: 1.5),
            ),
          ),
        ],
      ),
    );
  }
}
