import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../core/router/app_router.dart';
import '../../../shared/widgets/states.dart';
import '../domain/document.dart';
import 'documents_controller.dart';

class DocumentsScreen extends ConsumerStatefulWidget {
  const DocumentsScreen({super.key});

  @override
  ConsumerState<DocumentsScreen> createState() => _DocumentsScreenState();
}

class _DocumentsScreenState extends ConsumerState<DocumentsScreen> {
  final _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _addDocument() async {
    final controller = ref.read(documentsControllerProvider.notifier);
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: Text('הוספת מסמך', style: TextStyle(fontSize: 18)),
            ),
            ListTile(
              leading: const Icon(Icons.folder_open_outlined),
              title: const Text('בחירת קובץ'),
              subtitle: const Text('PDF, Word, טקסט או תמונה'),
              onTap: () => Navigator.of(context).pop('file'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_camera_outlined),
              title: const Text('צילום מסמך'),
              subtitle: const Text('סריקה עם זיהוי טקסט בעברית'),
              onTap: () => Navigator.of(context).pop('camera'),
            ),
            ListTile(
              leading: const Icon(Icons.photo_library_outlined),
              title: const Text('בחירה מהגלריה'),
              onTap: () => Navigator.of(context).pop('gallery'),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;

    final document = switch (choice) {
      'camera' => await controller.captureAndUpload(),
      'gallery' => await controller.captureAndUpload(fromCamera: false),
      _ => await controller.pickAndUpload(),
    };

    if (!mounted) return;
    final warnings = ref.read(documentsControllerProvider).warnings;
    if (document != null && warnings.isNotEmpty) {
      showMessage(context, warnings.join(' · '));
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(documentsControllerProvider);
    final controller = ref.read(documentsControllerProvider.notifier);

    ref.listen(documentsControllerProvider, (previous, next) {
      if (next.error != null && previous?.error != next.error) {
        showMessage(context, next.error!, isError: true);
        controller.clearError();
      }
    });

    return Scaffold(
      appBar: AppBar(
        title: const Text('מסמכים'),
        actions: [
          IconButton(
            icon: const Icon(Icons.description_outlined),
            tooltip: 'יצירת חוזה',
            onPressed: () => context.pushNamed(Routes.contracts),
          ),
          IconButton(
            icon: const Icon(Icons.mail_outline),
            tooltip: 'יצירת מכתב',
            onPressed: () => context.pushNamed(Routes.letters),
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(64),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: TextField(
              controller: _searchController,
              onSubmitted: (value) => controller.load(query: value),
              decoration: InputDecoration(
                hintText: 'חיפוש במסמכים',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _searchController.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: () {
                          _searchController.clear();
                          controller.load();
                        },
                      ),
                isDense: true,
              ),
            ),
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: state.isUploading ? null : _addDocument,
        icon: const Icon(Icons.add),
        label: const Text('מסמך חדש'),
      ),
      body: Column(
        children: [
          if (state.isUploading)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                children: [
                  LinearProgressIndicator(
                    value: state.uploadProgress > 0 && state.uploadProgress < 1
                        ? state.uploadProgress
                        : null,
                  ),
                  const SizedBox(height: 6),
                  Text(
                    state.uploadProgress >= 1
                        ? 'מעבד את המסמך…'
                        : 'מעלה… ${(state.uploadProgress * 100).round()}%',
                    style: Theme.of(context).textTheme.labelSmall,
                  ),
                  const SizedBox(height: 8),
                ],
              ),
            ),
          Expanded(
            child: state.isLoading && state.documents.isEmpty
                ? const LoadingState()
                : state.documents.isEmpty
                    ? EmptyState(
                        icon: Icons.folder_open_outlined,
                        title: 'אין מסמכים עדיין',
                        message: 'העלה חוזה, מכתב או מסמך סרוק כדי לנתח אותו',
                        actionLabel: 'הוספת מסמך',
                        onAction: _addDocument,
                      )
                    : RefreshIndicator(
                        onRefresh: () => controller.load(),
                        child: ListView.separated(
                          padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
                          itemCount: state.documents.length,
                          separatorBuilder: (_, __) => const SizedBox(height: 8),
                          itemBuilder: (context, index) => _DocumentTile(
                            document: state.documents[index],
                            onDelete: () =>
                                _confirmDelete(state.documents[index]),
                          ),
                        ),
                      ),
          ),
        ],
      ),
    );
  }

