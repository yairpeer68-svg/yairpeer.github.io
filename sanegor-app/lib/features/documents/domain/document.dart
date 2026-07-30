import 'package:flutter/material.dart';

enum DocumentStatus {
  pending('pending', 'ממתין'),
  processing('processing', 'בעיבוד'),
  ready('ready', 'מוכן'),
  failed('failed', 'נכשל');

  const DocumentStatus(this.key, this.label);

  final String key;
  final String label;

  static DocumentStatus fromKey(String? key) => DocumentStatus.values
      .firstWhere((s) => s.key == key, orElse: () => DocumentStatus.pending);
}

@immutable
class LegalDocument {
  const LegalDocument({
    required this.id,
    required this.filename,
    required this.contentType,
    required this.sizeBytes,
    required this.status,
    required this.createdAt,
    this.pageCount,
    this.wordCount,
    this.language,
    this.usedOcr = false,
    this.error,
  });

  final String id;
  final String filename;
  final String contentType;
  final int sizeBytes;
  final DocumentStatus status;
  final DateTime createdAt;
  final int? pageCount;
  final int? wordCount;
  final String? language;
  final bool usedOcr;
  final String? error;

  bool get isReady => status == DocumentStatus.ready;

  factory LegalDocument.fromJson(Map<String, dynamic> json) => LegalDocument(
        id: (json['id'] ?? '').toString(),
        filename: (json['filename'] ?? '').toString(),
        contentType: (json['content_type'] ?? '').toString(),
        sizeBytes: (json['size_bytes'] as num?)?.toInt() ?? 0,
        status: DocumentStatus.fromKey(json['status']?.toString()),
        createdAt:
            DateTime.tryParse((json['created_at'] ?? '').toString())?.toLocal() ??
                DateTime.now(),
        pageCount: (json['page_count'] as num?)?.toInt(),
        wordCount: (json['word_count'] as num?)?.toInt(),
        language: json['language']?.toString(),
        usedOcr: json['used_ocr'] == true,
        error: json['error']?.toString(),
      );

  IconData get icon => switch (contentType) {
        'application/pdf' => Icons.picture_as_pdf_outlined,
        'text/plain' => Icons.article_outlined,
        final t when t.startsWith('image/') => Icons.image_outlined,
        _ => Icons.description_outlined,
      };

  String get sizeLabel {
    if (sizeBytes < 1024) return '$sizeBytes B';
    if (sizeBytes < 1024 * 1024) {
      return '${(sizeBytes / 1024).toStringAsFixed(0)} KB';
    }
    return '${(sizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }

  /// Compact metadata line: size, pages, OCR marker.
  String get subtitle {
    final parts = <String>[
      sizeLabel,
      if (pageCount != null) '$pageCount עמודים',
      if (wordCount != null && wordCount! > 0) '$wordCount מילים',
      if (usedOcr) 'OCR',
    ];
    return parts.join(' · ');
  }
}

/// A structured analysis result.
@immutable
class DocumentAnalysis {
  const DocumentAnalysis({
    required this.documentId,
    required this.kind,
    required this.summary,
    required this.payload,
    this.citations = const [],
    this.complexityScore,
    this.riskScore,
    this.cached = false,
  });

  final String documentId;
  final String kind;
  final String summary;
  final Map<String, dynamic> payload;
  final List<dynamic> citations;
  final int? complexityScore;
  final int? riskScore;
  final bool cached;

  factory DocumentAnalysis.fromJson(Map<String, dynamic> json) =>
      DocumentAnalysis(
        documentId: (json['document_id'] ?? '').toString(),
        kind: (json['kind'] ?? 'document').toString(),
        summary: (json['summary'] ?? '').toString(),
        payload: json['payload'] is Map
            ? Map<String, dynamic>.from(json['payload'] as Map)
            : const {},
        citations: (json['citations'] as List?) ?? const [],
        complexityScore: (json['complexity_score'] as num?)?.toInt(),
        riskScore: (json['risk_score'] as num?)?.toInt(),
        cached: json['cached'] == true,
      );

  /// Free-form markdown, present only for case summaries.
  String? get markdown => payload['markdown'] as String?;

  List<Map<String, dynamic>> _list(String key) =>
      (payload[key] as List? ?? const [])
          .whereType<Map<String, dynamic>>()
          .toList(growable: false);

  List<String> _strings(String key) => (payload[key] as List? ?? const [])
      .map((e) => e.toString())
      .toList(growable: false);

  List<Map<String, dynamic>> get risks => _list('risks');
  List<Map<String, dynamic>> get keyPoints => _list('key_points');
  List<Map<String, dynamic>> get obligations => _list('obligations');
  List<Map<String, dynamic>> get dates => _list('dates');
  List<Map<String, dynamic>> get problematicTerms => _list('problematic_terms');
  List<Map<String, dynamic>> get contradictions => _list('contradictions');
  List<String> get recommendations => _strings('recommendations');
  List<String> get negotiationPoints => _strings('negotiation_points');
  List<String> get questionsForLawyer => _strings('questions_for_lawyer');

  /// Missing clauses arrive as strings for documents and objects for contracts.
  List<String> get missingClauses {
    final raw = payload['missing_clauses'] as List? ?? const [];
    return raw
        .map(
          (item) => item is Map
              ? (item['clause'] ?? item['why'] ?? '').toString()
              : item.toString(),
        )
        .where((s) => s.isNotEmpty)
        .toList(growable: false);
  }
}
