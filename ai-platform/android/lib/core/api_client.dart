import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'config.dart';
import 'ids.dart';
import 'storage.dart';

class _DerRange {
  final int start;
  final int end;
  const _DerRange(this.start, this.end);
}

class ApiException implements Exception {
  final String message;
  final String? code;
  final int? status;
  const ApiException(this.message, {this.code, this.status});
  @override
  String toString() => message;
}

class ApiClient {
  final Dio dio;
  final Dio refreshDio;
  final SecureTokenStore store;
  final InstallationId installation;
  final Random _secureRandom = Random.secure();
  Completer<bool>? _refreshing;
  bool onAuthenticationLostCalled = false;
  void Function()? onAuthenticationLost;

  /// Point every future request at a new server. Called after the operator saves a
  /// server address, so the change takes effect without restarting the app.
  void updateBaseUrl(String value) {
    dio.options.baseUrl = value;
    refreshDio.options.baseUrl = value;
  }

  ApiClient(this.store, this.installation)
      : dio = Dio(BaseOptions(
            baseUrl: AppConfig.defaultApiBaseUrl,
            connectTimeout: const Duration(seconds: 10),
            receiveTimeout: const Duration(seconds: 45),
            sendTimeout: const Duration(seconds: 15))),
        refreshDio = Dio(BaseOptions(
            baseUrl: AppConfig.defaultApiBaseUrl,
            connectTimeout: const Duration(seconds: 10),
            receiveTimeout: const Duration(seconds: 20))) {
    _configurePinning(dio);
    _configurePinning(refreshDio);
    dio.interceptors
        .add(InterceptorsWrapper(onRequest: _onRequest, onError: _onError));
  }

  /// Public-key (SPKI) pinning.
  ///
  /// Pinning the whole leaf certificate breaks on every renewal, and the deployment
  /// documents Let's Encrypt (60-90 day rotation), so a leaf pin took the entire
  /// installed base offline every cycle. The SPKI survives renewal whenever the key
  /// is reused, and CURRENT/NEXT still allow a planned key rotation.
  void _configurePinning(Dio target) {
    if (!AppConfig.pinningEnabled) return;
    final pins = {
      AppConfig.currentPin.toLowerCase(),
      AppConfig.nextPin.toLowerCase()
    }..remove('');
    if (pins.isEmpty) return;
    final adapter = IOHttpClientAdapter();
    adapter.validateCertificate =
        (X509Certificate? cert, String host, int port) {
      if (cert == null) return false;
      final spki = _spkiSha256(cert.der);
      if (spki != null && pins.contains(spki)) return true;
      // Accept a full-certificate pin too, so an existing deployment can migrate its
      // configured pins without a forced client update.
      return pins.contains(sha256.convert(cert.der).toString().toLowerCase());
    };
    target.httpClientAdapter = adapter;
  }

  /// Extracts SubjectPublicKeyInfo from a DER certificate and returns its SHA-256.
  ///
  /// Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
  /// TBSCertificate ::= SEQUENCE { [0] version, serialNumber, signature, issuer,
  ///                               validity, subject, subjectPublicKeyInfo, ... }
  static String? _spkiSha256(List<int> der) {
    try {
      final bytes = Uint8List.fromList(der);
      final certBody = _derContents(bytes, 0);
      if (certBody == null) return null;
      final tbs = _derContents(bytes, certBody.start);
      if (tbs == null) return null;

      var offset = tbs.start;
      // Skip the optional [0] EXPLICIT version tag.
      if (offset < tbs.end && bytes[offset] == 0xA0) {
        final version = _derContents(bytes, offset);
        if (version == null) return null;
        offset = version.end;
      }
      // serialNumber, signature, issuer, validity, subject — then SPKI.
      for (var i = 0; i < 5; i++) {
        final field = _derContents(bytes, offset);
        if (field == null) return null;
        offset = field.end;
      }
      final spki = _derContents(bytes, offset);
      if (spki == null || bytes[offset] != 0x30) return null;
      return sha256
          .convert(bytes.sublist(offset, spki.end))
          .toString()
          .toLowerCase();
    } catch (_) {
      return null;
    }
  }

  /// Parses one DER TLV header at [start], returning the content bounds.
  static _DerRange? _derContents(Uint8List bytes, int start) {
    if (start + 1 >= bytes.length) return null;
    var cursor = start + 1;
    var length = bytes[cursor++];
    if (length & 0x80 != 0) {
      final count = length & 0x7f;
      if (count == 0 || count > 4 || cursor + count > bytes.length) return null;
      length = 0;
      for (var i = 0; i < count; i++) {
        length = (length << 8) | bytes[cursor++];
      }
    }
    final end = cursor + length;
    if (end > bytes.length) return null;
    return _DerRange(cursor, end);
  }

