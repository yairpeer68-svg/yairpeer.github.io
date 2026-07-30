import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/config/app_config.dart';
import '../../../core/providers.dart';
import '../../../shared/widgets/citation_card.dart';
import '../../../shared/widgets/disclaimer_banner.dart';
import '../../../shared/widgets/states.dart';
import '../../documents/presentation/document_picker_sheet.dart';
import '../../home/presentation/home_shell.dart';
import 'chat_controller.dart';
import 'widgets/chat_composer.dart';
import 'widgets/message_bubble.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key, this.conversationId});

  final String? conversationId;

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final _scrollController = ScrollController();
  final _composerKey = GlobalKey<ChatComposerState>();

  /// Set when the user scrolls up, so incoming deltas stop yanking the view.
  bool _userScrolledAway = false;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    if (widget.conversationId != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        ref
            .read(chatControllerProvider.notifier)
            .loadConversation(widget.conversationId!);
      });
    }
  }

  @override
  void dispose() {
    _scrollController
      ..removeListener(_onScroll)
      ..dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    final position = _scrollController.position;
    _userScrolledAway = position.maxScrollExtent - position.pixels > 160;
  }

  void _scrollToBottom({bool force = false}) {
    if (!_scrollController.hasClients) return;
    if (_userScrolledAway && !force) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOut,
      );
    });
  }

  /// Content of the newest message, used to detect a streaming update.
  static String? _lastContent(ChatState? state) {
    final messages = state?.messages;
    if (messages == null || messages.isEmpty) return null;
    return messages[messages.length - 1].content;
  }

  Future<void> _attach() async {
    final documentId = await showDocumentPicker(context);
    if (documentId != null) {
      ref.read(chatControllerProvider.notifier).attachDocument(documentId);
    }
  }

  Future<void> _export(String format) async {
    final conversationId = ref.read(chatControllerProvider).conversationId;
    if (conversationId == null) {
      showMessage(context, 'אין שיחה לייצוא', isError: true);
      return;
    }
    showMessage(context, 'מכין קובץ…');
    try {
      final file = await ref.read(documentsRepositoryProvider).export(
            format: format,
            conversationId: conversationId,
            title: ref.read(chatControllerProvider).title,
          );
      await SharePlus.instance.share(
        ShareParams(files: [XFile(file.path)], title: 'ייצוא שיחה'),
      );
    } on Object catch (error) {
      if (mounted) showMessage(context, '$error', isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(chatControllerProvider);
    final controller = ref.read(chatControllerProvider.notifier);

    ref.listen(chatControllerProvider, (previous, next) {
      final grew = previous?.messages.length != next.messages.length;
      final tailChanged = _lastContent(previous) != _lastContent(next);
      if (grew || tailChanged) _scrollToBottom();
      if (next.error != null && previous?.error != next.error) {
        showMessage(context, next.error!, isError: true);
        controller.clearError();
      }
    });

    return Scaffold(
      appBar: AppBar(
        title: Text(
          state.isEmpty ? AppConfig.appName : state.title,
          overflow: TextOverflow.ellipsis,
        ),
        actions: [
          if (!state.isEmpty)
            IconButton(
              icon: const Icon(Icons.add_comment_outlined),
              tooltip: 'שיחה חדשה',
              onPressed: () {
                controller.startNewConversation();
                _userScrolledAway = false;
              },
            ),
          if (!state.isEmpty)
            PopupMenuButton<String>(
              tooltip: 'אפשרויות',
              onSelected: (value) => switch (value) {
                'pdf' || 'docx' || 'md' => _export(value),
                _ => null,
              },
              itemBuilder: (context) => const [
                PopupMenuItem(
                  value: 'pdf',
                  child: ListTile(
                    leading: Icon(Icons.picture_as_pdf_outlined),
                    title: Text('ייצוא ל-PDF'),
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
                PopupMenuItem(
                  value: 'docx',
                  child: ListTile(
                    leading: Icon(Icons.description_outlined),
                    title: Text('ייצוא ל-Word'),
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
                PopupMenuItem(
                  value: 'md',
                  child: ListTile(
                    leading: Icon(Icons.text_snippet_outlined),
                    title: Text('ייצוא כטקסט'),
                    contentPadding: EdgeInsets.zero,
                  ),
                ),
              ],
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: state.isLoading
                ? const LoadingState(message: 'טוען שיחה…')
                : state.isEmpty
                    ? _EmptyChat(
                        onPrompt: (prompt) =>
                            _composerKey.currentState?.setText(prompt),
                      )
                    : _MessageList(
                        scrollController: _scrollController,
                        state: state,
                        controller: controller,
                      ),
          ),
          const DisclaimerBanner(
            compact: true,
            margin: EdgeInsets.symmetric(horizontal: 16, vertical: 2),
          ),
          ChatComposer(
            key: _composerKey,
            isSending: state.isSending,
            attachmentCount: state.attachmentIds.length,
            onAttach: _attach,
            onRemoveAttachments: () {
              for (final id in [...state.attachmentIds]) {
                controller.removeAttachment(id);
              }
            },
            onCancel: controller.cancel,
            onSend: (text) {
              _userScrolledAway = false;
              controller.send(text);
            },
          ),
        ],
      ),
    );
  }
}

class _MessageList extends StatelessWidget {
  const _MessageList({
    required this.scrollController,
    required this.state,
    required this.controller,
  });

  final ScrollController scrollController;
  final ChatState state;
  final ChatController controller;

  @override
  Widget build(BuildContext context) {
    // The pending-sources banner is an extra leading item while an answer is
    // being generated, so the user sees the grounding before the text.
    final showSources = state.isSending && state.pendingSources.isNotEmpty;

    return ListView.builder(
      controller: scrollController,
      padding: const EdgeInsets.symmetric(vertical: 12),
      itemCount: state.messages.length + (showSources ? 1 : 0),
      itemBuilder: (context, index) {
        if (showSources && index == state.messages.length) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
            child: CitationList(
              citations: state.pendingSources,
              title: 'מקורות שנמצאו',
            ),
          );
        }
        final message = state.messages[index];
        return MessageBubble(
          message: message,
          onPin: message.id.startsWith('local-') ||
                  message.id.startsWith('pending-')
              ? null
              : () => controller.togglePin(message),
          onRetry: controller.retryLast,
          onShare: (content) => SharePlus.instance.share(
            ShareParams(text: '$content\n\n---\n${AppConfig.disclaimer}'),
          ),
        );
      },
    );
  }
}

class _EmptyChat extends StatelessWidget {
  const _EmptyChat({required this.onPrompt});

  final void Function(String prompt) onPrompt;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 24, 16, 16),
      children: [
        Column(
          children: [
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                color: theme.colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Icon(
                Icons.balance_outlined,
                size: 30,
                color: theme.colorScheme.onPrimaryContainer,
              ),
            ),
            const SizedBox(height: 16),
            Text('שלום', style: theme.textTheme.headlineSmall),
            const SizedBox(height: 6),
            Text(
              'שאל אותי כל שאלה על הדין הישראלי',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
        const SizedBox(height: 32),
        QuickActions(onPrompt: onPrompt),
        const SizedBox(height: 16),
        const DisclaimerBanner(margin: EdgeInsets.zero),
      ],
    );
  }
}
