import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../core/router/app_router.dart';
import '../../../shared/widgets/disclaimer_banner.dart';
import '../../../shared/widgets/states.dart';
import '../domain/template.dart';
import 'drafting_controller.dart';
import 'generated_document_screen.dart';

/// Renders a form from the template definition returned by the backend.
///
/// Nothing about the fields is hard-coded here, so a new contract type on the
/// server appears in the app without a client release.
class TemplateFormScreen extends ConsumerStatefulWidget {
  const TemplateFormScreen({
    super.key,
    required this.category,
    required this.templateKey,
  });

  final String category;
  final String templateKey;

  @override
  ConsumerState<TemplateFormScreen> createState() => _TemplateFormScreenState();
}

class _TemplateFormScreenState extends ConsumerState<TemplateFormScreen> {
  final _formKey = GlobalKey<FormState>();
  final Map<String, TextEditingController> _controllers = {};
  final Map<String, dynamic> _values = {};

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  TextEditingController _controllerFor(String key) =>
      _controllers.putIfAbsent(key, TextEditingController.new);

  Future<void> _generate(LegalTemplate template) async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    FocusScope.of(context).unfocus();

    final inputs = <String, dynamic>{..._values};
    for (final entry in _controllers.entries) {
      final text = entry.value.text.trim();
      if (text.isNotEmpty) inputs[entry.key] = text;
    }

    final missing = template.fields
        .where(
          (field) =>
              field.required &&
              (inputs[field.key] == null ||
                  '${inputs[field.key]}'.trim().isEmpty),
        )
        .map((field) => field.label)
        .toList();

