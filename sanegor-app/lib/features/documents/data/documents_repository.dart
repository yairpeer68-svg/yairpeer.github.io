import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';

import '../../../core/network/api_client.dart';
import '../domain/document.dart';

/// Uploads, analyses and exports.
class DocumentsRepository {
  const DocumentsRepository({required ApiClient client}) : _client = client;

  final ApiClient _client;

  Future<({LegalDocument document, List<String> warnings})> upload({
    required String filePath,
    required String filename,
    void Function(double progress)? onProgress,
    CancelToken? cancelToken,
  }) async {
    final response = await _client.upload(
      '/documents/upload',
      filePath: filePath,
      filename: filename,
      onProgress: onProgress,
      cancelToken: cancelToken,
    );
    return (
      document:
          LegalDocument.fromJson(response['document'] as Map<String, dynamic>),
      warnings: (response['warnings'] as List? ?? const [])
          .map((e) => e.toString())
          .toList(growable: false),
    );
  }

  Future<({List<LegalDocument> items, int total})> list({
    int limit = 20,
    int offset = 0,
    String? query,
  }) async {
    final response = await _client.get(
      '/documents',
      query: {
        'limit': limit,
        'offset': offset,
        if (query != null && query.isNotEmpty) 'query': query,
      },
    );
    return (
      items: (response['items'] as List? ?? const [])
          .whereType<Map<String, dynamic>>()
          .map(LegalDocument.fromJson)
          .toList(growable: false),
      total: (response['total'] as num?)?.toInt() ?? 0,
    );
  }

  Future<LegalDocument> get(String id) async =>
      LegalDocument.fromJson(await _client.get('/documents/$id'));

  Future<String> getText(String id) async {
    final response = await _client.get('/documents/$id/text');
    return (response['text'] ?? '').toString();
  }

  Future<void> delete(String id) => _client.delete('/documents/$id');

  // --------------------------------------------------------------- analysis
  Future<DocumentAnalysis> analyseDocument(
    String documentId, {
    String? focus,
    bool refresh = false,
  }) =>
      _analyse('/analysis/document', documentId, focus, refresh);

  Future<DocumentAnalysis> analyseContract(
    String documentId, {
    String? focus,
    bool refresh = false,
  }) =>
      _analyse('/analysis/contract', documentId, focus, refresh);

  Future<DocumentAnalysis> summariseCase(
    String documentId, {
    bool refresh = false,
  }) =>
      _analyse('/analysis/case-summary', documentId, null, refresh);

  Future<DocumentAnalysis> _analyse(
    String path,
    String documentId,
    String? focus,
    bool refresh,
  ) async =>
      DocumentAnalysis.fromJson(
        await _client.post(
          path,
          body: {
            'document_id': documentId,
            if (focus != null && focus.isNotEmpty) 'focus': focus,
            'refresh': refresh,
          },
        ),
      );

  // ----------------------------------------------------------------- export
  /// Export content and write it to a temporary file the OS can open.
  ///
  /// Returns the on-disk path so the caller can hand it to a share sheet or a
  /// viewer.
  Future<File> export({
    required String format,
    String? conversationId,
    String? generatedDocumentId,
    String? content,
    String? title,
    bool includeDisclaimer = true,
  }) async {
    final result = await _client.download(
      '/export',
      body: {
        'format': format,
        if (conversationId != null) 'conversation_id': conversationId,
        if (generatedDocumentId != null)
          'generated_document_id': generatedDocumentId,
        if (content != null) 'content': content,
        if (title != null) 'title': title,
        'include_disclaimer': includeDisclaimer,
      },
    );

    final directory = await getTemporaryDirectory();
    final safeTitle = (title ?? 'sanegor')
        .replaceAll(RegExp(r'[^\w֐-׿\- ]'), '')
        .trim()
        .replaceAll(' ', '_');
    final stamp = DateTime.now().millisecondsSinceEpoch;
    final file = File('${directory.path}/${safeTitle}_$stamp.$format');
    await file.writeAsBytes(result.bytes, flush: true);
    return file;
  }
}
