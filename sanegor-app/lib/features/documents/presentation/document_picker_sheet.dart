import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/widgets/states.dart';
import 'documents_controller.dart';

/// Pick an already-uploaded document to attach to a chat turn.
///
/// Returns the selected document id, or `null` if dismissed.
Future<String?> showDocumentPicker(BuildContext context) {
  return showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    builder: (context) => const _DocumentPickerSheet(),
  );
}

class _DocumentPickerSheet extends ConsumerWidget {
  const _DocumentPickerSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(documentsControllerProvider);
    final controller = ref.read(documentsControllerProvider.notifier);
    // Only processed documents have text to reason over.
    final ready = state.documents.where((d) => d.isReady).toList();

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.6,
      maxChildSize: 0.9,
      builder: (context, scrollController) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 12, 8),
            child: Row(
              children: [
                Text(
                  'צירוף מסמך',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const Spacer(),
                TextButton.icon(
                  onPressed: state.isUploading
                      ? null
                      : () async {
                          final document = await controller.pickAndUpload();
                          if (document != null && context.mounted) {
                            Navigator.of(context).pop(document.id);
                          }
                        },
                  icon: const Icon(Icons.upload_file, size: 18),
                  label: const Text('העלאה'),
                ),
              ],
            ),
          ),
          if (state.isUploading) const LinearProgressIndicator(),
          const Divider(height: 1),
          Expanded(
            child: ready.isEmpty
                ? const EmptyState(
                    icon: Icons.folder_open_outlined,
                    title: 'אין מסמכים מוכנים',
                    message: 'העלה מסמך כדי לצרף אותו לשיחה',
                  )
                : ListView.builder(
                    controller: scrollController,
                    padding: const EdgeInsets.fromLTRB(12, 8, 12, 24),
                    itemCount: ready.length,
                    itemBuilder: (context, index) {
                      final document = ready[index];
                      return ListTile(
                        leading: Icon(document.icon),
                        title: Text(
                          document.filename,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(document.subtitle),
                        onTap: () => Navigator.of(context).pop(document.id),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
