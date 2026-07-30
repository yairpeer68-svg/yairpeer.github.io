import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../../../core/providers.dart';
import '../../../core/router/app_router.dart';
import '../../../shared/widgets/states.dart';
import '../../chat/domain/chat_message.dart';
import '../../chat/presentation/chat_controller.dart';

class HistoryScreen extends ConsumerStatefulWidget {
  const HistoryScreen({super.key});

  @override
  ConsumerState<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends ConsumerState<HistoryScreen> {
  final _searchController = TextEditingController();
  bool _favoritesOnly = false;
  String? _query;

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  ({bool favoritesOnly, String? query}) get _args =>
      (favoritesOnly: _favoritesOnly, query: _query);

  @override
  Widget build(BuildContext context) {
    final conversations = ref.watch(conversationsProvider(_args));

    return Scaffold(
      appBar: AppBar(
        title: const Text('היסטוריה'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(112),
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child: TextField(
                  controller: _searchController,
                  onSubmitted: (value) => setState(
                    () => _query = value.trim().isEmpty ? null : value.trim(),
                  ),
                  decoration: InputDecoration(
                    hintText: 'חיפוש בשיחות',
                    prefixIcon: const Icon(Icons.search),
                    isDense: true,
                    suffixIcon: _searchController.text.isEmpty
                        ? null
                        : IconButton(
                            icon: const Icon(Icons.close),
                            onPressed: () {
                              _searchController.clear();
                              setState(() => _query = null);
                            },
                          ),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                child: Row(
                  children: [
                    FilterChip(
                      label: const Text('הכל'),
                      selected: !_favoritesOnly,
                      onSelected: (_) => setState(() => _favoritesOnly = false),
                    ),
                    const SizedBox(width: 8),
                    FilterChip(
                      label: const Text('מועדפים'),
                      avatar: const Icon(Icons.star_outline, size: 16),
                      selected: _favoritesOnly,
                      onSelected: (_) => setState(() => _favoritesOnly = true),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
      body: conversations.when(
        loading: () => const _HistorySkeleton(),
        error: (error, _) => ErrorState(
          error: error,
          onRetry: () => ref.invalidate(conversationsProvider(_args)),
        ),
        data: (page) => page.items.isEmpty
            ? EmptyState(
                icon: _favoritesOnly
                    ? Icons.star_outline
                    : Icons.forum_outlined,
                title: _favoritesOnly
                    ? 'אין שיחות מועדפות'
                    : 'עדיין לא ניהלת שיחות',
                message: _favoritesOnly
                    ? 'סמן שיחה בכוכב כדי שתופיע כאן'
                    : 'התחל שיחה ותוכל לחזור אליה בכל עת',
                actionLabel: _favoritesOnly ? null : 'לצ׳אט',
                onAction: _favoritesOnly
                    ? null
                    : () => context.goNamed(Routes.chat),
              )
            : RefreshIndicator(
                onRefresh: () async =>
                    ref.invalidate(conversationsProvider(_args)),
                child: ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                  itemCount: page.items.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 8),
                  itemBuilder: (context, index) => _ConversationTile(
                    conversation: page.items[index],
                    onOpen: () => context.goNamed(
                      Routes.chat,
                      queryParameters: {'conversation': page.items[index].id},
                    ),
                    onToggleFavorite: () =>
                        _toggleFavorite(page.items[index]),
                    onTogglePin: () => _togglePin(page.items[index]),
                    onDelete: () => _confirmDelete(page.items[index]),
                  ),
                ),
              ),
      ),
    );
  }

  Future<void> _toggleFavorite(Conversation conversation) async {
    await ref.read(chatRepositoryProvider).updateConversation(
          conversation.id,
          isFavorite: !conversation.isFavorite,
        );
    ref.invalidate(conversationsProvider(_args));
  }

  Future<void> _togglePin(Conversation conversation) async {
    await ref.read(chatRepositoryProvider).updateConversation(
          conversation.id,
          isPinned: !conversation.isPinned,
        );
    ref.invalidate(conversationsProvider(_args));
  }

  Future<void> _confirmDelete(Conversation conversation) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('מחיקת שיחה'),
        content: Text('למחוק את "${conversation.title}"?'),
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
    if (!(confirmed ?? false)) return;

    await ref.read(chatRepositoryProvider).deleteConversation(conversation.id);
    ref.invalidate(conversationsProvider(_args));
    if (mounted) showMessage(context, 'השיחה נמחקה');
  }
}

class _ConversationTile extends StatelessWidget {
  const _ConversationTile({
    required this.conversation,
    required this.onOpen,
    required this.onToggleFavorite,
    required this.onTogglePin,
    required this.onDelete,
  });

  final Conversation conversation;
  final VoidCallback onOpen;
  final VoidCallback onToggleFavorite;
  final VoidCallback onTogglePin;
  final VoidCallback onDelete;

  IconData get _kindIcon => switch (conversation.kind) {
        'contract_analysis' || 'contract_draft' => Icons.description_outlined,
        'letter_draft' => Icons.mail_outline,
        'document_analysis' => Icons.fact_check_outlined,
        'case_summary' => Icons.gavel_outlined,
        _ => Icons.forum_outlined,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Dismissible(
      key: ValueKey(conversation.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: AlignmentDirectional.centerStart,
        padding: const EdgeInsetsDirectional.only(start: 24),
        decoration: BoxDecoration(
          color: theme.colorScheme.errorContainer,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Icon(Icons.delete_outline, color: theme.colorScheme.error),
      ),
      // Confirm rather than delete outright: a swipe is easy to do by accident
      // and a legal conversation is not cheap to recreate.
      confirmDismiss: (_) async {
        onDelete();
        return false;
      },
      child: Card(
        child: InkWell(
          onTap: onOpen,
          borderRadius: BorderRadius.circular(20),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                Container(
                  width: 40,
                  height: 40,
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(_kindIcon, size: 20),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          if (conversation.isPinned) ...[
                            Icon(
                              Icons.push_pin,
                              size: 13,
                              color: theme.colorScheme.primary,
                            ),
                            const SizedBox(width: 4),
                          ],
                          Expanded(
                            child: Text(
                              conversation.title,
                              style: theme.textTheme.titleSmall,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 3),
                      Text(
                        '${conversation.messageCount} הודעות · '
                        '${_relativeTime(conversation.updatedAt)}',
                        style: theme.textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
                IconButton(
                  icon: Icon(
                    conversation.isFavorite ? Icons.star : Icons.star_outline,
                    color: conversation.isFavorite
                        ? theme.colorScheme.primary
                        : null,
                  ),
                  tooltip: 'מועדף',
                  onPressed: onToggleFavorite,
                ),
                PopupMenuButton<String>(
                  tooltip: 'אפשרויות',
                  onSelected: (value) => switch (value) {
                    'pin' => onTogglePin(),
                    'delete' => onDelete(),
                    _ => null,
                  },
                  itemBuilder: (context) => [
                    PopupMenuItem(
                      value: 'pin',
                      child: Text(
                        conversation.isPinned ? 'ביטול נעיצה' : 'נעיצה',
                      ),
                    ),
                    const PopupMenuItem(value: 'delete', child: Text('מחיקה')),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// Hebrew relative time; falls back to a date beyond a week.
  static String _relativeTime(DateTime value) {
    final difference = DateTime.now().difference(value);
    if (difference.inMinutes < 1) return 'עכשיו';
    if (difference.inMinutes < 60) return 'לפני ${difference.inMinutes} דקות';
    if (difference.inHours < 24) return 'לפני ${difference.inHours} שעות';
    if (difference.inDays == 1) return 'אתמול';
    if (difference.inDays < 7) return 'לפני ${difference.inDays} ימים';
    return DateFormat('d MMM yyyy', 'he').format(value);
  }
}

class _HistorySkeleton extends StatelessWidget {
  const _HistorySkeleton();

  @override
  Widget build(BuildContext context) => ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
        itemCount: 6,
        separatorBuilder: (_, __) => const SizedBox(height: 8),
        itemBuilder: (context, index) => Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                const SkeletonBox(height: 40, width: 40, radius: 12),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: const [
                      SkeletonBox(height: 14, width: 180),
                      SizedBox(height: 8),
                      SkeletonBox(height: 11, width: 110),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      );
}
