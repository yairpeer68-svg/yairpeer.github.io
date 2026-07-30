import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/providers.dart';
import '../data/chat_repository.dart';
import '../domain/chat_message.dart';
import '../domain/citation.dart';

class ChatState {
  const ChatState({
    this.conversationId,
    this.title = 'שיחה חדשה',
    this.messages = const [],
    this.pendingSources = const [],
    this.isSending = false,
    this.isLoading = false,
    this.error,
    this.attachmentIds = const [],
  });

  final String? conversationId;
  final String title;
  final List<ChatMessage> messages;

  /// Sources for the answer currently being generated. They arrive on the
  /// `start` event, before any text, so the user sees the grounding first.
  final List<Citation> pendingSources;

  final bool isSending;
  final bool isLoading;
  final String? error;
  final List<String> attachmentIds;

  bool get isEmpty => messages.isEmpty;

  ChatState copyWith({
    String? conversationId,
    String? title,
    List<ChatMessage>? messages,
    List<Citation>? pendingSources,
    bool? isSending,
    bool? isLoading,
    String? error,
    List<String>? attachmentIds,
    bool clearError = false,
  }) =>
      ChatState(
        conversationId: conversationId ?? this.conversationId,
        title: title ?? this.title,
        messages: messages ?? this.messages,
        pendingSources: pendingSources ?? this.pendingSources,
        isSending: isSending ?? this.isSending,
        isLoading: isLoading ?? this.isLoading,
        error: clearError ? null : (error ?? this.error),
        attachmentIds: attachmentIds ?? this.attachmentIds,
      );
}

/// Drives one conversation: loading history, sending, streaming, cancelling.
class ChatController extends StateNotifier<ChatState> {
  ChatController(this._repository, {required bool streamingEnabled})
      : _streamingEnabled = streamingEnabled,
        super(const ChatState());

  final ChatRepository _repository;
  final bool _streamingEnabled;

  CancelToken? _cancelToken;
  StreamSubscription<StreamedAnswer>? _subscription;

  @override
  void dispose() {
    _cancelToken?.cancel();
    unawaited(_subscription?.cancel());
    super.dispose();
  }

  /// Load an existing conversation into the controller.
  Future<void> loadConversation(String conversationId) async {
    state = state.copyWith(isLoading: true, clearError: true);
    try {
      final conversation = await _repository.getConversation(conversationId);
      state = ChatState(
        conversationId: conversation.id,
        title: conversation.title,
        messages: conversation.messages,
      );
    } on ApiException catch (error) {
      state = state.copyWith(isLoading: false, error: error.message);
    }
  }

  void startNewConversation() => state = const ChatState();

  void attachDocument(String documentId) => state = state.copyWith(
        attachmentIds: [...state.attachmentIds, documentId],
      );

  void removeAttachment(String documentId) => state = state.copyWith(
        attachmentIds:
            state.attachmentIds.where((id) => id != documentId).toList(),
      );

  /// Send a message and stream (or await) the answer.
  Future<void> send(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty || state.isSending) return;

    final now = DateTime.now();
    final userMessage = ChatMessage(
      id: 'local-${now.microsecondsSinceEpoch}',
      role: MessageRole.user,
      content: trimmed,
      createdAt: now,
      attachments: [
        for (final id in state.attachmentIds) {'document_id': id},
      ],
    );

    // Optimistic append, plus an empty assistant bubble the deltas fill in.
    final placeholder = ChatMessage(
      id: 'pending-${now.microsecondsSinceEpoch}',
      role: MessageRole.assistant,
      content: '',
      createdAt: now,
      isStreaming: true,
    );

    state = state.copyWith(
      messages: [...state.messages, userMessage, placeholder],
      isSending: true,
      pendingSources: const [],
      clearError: true,
    );

    final attachments = state.attachmentIds;
    state = state.copyWith(attachmentIds: const []);