  Future<void> _confirmDelete(LegalDocument document) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('מחיקת מסמך'),
        content: Text(
          'למחוק את "${document.filename}"? הקובץ יימחק מהשרת ולא ניתן יהיה '
          'לשחזר אותו.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('ביטול'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
            ),
            child: const Text('מחיקה'),
          ),
        ],
      ),
    );
    if (confirmed ?? false) {
      await ref.read(documentsControllerProvider.notifier).delete(document.id);
    }
  }
}

class _DocumentTile extends StatelessWidget {
  const _DocumentTile({required this.document, required this.onDelete});

  final LegalDocument document;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Card(
      child: InkWell(
        onTap: document.isReady
            ? () => _showActions(context)
            : null,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: scheme.primaryContainer,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  document.icon,
                  color: scheme.onPrimaryContainer,
                  size: 22,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      document.filename,
                      style: theme.textTheme.titleSmall,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 3),
                    Text(
                      '${document.subtitle} · '
                      '${DateFormat('d MMM yyyy', 'he').format(document.createdAt)}',
                      style: theme.textTheme.bodySmall,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (!document.isReady) ...[
                      const SizedBox(height: 6),
                      _StatusChip(document: document),
                    ],
                  ],
                ),
              ),
              IconButton(
                icon: const Icon(Icons.delete_outline),
                tooltip: 'מחיקה',
                onPressed: onDelete,
              ),
            ],
          ),
        ),
      ),
    );
  }

  void _showActions(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              child: Text(
                document.filename,
                style: Theme.of(sheetContext).textTheme.titleMedium,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            ListTile(
              leading: const Icon(Icons.fact_check_outlined),
              title: const Text('ניתוח מסמך'),
              subtitle: const Text('סיכום, סיכונים, מועדים והמלצות'),
              onTap: () {
                Navigator.of(sheetContext).pop();
                context.pushNamed(
                  Routes.analysis,
                  pathParameters: {'id': document.id},
                  queryParameters: {'kind': 'document'},
                );
              },
            ),
            ListTile(
              leading: const Icon(Icons.balance_outlined),
              title: const Text('ניתוח חוזה'),
              subtitle: const Text('סעיפים בעייתיים ונקודות למשא ומתן'),
              onTap: () {
                Navigator.of(sheetContext).pop();
                context.pushNamed(
                  Routes.analysis,
                  pathParameters: {'id': document.id},
                  queryParameters: {'kind': 'contract'},
                );
              },
            ),
            ListTile(
              leading: const Icon(Icons.gavel_outlined),
              title: const Text('סיכום פסק דין'),
              onTap: () {
                Navigator.of(sheetContext).pop();
                context.pushNamed(
                  Routes.analysis,
                  pathParameters: {'id': document.id},
                  queryParameters: {'kind': 'case_summary'},
                );
              },
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.document});

  final LegalDocument document;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isFailed = document.status == DocumentStatus.failed;
    final color = isFailed ? theme.colorScheme.error : theme.colorScheme.primary;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (!isFailed)
          SizedBox(
            width: 11,
            height: 11,
            child: CircularProgressIndicator(strokeWidth: 2, color: color),
          )
        else
          Icon(Icons.error_outline, size: 13, color: color),
        const SizedBox(width: 6),
        Flexible(
          child: Text(
            isFailed
                ? (document.error ?? 'עיבוד המסמך נכשל')
                : document.status.label,
            style: theme.textTheme.labelSmall?.copyWith(color: color),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ],
    );
  }
}
