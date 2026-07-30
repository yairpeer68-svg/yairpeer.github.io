import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../../shared/widgets/citation_card.dart';
import '../../../shared/widgets/disclaimer_banner.dart';
import '../../../shared/widgets/markdown_body.dart';
import '../../../shared/widgets/states.dart';
import '../../chat/domain/citation.dart';
import '../domain/template.dart';

/// Wrapper so a generated document can travel through GoRouter's `extra`.
class GeneratedDocumentArgs {
  const GeneratedDocumentArgs(this.document);

  final GeneratedDocument document;
}

/// Displays a drafted contract or letter and offers export.
class GeneratedDocumentScreen extends ConsumerWidget {
  const GeneratedDocumentScreen({super.key, this.document});

  final GeneratedDocumentArgs? document;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final draft = document?.document;
    if (draft == null) {
      return Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.arrow_forward),
            onPressed: () => context.pop(),
          ),
        ),
        body: const EmptyState(
          icon: Icons.description_outlined,
          title: 'לא נמצא מסמך להצגה',
        ),
      );
    }

    final theme = Theme.of(context);
    final citations = draft.citations
        .whereType<Map<String, dynamic>>()
        .map(Citation.fromJson)
        .toList();

    return Scaffold(
      appBar: AppBar(
        title: Text(draft.title, overflow: TextOverflow.ellipsis),
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_outlined),
            tooltip: 'העתקה',
            onPressed: () async {
              await Clipboard.setData(
                ClipboardData(text: draft.bodyMarkdown),
              );
              if (context.mounted) showMessage(context, 'המסמך הועתק');
            },
          ),
          PopupMenuButton<String>(
            tooltip: 'ייצוא',
            icon: const Icon(Icons.download_outlined),
            onSelected: (format) => _export(context, ref, draft, format),
            itemBuilder: (context) => const [
              PopupMenuItem(value: 'pdf', child: Text('ייצוא ל-PDF')),
              PopupMenuItem(value: 'docx', child: Text('ייצוא ל-Word')),
              PopupMenuItem(value: 'md', child: Text('ייצוא כטקסט')),
            ],
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          if (draft.missingFields.isNotEmpty)
            _MissingFieldsNotice(fields: draft.missingFields),

          Card(
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: LegalMarkdown(data: draft.bodyMarkdown),
            ),
          ),

          if (citations.isNotEmpty) ...[
            const SizedBox(height: 12),
            CitationList(citations: citations),
          ],

          const SizedBox(height: 12),
          const DisclaimerBanner(margin: EdgeInsets.zero),
          const SizedBox(height: 16),

          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () => SharePlus.instance.share(
                    ShareParams(
                      text: '${draft.title}\n\n${draft.bodyMarkdown}'
                          '\n\n---\n${AppConfig.disclaimer}',
                    ),
                  ),
                  icon: const Icon(Icons.ios_share),
                  label: const Text('שיתוף'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton.icon(
                  onPressed: () => _export(context, ref, draft, 'pdf'),
                  icon: const Icon(Icons.picture_as_pdf_outlined),
                  label: const Text('PDF'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          Text(
            'לפני חתימה או הגשה — מומלץ מאוד להעביר את המסמך לבדיקה של '
            'עורך דין מוסמך.',
            style: theme.textTheme.bodySmall?.copyWith(height: 1.6),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Future<void> _export(
    BuildContext context,
    WidgetRef ref,
    GeneratedDocument draft,
    String format,
  ) async {
    showMessage(context, 'מכין קובץ…');
    try {
      final file = await ref.read(documentsRepositoryProvider).export(
            format: format,
            generatedDocumentId: draft.id.isEmpty ? null : draft.id,
            content: draft.id.isEmpty ? draft.bodyMarkdown : null,
            title: draft.title,
          );
      await SharePlus.instance.share(
        ShareParams(files: [XFile(file.path)], title: draft.title),
      );
    } on Object catch (error) {
      if (context.mounted) showMessage(context, '$error', isError: true);
    }
  }
}

/// Warns that the draft contains blanks the user still has to fill in.
///
/// The backend deliberately writes `______` rather than inventing a party
/// name or a date, so this notice is what turns that into a visible task.
class _MissingFieldsNotice extends StatelessWidget {
  const _MissingFieldsNotice({required this.fields});

  final List<String> fields;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      margin: const EdgeInsets.only(bottom: 16),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: theme.colorScheme.errorContainer.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: theme.colorScheme.error.withValues(alpha: 0.4)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.edit_note_outlined,
                size: 18,
                color: theme.colorScheme.error,
              ),
              const SizedBox(width: 8),
              Text(
                'פרטים שחסרים במסמך',
                style: theme.textTheme.titleSmall?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            'השדות הבאים מסומנים במסמך כ-______ ויש להשלים אותם ידנית:',
            style: theme.textTheme.bodySmall?.copyWith(height: 1.5),
          ),
          const SizedBox(height: 6),
          for (final field in fields)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(
                '• $field',
                style: theme.textTheme.bodySmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
        ],
      ),
    );
  }
}
