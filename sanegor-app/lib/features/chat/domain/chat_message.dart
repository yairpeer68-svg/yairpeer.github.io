import 'package:flutter/foundation.dart';

import 'citation.dart';

enum MessageRole { user, assistant, system }

/// A single turn in a conversation.
@immutable
class ChatMessage {
  const ChatMessage({
    required this.id,
    required this.role,
    required this.content,
    required this.createdAt,
    this.citations = const [],
    this.attachments = const [],
    this.isPinned = false,
    this.isStreaming = false,
    this.error,
    this.model,
  });

  final String id;
  final MessageRole role;
  final String content;
  final DateTime createdAt;
  final List<Citation> citations;
  final List<Map<String, dynamic>> attachments;
  final bool isPinned;

  /// True while deltas are still arriving — drives the typing indicator.
  final bool isStreaming;
  final String? error;
  final String? model;

  bool get isUser => role == MessageRole.user;
  bool get isAssistant => role == MessageRole.assistant;
  bool get hasCitations => citations.isNotEmpty;
  bool get hasFailed => error != null && content.trim().isEmpty;

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id: (json['id'] ?? '').toString(),
        role: switch (json['role']) {
          'user' => MessageRole.user,
          'system' => MessageRole.system,
          _ => MessageRole.assistant,
        },
        content: (json['content'] ?? '').toString(),
        createdAt:
            DateTime.tryParse((json['created_at'] ?? '').toString())?.toLocal() ??
                DateTime.now(),
        citations: (json['citations'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(Citation.fromJson)
            .toList(growable: false),
        attachments: (json['attachments'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .toList(growable: false),
        isPinned: json['is_pinned'] == true,
        error: json['error']?.toString(),
        model: json['model']?.toString(),
      );

  ChatMessage copyWith({
    String? id,
    String? content,
    List<Citation>? citations,
    bool? isPinned,
    bool? isStreaming,
    String? error,
    String? model,
  }) =>
      ChatMessage(
        id: id ?? this.id,
        role: role,
        content: content ?? this.content,
        createdAt: createdAt,
        citations: citations ?? this.citations,
        attachments: attachments,
        isPinned: isPinned ?? this.isPinned,
        isStreaming: isStreaming ?? this.isStreaming,
        error: error ?? this.error,
        model: model ?? this.model,
      );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is ChatMessage &&
          other.id == id &&
          other.content == content &&
          other.isStreaming == isStreaming &&
          other.isPinned == isPinned &&
          other.citations.length == citations.length;

  @override
  int get hashCode =>
      Object.hash(id, content, isStreaming, isPinned, citations.length);
}

/// A conversation in the history list.
@immutable
class Conversation {
  const Conversation({
    required this.id,
    required this.title,
    required this.kind,
    required this.updatedAt,
    this.isPinned = false,
    this.isFavorite = false,
    this.messageCount = 0,
    this.messages = const [],
  });

  final String id;
  final String title;
  final String kind;
  final DateTime updatedAt;
  final bool isPinned;
  final bool isFavorite;
  final int messageCount;
  final List<ChatMessage> messages;

  factory Conversation.fromJson(Map<String, dynamic> json) => Conversation(
        id: (json['id'] ?? '').toString(),
        title: (json['title'] ?? 'שיחה').toString(),
        kind: (json['kind'] ?? 'chat').toString(),
        updatedAt:
            DateTime.tryParse((json['updated_at'] ?? '').toString())?.toLocal() ??
                DateTime.now(),
        isPinned: json['is_pinned'] == true,
        isFavorite: json['is_favorite'] == true,
        messageCount: (json['message_count'] as num?)?.toInt() ?? 0,
        messages: (json['messages'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(ChatMessage.fromJson)
            .toList(growable: false),
      );

  Conversation copyWith({
    String? title,
    bool? isPinned,
    bool? isFavorite,
    List<ChatMessage>? messages,
  }) =>
      Conversation(
        id: id,
        title: title ?? this.title,
        kind: kind,
        updatedAt: updatedAt,
        isPinned: isPinned ?? this.isPinned,
        isFavorite: isFavorite ?? this.isFavorite,
        messageCount: messageCount,
        messages: messages ?? this.messages,
      );
}
