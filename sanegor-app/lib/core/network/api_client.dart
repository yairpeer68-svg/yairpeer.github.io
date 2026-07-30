import 'dart:async';

import 'package:dio/dio.dart';

import '../config/app_config.dart';
import '../storage/secure_store.dart';
import 'api_exception.dart';

/// HTTP client for the Sanegor backend.
///
/// Handles bearer-token injection and transparent refresh. The refresh is
/// serialised through a single future so that N concurrent 401s trigger one
/// refresh, not N — otherwise the backend's single-use rotation would
/// invalidate the token the other requests are about to use.
class ApiClient {
  ApiClient({required SecureStore store, Dio? dio})
      : _store = store,
        _dio = dio ?? Dio() {
    _dio.options = BaseOptions(
      baseUrl: AppConfig.apiUrl,
      connectTimeout: AppConfig.connectTimeout,
      receiveTimeout: AppConfig.receiveTimeout,
      headers: {'Accept': 'application/json'},
      // Let the interceptor decide what counts as a failure.
      validateStatus: (status) => status != null && status < 500,
    );
    _dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: _onRequest,
        onResponse: _onResponse,
        onError: _onError,
      ),
    );
  }

  final Dio _dio;
  final SecureStore _store;

  Future<void>? _refreshInFlight;

  /// Invoked when the session cannot be recovered; the app routes to login.
  void Function()? onSessionExpired;

  Dio get raw => _dio;

  Future<void> _onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (options.extra['skipAuth'] != true) {
      final token = await _store.readAccessToken();
      if (token != null && token.isNotEmpty) {
        options.headers['Authorization'] = 'Bearer $token';
      }
    }
    handler.next(options);
  }

  void _onResponse(Response<dynamic> response, ResponseInterceptorHandler handler) {
    final status = response.statusCode ?? 0;
    if (status >= 400) {
      // validateStatus lets 4xx through so it can be converted here into the
      // same DioException shape a 5xx produces.
      handler.reject(
        DioException(
          requestOptions: response.requestOptions,
          response: response,
          type: DioExceptionType.badResponse,
        ),
        true,
      );
      return;
    }
    handler.next(response);
  }

  Future<void> _onError(
    DioException error,
    ErrorInterceptorHandler handler,
  ) async {
    final isAuthFailure = error.response?.statusCode == 401;
    final alreadyRetried = error.requestOptions.extra['retried'] == true;
    final isAuthRoute = error.requestOptions.path.startsWith('/auth/');

    if (!isAuthFailure || alreadyRetried || isAuthRoute) {
      handler.next(error);
      return;
    }

    final refreshed = await _refreshSession();
    if (!refreshed) {
      await _store.clear();
      onSessionExpired?.call();
      handler.next(error);
      return;
    }

    try {
      final options = error.requestOptions;
      options.extra['retried'] = true;
      final token = await _store.readAccessToken();
      if (token != null) options.headers['Authorization'] = 'Bearer $token';
      handler.resolve(await _dio.fetch<dynamic>(options));
    } on DioException catch (retryError) {
      handler.next(retryError);
    }
  }

  /// Refresh the session, coalescing concurrent callers onto one attempt.
  Future<bool> _refreshSession() async {
    if (_refreshInFlight != null) {
      await _refreshInFlight;
      return (await _store.readAccessToken())?.isNotEmpty ?? false;
    }

    final completer = Completer<void>();
    _refreshInFlight = completer.future;
    try {
      final refreshToken = await _store.readRefreshToken();
      if (refreshToken == null || refreshToken.isEmpty) return false;

      final response = await Dio(
        BaseOptions(
          baseUrl: AppConfig.apiUrl,
          connectTimeout: AppConfig.connectTimeout,
        ),
      ).post<Map<String, dynamic>>(
        '/auth/refresh',
        data: {'refresh_token': refreshToken},
      );

      final tokens = response.data?['tokens'];
      if (tokens is! Map) return false;

      await _store.saveTokens(
        accessToken: tokens['access_token'] as String,
        refreshToken: tokens['refresh_token'] as String,
      );
      final user = response.data?['user'];
      if (user is Map<String, dynamic>) await _store.saveUser(user);
      return true;
    } on DioException {
      return false;
    } finally {
      completer.complete();
      _refreshInFlight = null;
    }
  }

  // ------------------------------------------------------------------ verbs
  Future<Map<String, dynamic>> get(
    String path, {
    Map<String, dynamic>? query,
    bool skipAuth = false,
  }) =>
      _request(() => _dio.get<dynamic>(
            path,
            queryParameters: query,
            options: Options(extra: {'skipAuth': skipAuth}),
          ));

  Future<List<dynamic>> getList(
    String path, {
    Map<String, dynamic>? query,
  }) async {
    try {
      final response = await _dio.get<dynamic>(path, queryParameters: query);
      final data = response.data;
      return data is List ? data : const [];
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }

  Future<Map<String, dynamic>> post(
    String path, {
    Object? body,
    Map<String, dynamic>? query,
    bool skipAuth = false,
  }) =>
      _request(() => _dio.post<dynamic>(
            path,
            data: body,
            queryParameters: query,
            options: Options(extra: {'skipAuth': skipAuth}),
          ));

  Future<Map<String, dynamic>> patch(String path, {Object? body}) =>
      _request(() => _dio.patch<dynamic>(path, data: body));

  Future<Map<String, dynamic>> delete(String path) =>
      _request(() => _dio.delete<dynamic>(path));

  /// Upload a file as multipart. [onProgress] reports 0.0–1.0.
  Future<Map<String, dynamic>> upload(
    String path, {
    required String filePath,
    required String filename,
    void Function(double progress)? onProgress,
    CancelToken? cancelToken,
  }) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath, filename: filename),
    });
    return _request(
      () => _dio.post<dynamic>(
        path,
        data: form,
        cancelToken: cancelToken,
        // Uploads may be followed by OCR, which is slow.
        options: Options(receiveTimeout: AppConfig.streamTimeout),
        onSendProgress: (sent, total) {
          if (total > 0) onProgress?.call(sent / total);
        },
      ),
    );
  }

  /// Download binary content (exports, original files).
  Future<({List<int> bytes, String? contentType})> download(
    String path, {
    Object? body,
  }) async {
    try {
      final response = await _dio.post<List<int>>(
        path,
        data: body,
        options: Options(
          responseType: ResponseType.bytes,
          receiveTimeout: AppConfig.streamTimeout,
        ),
      );
      return (
        bytes: response.data ?? const <int>[],
        contentType: response.headers.value('content-type'),
      );
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }

  Future<Map<String, dynamic>> _request(
    Future<Response<dynamic>> Function() send,
  ) async {
    try {
      final response = await send();
      final data = response.data;
      if (data is Map<String, dynamic>) return data;
      if (data is Map) return Map<String, dynamic>.from(data);
      return const {};
    } on DioException catch (error) {
      throw ApiException.fromDio(error);
    }
  }
}