    if (_streamingEnabled) {
      await _sendStreaming(trimmed, attachments, placeholder.id);
    } else {
      await _sendBlocking(trimmed, attachments, placeholder.id);
    }
  }

  Future<void> _sendStreaming(
    String text,
    List<String> attachmentIds,
    String placeholderId,
  ) async {
    _cancelToken = CancelToken();
    final completer = Completer<void>();

    _subscription = _repository
        .streamMessage(
          message: text,
          conversationId: state.conversationId,
          attachmentIds: attachmentIds,
          cancelToken: _cancelToken,
        )
        .listen(
          (answer) => _applyAnswer(answer, placeholderId, streaming: true),
          onError: (Object error) {
            _failPlaceholder(placeholderId, _describe(error));
            if (!completer.isCompleted) completer.complete();
          },
          onDone: () {
            _finishPlaceholder(placeholderId);
            if (!completer.isCompleted) completer.complete();
          },
          cancelOnError: true,
        );

    await completer.future;
    _cancelToken = null;
    await _subscription?.cancel();
    _subscription = null;
  }

  Future<void> _sendBlocking(
    String text,
    List<String> attachmentIds,
    String placeholderId,
  ) async {
    try {
      final answer = await _repository.sendMessage(
        message: text,
        conversationId: state.conversationId,
        attachmentIds: attachmentIds,
      );
      _applyAnswer(answer, placeholderId, streaming: false);
      _finishPlaceholder(placeholderId);
    } on Object catch (error) {
      _failPlaceholder(placeholderId, _describe(error));
    }
  }

  void _applyAnswer(
    StreamedAnswer answer,
    String placeholderId, {
    required bool streaming,
  }) {
    final messages = [
      for (final message in state.messages)
        if (message.id == placeholderId)
          message.copyWith(
            content: answer.text,
            citations:
                answer.citations.isNotEmpty ? answer.citations : message.citations,
            isStreaming: streaming,
          )
        else
          message,
    ];

    state = state.copyWith(
      messages: messages,
      conversationId: answer.conversationId.isNotEmpty
          ? answer.conversationId
          : state.conversationId,
      title: answer.conversationTitle?.isNotEmpty == true
          ? answer.conversationTitle
          : state.title,
      pendingSources: answer.sources.isNotEmpty
          ? answer.sources
          : state.pendingSources,
    );
  }

  void _finishPlaceholder(String placeholderId) {
    state = state.copyWith(
      isSending: false,
      pendingSources: const [],
      messages: [
        for (final message in state.messages)
          if (message.id == placeholderId)
            message.copyWith(isStreaming: false)
          else
            message,
      ],
    );
  }

  void _failPlaceholder(String placeholderId, String message) {
    state = state.copyWith(
      isSending: false,
      error: message,
      pendingSources: const [],
      messages: [
        for (final entry in state.messages)
          if (entry.id == placeholderId)
            entry.copyWith(isStreaming: false, error: message)
          else
            entry,
      ],
    );
  }

  /// Stop an in-flight answer. Whatever streamed so far is kept.
  void cancel() {
    _cancelToken?.cancel('cancelled by user');
    unawaited(_subscription?.cancel());
    _subscription = null;
    state = state.copyWith(
      isSending: false,
      messages: [
        for (final message in state.messages)
          message.isStreaming ? message.copyWith(isStreaming: false) : message,
      ],
    );
  }

  /// Re-send the last question, replacing the failed answer.
  Future<void> retryLast() async {
    ChatMessage? lastUser;
    for (final message in state.messages.reversed) {
      if (message.isUser) {
        lastUser = message;
        break;
      }
    }
    if (lastUser == null || lastUser.content.isEmpty) return;

    // Drop the trailing failed exchange before retrying.
    final messages = [...state.messages];
    while (messages.isNotEmpty && !messages.last.isUser) {
      messages.removeLast();
    }
    if (messages.isNotEmpty && messages.last.isUser) messages.removeLast();

    state = state.copyWith(messages: messages, clearError: true);
    await send(lastUser.content);
  }

  Future<void> togglePin(ChatMessage message) async {
    // Optimistic: the pin is trivially reversible, so don't block the tap.
    final target = !message.isPinned;
    state = state.copyWith(
      messages: [
        for (final entry in state.messages)
          entry.id == message.id ? entry.copyWith(isPinned: target) : entry,
      ],
    );
    try {
      await _repository.pinMessage(message.id, pinned: target);
    } on ApiException {
      state = state.copyWith(
        messages: [
          for (final entry in state.messages)
            entry.id == message.id
                ? entry.copyWith(isPinned: message.isPinned)
                : entry,
        ],
        error: 'לא ניתן לעדכן את הנעיצה',
      );
    }
  }

  void clearError() => state = state.copyWith(clearError: true);

  static String _describe(Object error) =>
      error is ApiException ? error.message : 'אירעה שגיאה בקבלת התשובה';
}

final chatControllerProvider =
    StateNotifierProvider.autoDispose<ChatController, ChatState>(
  (ref) => ChatController(
    ref.watch(chatRepositoryProvider),
    streamingEnabled: ref.watch(streamingEnabledProvider),
  ),
);

/// Conversation list for the history screen.
final conversationsProvider = FutureProvider.autoDispose
    .family<({List<Conversation> items, int total}), ({bool favoritesOnly, String? query})>(
  (ref, args) => ref.watch(chatRepositoryProvider).listConversations(
        favoritesOnly: args.favoritesOnly,
        query: args.query,
      ),
);
