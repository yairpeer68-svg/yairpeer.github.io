/// Direct DeepSeek client, used when the app runs without a backend.
///
/// The API key belongs to the person using the app: they paste it into
/// settings and it is stored in the platform keystore. Nothing is shared, so
/// nothing is exposed — which is exactly why this mode is safe for a personal
/// build and unsafe the moment one key would serve many users.
///
/// The response contract mirrors the backend's SSE stream so the presentation
/// layer cannot tell the two apart.
library;

import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';

import '../storage/secure_store.dart';
import 'api_exception.dart';

class DeepSeekMessage {
  const DeepSeekMessage(this.role, this.content);

  const DeepSeekMessage.system(this.content) : role = 'system';
  const DeepSeekMessage.user(this.content) : role = 'user';
  const DeepSeekMessage.assistant(this.content) : role = 'assistant';

  final String role;
  final String content;

  Map<String, String> toJson() => {'role': role, 'content': content};
}

class DeepSeekDirectClient {
  DeepSeekDirectClient({required SecureStore store, Dio? dio})
      : _store = store,
        _dio = dio ?? Dio();

  final Dio _dio;
  final SecureStore _store;

  static const String baseUrl = 'https://api.deepseek.com';
  static const String model = 'deepseek-chat';

  /// Generous: a full contract draft is slow, and there is no server in
  /// front to keep the connection warm.
  static const Duration _timeout = Duration(minutes: 5);

  Future<bool> get hasKey async => (await _store.readApiKey())?.isNotEmpty ?? false;

  Future<String> _requireKey() async {
    final key = await _store.readApiKey();
    if (key == null || key.isEmpty) {
      throw const ApiException(
        code: 'no_api_key',
        message: 'לא הוגדר מפתח DeepSeek. היכנס להגדרות והזן מפתח',
      );
    }
    return key;
  }

  Map<String, dynamic> _payload(
    List<DeepSeekMessage> messages, {
    required bool stream,
    double temperature = 0.2,
    int maxTokens = 4096,
    bool jsonMode = false,
  }) =>
      {
        'model': model,
        'messages': [for (final m in messages) m.toJson()],
        'temperature': temperature,
        'max_tokens': maxTokens,
        'stream': stream,
        if (jsonMode) 'response_format': {'type': 'json_object'},
      };

  /// Stream an answer as plain text deltas.
  Stream<String> stream(
    List<DeepSeekMessage> messages, {
    double temperature = 0.2,
    int maxTokens = 4096,
    CancelToken? cancelToken,
  }) async* {
    final key = await _requireKey();

    late final Response<ResponseBody> response;
    try {
      response = await _dio.post<ResponseBody>(
        '$baseUrl/chat/completions',
        data: _payload(
          messages,
          stream: true,
          temperature: temperature,
          maxTokens: maxTokens,
        ),
        cancelToken: cancelToken,
        options: Options(
          responseType: ResponseType.stream,
          receiveTimeout: _timeout,
          headers: {
            'Authorization': 'Bearer $key',
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
          },
          validateStatus: (status) => status != null && status < 500,
        ),
      );
    } on DioException catch (error) {
      throw _translate(error);
    }

    if ((response.statusCode ?? 0) >= 400) {
      throw _statusError(response.statusCode, await _drain(response));
    }

    // DeepSeek sends SSE frames, but a chunk boundary can fall anywhere, so
    // lines are reassembled rather than parsed per chunk.
    final buffer = StringBuffer();
    await for (final chunk in response.data!.stream) {
      buffer.write(utf8.decode(chunk, allowMalformed: true));
      final content = buffer.toString();
      final lastBreak = content.lastIndexOf('\n');
      if (lastBreak == -1) continue;

      buffer
        ..clear()
        ..write(content.substring(lastBreak + 1));

      for (final line in content.substring(0, lastBreak).split('\n')) {
        final delta = _parseLine(line);
        if (delta != null && delta.isNotEmpty) yield delta;
      }
    }

    final tail = _parseLine(buffer.toString());
    if (tail != null && tail.isNotEmpty) yield tail;
  }

