import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../storage/secure_store.dart';
import 'api_exception.dart';

/// One decoded Server-Sent Event.
class SseEvent {
  const SseEvent(this.event, this.data);

  final String event;
  final Map<String, dynamic> data;

  bool get isStart => event == 'start';
  bool get isDelta => event == 'delta';
  bool get isDone => event == 'done';
  bool get isError => event == 'error';

  String get text => (data['text'] ?? '').toString();
}

/// Streams `text/event-stream` responses from the chat endpoint.
///
/// Dio's stream response yields arbitrary byte chunks, not lines, so frames
/// are reassembled here: a buffer is split on the blank-line separator and only
/// complete frames are parsed. Splitting naively on `\n` would corrupt any
/// answer whose delta happened to straddle a chunk boundary.
class SseClient {
  SseClient({required SecureStore store, Dio? dio})
      : _store = store,
        _dio = dio ?? Dio();

  final Dio _dio;
  final SecureStore _store;

  Stream<SseEvent> stream(
    String path, {
    required Map<String, dynamic> body,
    CancelToken? cancelToken,
  }) async* {
    final token = await _store.readAccessToken();

    late final Response<ResponseBody> response;
    try {
      response = await _dio.post<ResponseBody>(
        '${AppConfig.apiUrl}$path',
        data: body,
        cancelToken: cancelToken,
        options: Options(
          responseType: ResponseType.stream,
          receiveTimeout: AppConfig.streamTimeout,
          headers: {
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache',
            if (token != null) 'Authorization': 'Bearer $token',
          },
          validateStatus: (status) => status != null && status < 500,
        ),
      );
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }

    final status = response.statusCode ?? 0;
    if (status >= 400) {
      throw await _errorFromStream(response);
    }

    final buffer = StringBuffer();
    await for (final chunk in response.data!.stream) {
      buffer.write(utf8.decode(chunk, allowMalformed: true));

      var content = buffer.toString();
      // Frames end with a blank line; anything after the last one is partial.
      final lastBreak = content.lastIndexOf('\n\n');
      if (lastBreak == -1) continue;

      final complete = content.substring(0, lastBreak);
      final remainder = content.substring(lastBreak + 2);
      buffer
        ..clear()
        ..write(remainder);

      for (final frame in complete.split('\n\n')) {
        final event = _parseFrame(frame);
        if (event != null) yield event;
      }
    }

    // A final frame may arrive without a trailing blank line.
    final tail = _parseFrame(buffer.toString());
    if (tail != null) yield tail;
  }

  static SseEvent? _parseFrame(String frame) {
    if (frame.trim().isEmpty) return null;

    var name = 'message';
    final dataLines = <String>[];
    for (final line in frame.split('\n')) {
      if (line.startsWith('event:')) {
        name = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.add(line.substring(5).trim());
      }
    }
    if (dataLines.isEmpty) return null;

    try {
      final decoded = jsonDecode(dataLines.join('\n'));
      return SseEvent(
        name,
        decoded is Map<String, dynamic>
            ? decoded
            : <String, dynamic>{'value': decoded},
      );
    } on FormatException {
      // A malformed frame is dropped rather than killing the whole answer.
      return null;
    }
  }

  Future<ApiException> _errorFromStream(Response<ResponseBody> response) async {
    final bytes = <int>[];
    await for (final chunk in response.data!.stream) {
      bytes.addAll(chunk);
      if (bytes.length > 8192) break;
    }
    try {
      final decoded = jsonDecode(utf8.decode(bytes, allowMalformed: true));
      if (decoded is Map && decoded['error'] is Map) {
        final envelope = Map<String, dynamic>.from(decoded['error'] as Map);
        return ApiException(
          code: (envelope['code'] ?? 'error').toString(),
          message: (envelope['message'] ?? 'אירעה שגיאה').toString(),
          statusCode: response.statusCode,
          requestId: envelope['request_id']?.toString(),
        );
      }
    } on FormatException {
      // fall through to the generic message
    }
    return ApiException(
      code: 'stream_error',
      message: 'לא ניתן להתחיל את התשובה',
      statusCode: response.statusCode,
    );
  }
}
