import 'package:dio/dio.dart';

/// A failure the UI can render.
///
/// The backend returns one error envelope for everything, so this type mirrors
/// it exactly and adds the transport-level cases (offline, timeout) that never
/// reach the server at all.
class ApiException implements Exception {
  const ApiException({
    required this.code,
    required this.message,
    this.details = const {},
    this.statusCode,
    this.requestId,
  });

  final String code;
  final String message;
  final Map<String, dynamic> details;
  final int? statusCode;
  final String? requestId;

  bool get isUnauthorized => statusCode == 401 || code == 'unauthenticated';
  bool get isForbidden => statusCode == 403;
  bool get isNotFound => statusCode == 404;
  bool get isRateLimited => statusCode == 429;
  bool get isOffline => code == 'offline';

  /// Whether retrying the same request could plausibly succeed.
  bool get isRetryable =>
      isOffline ||
      code == 'timeout' ||
      statusCode == 429 ||
      (statusCode != null && statusCode! >= 500);

  int? get retryAfterSeconds {
    final value = details['retry_after_seconds'];
    return value is int ? value : null;
  }

  /// Per-field validation messages, keyed by field name.
  Map<String, String> get fieldErrors {
    final fields = details['fields'];
    if (fields is! List) return const {};
    return {
      for (final entry in fields)
        if (entry is Map && entry['field'] is String)
          entry['field'] as String: (entry['message'] ?? '').toString(),
    };
  }

  /// Build from a Dio failure, unwrapping the backend envelope when present.
  factory ApiException.fromDio(DioException error) {
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
        return const ApiException(
          code: 'timeout',
          message: 'החיבור לשרת איטי מדי. נסה שוב',
        );
      case DioExceptionType.receiveTimeout:
        return const ApiException(
          code: 'timeout',
          message: 'השרת לא הגיב בזמן. נסה שוב',
        );
      case DioExceptionType.connectionError:
        return const ApiException(
          code: 'offline',
          message: 'אין חיבור לאינטרנט',
        );
      case DioExceptionType.cancel:
        return const ApiException(code: 'cancelled', message: 'הפעולה בוטלה');
      case DioExceptionType.badCertificate:
        return const ApiException(
          code: 'bad_certificate',
          message: 'החיבור לשרת אינו מאובטח',
        );
      case DioExceptionType.badResponse:
      case DioExceptionType.unknown:
        return ApiException._fromResponse(error);
    }
  }

  factory ApiException._fromResponse(DioException error) {
    final response = error.response;
    final data = response?.data;

    if (data is Map && data['error'] is Map) {
      final envelope = Map<String, dynamic>.from(data['error'] as Map);
      return ApiException(
        code: (envelope['code'] ?? 'error').toString(),
        message: (envelope['message'] ?? 'אירעה שגיאה').toString(),
        details: envelope['details'] is Map
            ? Map<String, dynamic>.from(envelope['details'] as Map)
            : const {},
        statusCode: response?.statusCode,
        requestId: envelope['request_id']?.toString(),
      );
    }

    return ApiException(
      code: 'http_error',
      message: _messageForStatus(response?.statusCode),
      statusCode: response?.statusCode,
    );
  }

  static String _messageForStatus(int? status) => switch (status) {
        400 => 'הבקשה אינה תקינה',
        401 => 'נדרשת התחברות',
        403 => 'אין לך הרשאה לפעולה זו',
        404 => 'הפריט המבוקש לא נמצא',
        413 => 'הקובץ גדול מדי',
        415 => 'סוג הקובץ אינו נתמך',
        429 => 'יותר מדי בקשות. נסה שוב בעוד רגע',
        500 || 502 || 503 => 'השרת אינו זמין כעת. נסה שוב מאוחר יותר',
        _ => 'אירעה שגיאה בלתי צפויה',
      };

  @override
  String toString() => 'ApiException($code): $message';
}