    if (missing.isNotEmpty) {
      // The backend would mark these as ______ anyway; warn first so the user
      // can decide rather than being surprised by blanks in the draft.
      final proceed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('חסרים פרטים'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'השדות הבאים לא מולאו. הם יופיעו במסמך כ-______ ולא יומצאו '
                'על ידי המערכת:',
              ),
              const SizedBox(height: 10),
              for (final label in missing) Text('• $label'),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('חזרה למילוי'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('המשך בכל זאת'),
            ),
          ],
        ),
      );
      if (!(proceed ?? false)) return;
    }

    final document =
        await ref.read(draftingControllerProvider.notifier).generate(
              category: widget.category,
              templateKey: widget.templateKey,
              inputs: inputs,
            );

    if (!mounted) return;
    if (document == null) {
      final error = ref.read(draftingControllerProvider).error;
      showMessage(context, error ?? 'יצירת המסמך נכשלה', isError: true);
      return;
    }
    context.pushReplacementNamed(
      Routes.generated,
      extra: GeneratedDocumentArgs(document),
    );
  }

  @override
  Widget build(BuildContext context) {
    final args = (category: widget.category, key: widget.templateKey);
    final template = ref.watch(templateProvider(args));
    final drafting = ref.watch(draftingControllerProvider);

    return Scaffold(
      appBar: AppBar(
        title: Text(template.valueOrNull?.name ?? 'טופס'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
      ),
      body: template.when(
        loading: () => const LoadingState(),
        error: (error, _) => ErrorState(
          error: error,
          onRetry: () => ref.invalidate(templatesProvider(widget.category)),
        ),
        data: (item) => item == null
            ? const EmptyState(
                icon: Icons.help_outline,
                title: 'התבנית לא נמצאה',
              )
            : _form(item, drafting.isGenerating),
      ),
    );
  }

  Widget _form(LegalTemplate template, bool isGenerating) {
    final theme = Theme.of(context);

    return Stack(
      children: [
        Form(
          key: _formKey,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 100),
            children: [
              Text(template.description, style: theme.textTheme.bodyMedium),
              if (template.legalNotes.isNotEmpty) ...[
                const SizedBox(height: 14),
                _LegalNotes(notes: template.legalNotes),
              ],
              const SizedBox(height: 20),

              for (final field in template.fields)
                Padding(
                  padding: const EdgeInsets.only(bottom: 14),
                  child: _buildField(field),
                ),

              const SizedBox(height: 8),
              const DisclaimerBanner(margin: EdgeInsets.zero),
            ],
          ),
        ),
        Positioned(
          left: 16,
          right: 16,
          bottom: 16,
          child: FilledButton.icon(
            onPressed: isGenerating ? null : () => _generate(template),
            icon: isGenerating
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2.2),
                  )
                : const Icon(Icons.auto_awesome),
            label: Text(isGenerating ? 'מנסח…' : 'יצירת מסמך'),
          ),
        ),
      ],
    );
  }

  Widget _buildField(TemplateField field) {
    final label = field.required ? '${field.label} *' : field.label;

    switch (field.type) {
      case TemplateFieldType.boolean:
        return SwitchListTile(
          value: _values[field.key] as bool? ?? false,
          onChanged: (value) => setState(() => _values[field.key] = value),
          title: Text(field.label),
          subtitle: field.hint == null ? null : Text(field.hint!),
          contentPadding: EdgeInsets.zero,
        );

      case TemplateFieldType.select:
        return DropdownButtonFormField<String>(
          initialValue: _values[field.key] as String?,
          decoration: InputDecoration(labelText: label, helperText: field.hint),
          items: [
            for (final option in field.options)
              DropdownMenuItem(value: option, child: Text(option)),
          ],
          onChanged: (value) => setState(() => _values[field.key] = value),
          validator: field.required
              ? (value) => (value == null || value.isEmpty)
                  ? 'יש לבחור ${field.label}'
                  : null
              : null,
        );

      case TemplateFieldType.date:
        final selected = _values[field.key] as DateTime?;
        return InkWell(
          onTap: () async {
            final now = DateTime.now();
            final picked = await showDatePicker(
              context: context,
              initialDate: selected ?? now,
              // Wide enough for both a historic ruling date and a future
              // contract term.
              firstDate: DateTime(now.year - 30),
              lastDate: DateTime(now.year + 30),
              locale: const Locale('he', 'IL'),
            );
            if (picked != null) {
              setState(() {
                _values[field.key] =
                    picked.toIso8601String().split('T').first;
              });
            }
          },
          child: InputDecorator(
            decoration: InputDecoration(
              labelText: label,
              helperText: field.hint,
              prefixIcon: const Icon(Icons.event_outlined),
            ),
            child: Text(
              _values[field.key] == null
                  ? 'בחר תאריך'
                  : DateFormat('d MMMM yyyy', 'he').format(
                      DateTime.parse(_values[field.key] as String),
                    ),
            ),
          ),
        );

      case TemplateFieldType.currency:
        return TextFormField(
          controller: _controllerFor(field.key),
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          textDirection: TextDirection.ltr,
          inputFormatters: [
            FilteringTextInputFormatter.allow(RegExp(r'[\d.,]')),
          ],
          decoration: InputDecoration(
            labelText: label,
            helperText: field.hint,
            prefixIcon: const Icon(Icons.payments_outlined),
            suffixText: '₪',
          ),
          validator: _requiredValidator(field),
        );

      case TemplateFieldType.number:
        return TextFormField(
          controller: _controllerFor(field.key),
          keyboardType: TextInputType.number,
          textDirection: TextDirection.ltr,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: InputDecoration(labelText: label, helperText: field.hint),
          validator: _requiredValidator(field),
        );

      case TemplateFieldType.multiline:
        return TextFormField(
          controller: _controllerFor(field.key),
          maxLines: 5,
          minLines: 3,
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(
            labelText: label,
            helperText: field.hint,
            alignLabelWithHint: true,
          ),
          validator: _requiredValidator(field),
        );

      case TemplateFieldType.text:
        return TextFormField(
          controller: _controllerFor(field.key),
          textCapitalization: TextCapitalization.sentences,
          decoration: InputDecoration(labelText: label, helperText: field.hint),
          validator: _requiredValidator(field),
        );
    }
  }

  /// Required fields warn on submit rather than blocking, because a partial
  /// draft is still useful — the missing values become visible blanks.
  String? Function(String?)? _requiredValidator(TemplateField field) =>
      field.required
          ? (value) => (value?.trim().isEmpty ?? true)
              ? 'שדה חובה — ניתן להשאיר ריק והמסמך יסמן ______'
              : null
          : null;
}

class _LegalNotes extends StatelessWidget {
  const _LegalNotes({required this.notes});

  final List<String> notes;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.tips_and_updates_outlined,
                size: 16,
                color: theme.colorScheme.primary,
              ),
              const SizedBox(width: 6),
              Text('נקודות לתשומת לב', style: theme.textTheme.labelLarge),
            ],
          ),
          const SizedBox(height: 8),
          for (final note in notes)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text(
                '• $note',
                style: theme.textTheme.bodySmall?.copyWith(height: 1.5),
              ),
            ),
        ],
      ),
    );
  }
}
