import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/config/app_config.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/providers.dart';
import '../domain/document.dart';

class DocumentsState {
  const DocumentsState({
    this.documents = const [],
    this.total = 0,
    this.isLoading = false,
    this.isUploading = false,
    this.uploadProgress = 0,
    this.error,
    this.warnings = const [],
  });

  final List<LegalDocument> documents;
  final int total;
  final bool isLoading;
  final bool isUploading;
  final double uploadProgress;
  final String? error;
  final List<String> warnings;

  DocumentsState copyWith({
    List<LegalDocument>? documents,
    int? total,
    bool? isLoading,
    bool? isUploading,
    double? uploadProgress,
    String? error,
    List<String>? warnings,
    bool clearError = false,
  }) =>
      DocumentsState(
        documents: documents ?? this.documents,
        total: total ?? this.total,
        isLoading: isLoading ?? this.isLoading,
        isUploading: isUploading ?? this.isUploading,
        uploadProgress: uploadProgress ?? this.uploadProgress,
        error: clearError ? null : (error ?? this.error),
        warnings: warnings ?? this.warnings,
      );
}

class DocumentsController extends StateNotifier<DocumentsState> {
  DocumentsController(this._ref) : super(const DocumentsState()) {
    load();
  }

  final Ref _ref;

  Future<void> load({String? query}) async {
    state = state.copyWith(isLoading: true, clearError: true);
    try {
      final result = await _ref
          .read(documentsRepositoryProvider)
          .list(query: query, limit: 50);
      state = state.copyWith(
        documents: result.items,
        total: result.total,
        isLoading: false,
      );
    } on ApiException catch (error) {
      state = state.copyWith(isLoading: false, error: error.message);
    }
  }

  /// Pick a file from storage and upload it.
  Future<LegalDocument?> pickAndUpload() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: AppConfig.allowedExtensions,
      withData: false,
    );
    final files = result?.files ?? const <PlatformFile>[];
    if (files.length != 1 || files.first.path == null) return null;
    final file = files.first;

    if (file.size > AppConfig.maxUploadBytes) {
      state = state.copyWith(
        error: 'הקובץ גדול מ-${AppConfig.maxUploadBytes ~/ (1024 * 1024)} מגה-בייט',
      );
      return null;
    }
    return _upload(file.path!, file.name);
  }

  /// Capture a photo of a document and upload it for OCR.
  Future<LegalDocument?> captureAndUpload({bool fromCamera = true}) async {
    final picked = await ImagePicker().pickImage(
      source: fromCamera ? ImageSource.camera : ImageSource.gallery,
      // Downscale before upload: a 12 MP phone photo is far more than OCR
      // needs and just costs the user bandwidth.
      maxWidth: 2400,
      imageQuality: 88,
    );
    if (picked == null) return null;
    return _upload(picked.path, picked.name);
  }

  Future<LegalDocument?> _upload(String path, String filename) async {
    state = state.copyWith(
      isUploading: true,
      uploadProgress: 0,
      clearError: true,
      warnings: const [],
    );
    try {
      final result = await _ref.read(documentsRepositoryProvider).upload(
            filePath: path,
            filename: filename,
            onProgress: (progress) =>
                state = state.copyWith(uploadProgress: progress),
          );
      state = state.copyWith(
        isUploading: false,
        uploadProgress: 1,
        documents: [result.document, ...state.documents],
        total: state.total + 1,
        warnings: result.warnings,
      );
      return result.document;
    } on ApiException catch (error) {
      state = state.copyWith(isUploading: false, error: error.message);
      return null;
    }
  }

  Future<void> delete(String documentId) async {
    final previous = state.documents;
    // Optimistic removal; restored if the server rejects it.
    state = state.copyWith(
      documents: previous.where((d) => d.id != documentId).toList(),
      total: state.total - 1,
    );
    try {
      await _ref.read(documentsRepositoryProvider).delete(documentId);
    } on ApiException catch (error) {
      state = state.copyWith(
        documents: previous,
        total: previous.length,
        error: error.message,
      );
    }
  }

  void clearError() => state = state.copyWith(clearError: true);
}

final documentsControllerProvider =
    StateNotifierProvider<DocumentsController, DocumentsState>(
  DocumentsController.new,
);

/// One document's analysis, keyed by document id and analysis kind.
final analysisProvider = FutureProvider.autoDispose
    .family<DocumentAnalysis, ({String documentId, String kind})>((ref, args) {
  final repository = ref.watch(documentsRepositoryProvider);
  return switch (args.kind) {
    'contract' => repository.analyseContract(args.documentId),
    'case_summary' => repository.summariseCase(args.documentId),
    _ => repository.analyseDocument(args.documentId),
  };
});

final documentTextProvider =
    FutureProvider.autoDispose.family<String, String>((ref, documentId) {
  return ref.watch(documentsRepositoryProvider).getText(documentId);
});
