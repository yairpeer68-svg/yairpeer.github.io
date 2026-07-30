import 'package:dio/dio.dart';

import '../../../core/network/api_client.dart';
import '../../../core/network/sse_client.dart';
import '../domain/chat_message.dart';
import '../domain/citation.dart';

/// The result of one streamed answer, assembled as events arrive.
class StreamedAnswer {
  StreamedAnswer({
    required this.conversationId,
    required this.messageId,
    this.sources = const [],
  });

  String conversationId;
  String messageId;
  List<Citation> sources;
  List<Citation> citations = const [];
  final StringBuffer buffer = StringBuffer();
  bool grounded = false;
  bool truncated = false;
  String? conversationTitle;

  String get text => buffer.toString();
}

/// Chat and conversation-history operations.
class ChatRepository {
  const ChatRepository({required ApiClient client, required SseClient sse})
      : _client = client,
        _sse = sse;

  final ApiClient _client;
  final SseClient _sse;

  /// Stream an answer. Emits the accumulating [StreamedAnswer] on every delta
  /// so the UI can repaint incrementally without re-reading the whole list.
  Stream<StreamedAnswer> streamMessage({
    required String message,
    String? conversationId,
    List<String> attachmentIds = const [],
    CancelToken? cancelToken,
  }) async* {
    final answer = StreamedAnswer(conversationId: '', messageId: '');

    final events = _sse.stream(
      '/chat',
      cancelToken: cancelToken,
      body: {
        'message': message,
        'stream': true,
        if (conversationId != null) 'conversation_id': conversationId,
        if (attachmentIds.isNotEmpty)
          'attachments': [
            for (final id in attachmentIds) {'document_id': id},
          ],
      },
    );

    await for (final event in events) {
      if (event.isStart) {
        answer
          ..conversationId = (event.data['conversation_id'] ?? '').toString()
          ..messageId = (event.data['message_id'] ?? '').toString()
          ..grounded = event.data['grounded'] == true
          ..sources = _citations(event.data['sources']);
        yield answer;
      } else if (event.isDelta) {
        answer.buffer.write(event.text);
        yield answer;
      } else if (event.isDone) {
        answer
          ..citations = _citations(event.data['citations'])
          ..grounded = event.data['grounded'] == true
          ..truncated = event.data['truncated'] == true
          ..conversationTitle = event.data['conversation_title']?.toString()
          ..messageId =
              (event.data['message_id'] ?? answer.messageId).toString();
        yield answer;
      } else if (event.isError) {
        throw _StreamFailure((event.data['message'] ?? 'אירעה שגיאה').toString());
      }
    }
  }

  /// Non-streaming fallback, used when the user disables streaming.
  Future<StreamedAnswer> sendMessage({
    required String message,
    String? conversationId,
    List<String> attachmentIds = const [],
  }) async {
    final response = await _client.post(
      '/chat',
      body: {
        'message': message,
        'stream': false,
        if (conversationId != null) 'conversation_id': conversationId,
        if (attachmentIds.isNotEmpty)
          'attachments': [
            for (final id in attachmentIds) {'document_id': id},
          ],
      },
    );

    final answer = StreamedAnswer(
      conversationId: (response['conversation_id'] ?? '').toString(),
      messageId: (response['message_id'] ?? '').toString(),
    )
      ..citations = _citations(response['citations'])
      ..grounded = response['grounded'] == true;
    answer.buffer.write((response['content'] ?? '').toString());
    return answer;
  }

  // ---------------------------------------------------------------- history
  Future<({List<Conversation> items, int total})> listConversations({
    int limit = 20,
    int offset = 0,
    bool favoritesOnly = false,
    String? query,
  }) async {
    final response = await _client.get(
      '/history',
      query: {
        'limit': limit,
        'offset': offset,
        if (favoritesOnly) 'favorites_only': true,
        if (query != null && query.isNotEmpty) 'query': query,
      },
    );
    return (
      items: (response['items'] as List? ?? const [])
          .whereType<Map<String, dynamic>>()
          .map(Conversation.fromJson)
          .toList(growable: false),
      total: (response['total'] as num?)?.toInt() ?? 0,
    );
  }

  Future<Conversation> getConversation(String id) async =>
      Conversation.fromJson(await _client.get('/history/$id'));

  Future<List<ChatMessage>> searchInConversation(
    String conversationId,
    String query,
  ) async {
    final results = await _client.getList(
      '/history/$conversationId/search',
      query: {'q': query},
    );
    return results
        .whereType<Map<String, dynamic>>()
        .map(ChatMessage.fromJson)
        .toList(growable: false);
  }

  Future<Conversation> updateConversation(
    String id, {
    String? title,
    bool? isPinned,
    bool? isFavorite,
  }) async =>
      Conversation.fromJson(
        await _client.patch(
          '/history/$id',
          body: {
            if (title != null) 'title': title,
            if (isPinned != null) 'is_pinned': isPinned,
            if (isFavorite != null) 'is_favorite': isFavorite,
          },
        ),
      );

  Future<void> deleteConversation(String id) => _client.delete('/history/$id');

  Future<ChatMessage> pinMessage(String messageId, {required bool pinned}) async =>
      ChatMessage.fromJson(
        await _client.post(
          '/history/messages/$messageId/pin',
          body: {'is_pinned': pinned},
        ),
      );

  static List<Citation> _citations(Object? raw) => (raw as List? ?? const [])
      .whereType<Map<String, dynamic>>()
      .map(Citation.fromJson)
      .toList(growable: false);
}

class _StreamFailure implements Exception {
  const _StreamFailure(this.message);
  final String message;
  @override
  String toString() => message;
}
