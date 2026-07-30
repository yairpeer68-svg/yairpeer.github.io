import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/router/app_router.dart';
import '../../../shared/widgets/disclaimer_banner.dart';
import '../../../shared/widgets/states.dart';
import '../domain/template.dart';
import 'drafting_controller.dart';
import 'generated_document_screen.dart';

/// Grid of contract or letter templates.
class TemplatesScreen extends ConsumerWidget {
  const TemplatesScreen({super.key, required this.category});

  final String category;

  bool get _isContract => category == 'contract';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final templates = ref.watch(templatesProvider(category));

    return Scaffold(
      appBar: AppBar(
        title: Text(_isContract ? 'יצירת חוזה' : 'מכתבים משפטיים'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.folder_special_outlined),
            tooltip: 'מסמכים שנוצרו',
            onPressed: () => _showGenerated(context, ref),
          ),
        ],
      ),
      body: templates.when(
        loading: () => const LoadingState(),
        error: (error, _) => ErrorState(
          error: error,
          onRetry: () => ref.invalidate(templatesProvider(category)),
        ),
        data: (items) => ListView(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
          children: [
            Text(
              _isContract
                  ? 'בחר סוג חוזה. המערכת תיצור טיוטה לפי הפרטים שתמלא.'
                  : 'בחר סוג מכתב. המערכת תנסח טיוטה לפי הפרטים שתמלא.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 16),
            for (final template in items)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _TemplateCard(
                  template: template,
                  onTap: () => context.pushNamed(
                    Routes.templateForm,
                    pathParameters: {
                      'category': category,
                      'key': template.key,
                    },
                  ),
                ),
              ),
            const SizedBox(height: 8),
            const DisclaimerBanner(margin: EdgeInsets.zero),
          ],
        ),
      ),
    );
  }

  void _showGenerated(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.7,
        builder: (context, controller) => Consumer(
          builder: (context, ref, _) {
            final documents = ref.watch(generatedDocumentsProvider(category));
            return Column(
              children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
                  child: Text(
                    'מסמכים שנוצרו',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                const Divider(height: 1),
                Expanded(
                  child: documents.when(
                    loading: () => const LoadingState(),
                    error: (error, _) => ErrorState(error: error),
                    data: (items) => items.isEmpty
                        ? const EmptyState(
                            icon: Icons.folder_open_outlined,
                            title: 'עדיין לא יצרת מסמכים',
                          )
                        : ListView.builder(
                            controller: controller,
                            padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                            itemCount: items.length,
                            itemBuilder: (context, index) {
                              final document = items[index];
                              return ListTile(
                                leading: const Icon(Icons.article_outlined),
                                title: Text(
                                  document.title,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                onTap: () {
                                  Navigator.of(context).pop();
                                  context.pushNamed(
                                    Routes.generated,
                                    extra: GeneratedDocumentArgs(document),
                                  );
                                },
                              );
                            },
                          ),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _TemplateCard extends StatelessWidget {
  const _TemplateCard({required this.template, required this.onTap});

  final LegalTemplate template;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Container(
                width: 46,
                height: 46,
                decoration: BoxDecoration(
                  color: theme.colorScheme.primaryContainer,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(
                  template.materialIcon,
                  color: theme.colorScheme.onPrimaryContainer,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(template.name, style: theme.textTheme.titleSmall),
                    const SizedBox(height: 3),
                    Text(
                      template.description,
                      style: theme.textTheme.bodySmall,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_left,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