  Future<void> _onRequest(
      RequestOptions options, RequestInterceptorHandler handler) async {
    final token = await store.accessToken();
    final serverDevice = await store.serverDeviceId();
    options.headers['X-Request-ID'] = _uuidV4();
    if (serverDevice != null) options.headers['X-Device-ID'] = serverDevice;
    if (token != null) options.headers['Authorization'] = 'Bearer $token';
    handler.next(options);
  }

  Future<void> _onError(
      DioException error, ErrorInterceptorHandler handler) async {
    final status = error.response?.statusCode;
    final alreadyRetried = error.requestOptions.extra['authRetried'] == true;
    final isRefreshEndpoint =
        error.requestOptions.path.contains('/auth/refresh');
    if (status == 401 && !alreadyRetried && !isRefreshEndpoint) {
      final ok = await _refresh();
      if (ok) {
        final copy = error.requestOptions;
        copy.extra['authRetried'] = true;
        final token = await store.accessToken();
        copy.headers['Authorization'] = 'Bearer $token';
        try {
          return handler.resolve(await dio.fetch(copy));
        } catch (_) {}
      }
    }
    final networkError = error.type == DioExceptionType.connectionError ||
        error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout;
    final retrySafe = error.requestOptions.method.toUpperCase() == 'GET' &&
        error.requestOptions.extra['networkRetried'] != true;
    if (networkError && retrySafe) {
      await Future<void>.delayed(const Duration(milliseconds: 350));
      final copy = error.requestOptions..extra['networkRetried'] = true;
      try {
        return handler.resolve(await dio.fetch(copy));
      } catch (_) {}
    }
    handler.next(error);
  }

  Future<bool> _refresh() async {
    if (_refreshing != null) return _refreshing!.future;
    _refreshing = Completer<bool>();
    try {
      final token = await store.refreshToken();
      if (token == null) {
        _authenticationLost();
        _refreshing!.complete(false);
        return false;
      }
      final response = await refreshDio.post('/auth/refresh',
          data: {'refresh_token': token},
          options: Options(headers: {'X-Request-ID': _uuidV4()}));
      final data = Map<String, dynamic>.from(response.data as Map);
      await store.writeTokens(
          data['access_token'] as String, data['refresh_token'] as String);
      _refreshing!.complete(true);
      return true;
    } on DioException {
      await store.clearTokens();
      _authenticationLost();
      _refreshing!.complete(false);
      return false;
    } finally {
      _refreshing = null;
    }
  }

  void _authenticationLost() {
    if (!onAuthenticationLostCalled) {
      onAuthenticationLostCalled = true;
      onAuthenticationLost?.call();
    }
  }

  /// Call after a successful sign-in so a later session loss notifies the UI again.
  void resetAuthenticationLatch() {
    onAuthenticationLostCalled = false;
  }

  /// Multipart upload used for engineering project archives.
  Future<Map<String, dynamic>> uploadFile(
      String path, String filePath, String fileName) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath, filename: fileName),
    });
    final response = await dio.post<dynamic>(
      path,
      data: form,
      options: Options(
          sendTimeout: const Duration(minutes: 10),
          receiveTimeout: const Duration(minutes: 5)),
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  String _uuidV4() {
    final b = List<int>.generate(16, (_) => _secureRandom.nextInt(256));
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    String h(int i) => b[i].toRadixString(16).padLeft(2, '0');
    return '${h(0)}${h(1)}${h(2)}${h(3)}-${h(4)}${h(5)}-${h(6)}${h(7)}-${h(8)}${h(9)}-${h(10)}${h(11)}${h(12)}${h(13)}${h(14)}${h(15)}';
  }

  ApiException mapError(Object error) {
    if (error is DioException) {
      final data = error.response?.data;
      if (data is Map && data['error'] is Map) {
        final e = Map<String, dynamic>.from(data['error'] as Map);
        return ApiException((e['message'] as String?) ?? 'Request failed',
            code: e['code'] as String?, status: error.response?.statusCode);
      }
      if (error.type == DioExceptionType.connectionTimeout ||
          error.type == DioExceptionType.receiveTimeout) {
        return const ApiException('The server took too long to respond.');
      }
      if (error.type == DioExceptionType.connectionError) {
        return const ApiException('No connection to the server.');
      }
      return ApiException(
          'Request failed (${error.response?.statusCode ?? 'network'})',
          status: error.response?.statusCode);
    }
    return const ApiException('Unexpected error');
  }
}