  /// Blocking completion, used for JSON-shaped tasks such as analysis.
  Future<String> complete(
    List<DeepSeekMessage> messages, {
    double temperature = 0.2,
    int maxTokens = 4096,
    bool jsonMode = false,
    CancelToken? cancelToken,
  }) async {
    final key = await _requireKey();

    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '$baseUrl/chat/completions',
        data: _payload(
          messages,
          stream: false,
          temperature: temperature,
          maxTokens: maxTokens,
          jsonMode: jsonMode,
        ),
        cancelToken: cancelToken,
        options: Options(
          receiveTimeout: _timeout,
          headers: {
            'Authorization': 'Bearer $key',
            'Content-Type': 'application/json',
          },
          validateStatus: (status) => status != null && status < 500,
        ),
      );

      if ((response.statusCode ?? 0) >= 400) {
        throw _statusError(response.statusCode, jsonEncode(response.data));
      }

      final choices = response.data?['choices'];
      if (choices is! List || choices.isEmpty) {
        throw const ApiException(
          code: 'empty_response',
          message: 'שירות ה-AI החזיר תשובה ריקה',
        );
      }
      return (choices.first['message']?['content'] ?? '').toString();
    } on DioException catch (error) {
      throw _translate(error);
    }
  }

  /// Verify a key by making the smallest possible billable call.
  Future<bool> validateKey(String key) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '$baseUrl/chat/completions',
        data: {
          'model': model,
          'messages': [
            {'role': 'user', 'content': 'hi'},
          ],
          'max_tokens': 1,
        },
        options: Options(
          headers: {
            'Authorization': 'Bearer $key',
            'Content-Type': 'application/json',
          },
          validateStatus: (status) => status != null && status < 500,
        ),
      );
      return response.statusCode == 200;
    } on DioException {
      return false;
    }
  }

  // ------------------------------------------------------------------ parsing
  static String? _parseLine(String line) {
    final trimmed = line.trim();
    if (!trimmed.startsWith('data:')) return null;
    final data = trimmed.substring(5).trim();
    if (data.isEmpty || data == '[DONE]') return null;

    try {
      final chunk = jsonDecode(data);
      if (chunk is! Map) return null;
      final choices = chunk['choices'];
      if (choices is! List || choices.isEmpty) return null;
      return (choices.first['delta']?['content'] ?? '').toString();
    } on FormatException {
      return null;
    }
  }

  static Future<String> _drain(Response<ResponseBody> response) async {
    final bytes = <int>[];
    await for (final chunk in response.data!.stream) {
      bytes.addAll(chunk);
      if (bytes.length > 4096) break;
    }
    return utf8.decode(bytes, allowMalformed: true);
  }

  static ApiException _statusError(int? status, String body) {
    // Surface the two failures a self-hosted key actually hits, in words the
    // user can act on rather than an HTTP code.
    final message = switch (status) {
      401 => 'מפתח ה-API אינו תקף. בדוק אותו בהגדרות',
      402 => 'אין יתרה בחשבון DeepSeek. יש לטעון קרדיט',
      429 => 'יותר מדי בקשות ל-DeepSeek. המתן רגע ונסה שוב',
      400 => 'הבקשה נדחתה על ידי שירות ה-AI',
      _ => 'שירות ה-AI אינו זמין כעת',
    };
    return ApiException(
      code: 'deepseek_error',
      message: message,
      statusCode: status,
      details: {'body': body.substring(0, body.length.clamp(0, 300))},
    );
  }

  static ApiException _translate(DioException error) => switch (error.type) {
        DioExceptionType.connectionError => const ApiException(
            code: 'offline',
            message: 'אין חיבור לאינטרנט',
          ),
        DioExceptionType.receiveTimeout ||
        DioExceptionType.connectionTimeout ||
        DioExceptionType.sendTimeout =>
          const ApiException(
            code: 'timeout',
            message: 'שירות ה-AI לא הגיב בזמן',
          ),
        DioExceptionType.cancel => const ApiException(
            code: 'cancelled',
            message: 'הפעולה בוטלה',
          ),
        _ => ApiException.fromDio(error),
      };
}
