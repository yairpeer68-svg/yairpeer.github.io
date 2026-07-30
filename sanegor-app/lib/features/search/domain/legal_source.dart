import 'package:flutter/foundation.dart';

/// A record in the legal corpus.
@immutable
class LegalSource {
  const LegalSource({
    required this.id,
    required this.citationKey,
    required this.title,
    required this.sourceType,
    required this.domain,
    this.shortTitle,
    this.caseNumber,
    this.court,
    this.judges = const [],
    this.parties,
    this.proceedingType,
    this.publishedAt,
    this.sourceUrl,
    this.publisher = '',
    this.chunkCount = 0,
  });

  final String id;
  final String citationKey;
  final String title;
  final String sourceType;
  final String domain;
  final String? shortTitle;
  final String? caseNumber;
  final String? court;
  final List<String> judges;
  final String? parties;
  final String? proceedingType;
  final DateTime? publishedAt;
  final String? sourceUrl;
  final String publisher;
  final int chunkCount;

  factory LegalSource.fromJson(Map<String, dynamic> json) => LegalSource(
        id: (json['id'] ?? '').toString(),
        citationKey: (json['citation_key'] ?? '').toString(),
        title: (json['title'] ?? '').toString(),
        sourceType: (json['source_type'] ?? 'other').toString(),
        domain: (json['domain'] ?? 'other').toString(),
        shortTitle: json['short_title']?.toString(),
        caseNumber: json['case_number']?.toString(),
        court: json['court']?.toString(),
        judges: (json['judges'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(growable: false),
        parties: json['parties']?.toString(),
        proceedingType: json['proceeding_type']?.toString(),
        publishedAt: DateTime.tryParse((json['published_at'] ?? '').toString()),
        sourceUrl: json['source_url']?.toString(),
        publisher: (json['publisher'] ?? '').toString(),
        chunkCount: (json['chunk_count'] as num?)?.toInt() ?? 0,
      );
}

/// A matched passage inside a source.
@immutable
class LegalPassage {
  const LegalPassage({
    required this.source,
    required this.snippet,
    required this.score,
    this.heading,
  });

  final LegalSource source;
  final String snippet;
  final double score;
  final String? heading;

  factory LegalPassage.fromJson(Map<String, dynamic> json) => LegalPassage(
        source: LegalSource.fromJson(
          Map<String, dynamic>.from(json['source'] as Map? ?? const {}),
        ),
        snippet: (json['snippet'] ?? '').toString(),
        score: (json['score'] as num?)?.toDouble() ?? 0,
        heading: json['heading']?.toString(),
      );
}

/// Result of a corpus search.
///
/// [corpusEmpty] distinguishes "nothing has been loaded into this deployment"
/// from "no match" — a distinction the user needs, because the first is a
/// configuration state and the second is a legal one.
@immutable
class LegalSearchResult {
  const LegalSearchResult({
    this.sources = const [],
    this.passages = const [],
    this.total = 0,
    this.corpusEmpty = false,
    this.notice,
  });

  final List<LegalSource> sources;
  final List<LegalPassage> passages;
  final int total;
  final bool corpusEmpty;
  final String? notice;

  bool get isEmpty => sources.isEmpty && passages.isEmpty;

  factory LegalSearchResult.fromJson(Map<String, dynamic> json) =>
      LegalSearchResult(
        sources: (json['sources'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(LegalSource.fromJson)
            .toList(growable: false),
        passages: (json['passages'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(LegalPassage.fromJson)
            .toList(growable: false),
        total: (json['total'] as num?)?.toInt() ?? 0,
        corpusEmpty: json['corpus_empty'] == true,
        notice: json['notice']?.toString(),
      );
}

/// Search filters mirroring the backend's request model.
@immutable
class SearchFilters {
  const SearchFilters({
    this.sourceTypes = const [],
    this.domains = const [],
    this.courts = const [],
    this.proceedingType,
    this.dateFrom,
    this.dateTo,
  });

  final List<String> sourceTypes;
  final List<String> domains;
  final List<String> courts;
  final String? proceedingType;
  final DateTime? dateFrom;
  final DateTime? dateTo;

  bool get isEmpty =>
      sourceTypes.isEmpty &&
      domains.isEmpty &&
      courts.isEmpty &&
      proceedingType == null &&
      dateFrom == null &&
      dateTo == null;

  int get activeCount =>
      sourceTypes.length +
      domains.length +
      courts.length +
      (proceedingType != null ? 1 : 0) +
      (dateFrom != null ? 1 : 0) +
      (dateTo != null ? 1 : 0);

  Map<String, dynamic> toJson() => {
        if (sourceTypes.isNotEmpty) 'source_types': sourceTypes,
        if (domains.isNotEmpty) 'domains': domains,
        if (courts.isNotEmpty) 'courts': courts,
        if (proceedingType != null) 'proceeding_type': proceedingType,
        if (dateFrom != null)
          'date_from': dateFrom!.toIso8601String().split('T').first,
        if (dateTo != null) 'date_to': dateTo!.toIso8601String().split('T').first,
      };

  SearchFilters copyWith({
    List<String>? sourceTypes,
    List<String>? domains,
    List<String>? courts,
    String? proceedingType,
    DateTime? dateFrom,
    DateTime? dateTo,
    bool clearDates = false,
  }) =>
      SearchFilters(
        sourceTypes: sourceTypes ?? this.sourceTypes,
        domains: domains ?? this.domains,
        courts: courts ?? this.courts,
        proceedingType: proceedingType ?? this.proceedingType,
        dateFrom: clearDates ? null : (dateFrom ?? this.dateFrom),
        dateTo: clearDates ? null : (dateTo ?? this.dateTo),
      );
}

/// Labels for the filter chips.
abstract final class LegalTaxonomy {
  static const sourceTypes = {
    'legislation': 'חקיקה',
    'regulation': 'תקנות',
    'ruling': 'פסיקה',
    'guideline': 'הנחיות',
    'form': 'טפסים',
  };

  static const courts = {
    'supreme': 'עליון',
    'district': 'מחוזי',
    'magistrate': 'שלום',
    'labor_national': 'ארצי לעבודה',
    'labor_regional': 'אזורי לעבודה',
    'family': 'משפחה',
    'traffic': 'תעבורה',
    'administrative': 'מנהליים',
  };

  static const domains = {
    'civil': 'אזרחי',
    'contracts': 'חוזים',
    'labor': 'עבודה',
    'family': 'משפחה',
    'criminal': 'פלילי',
    'administrative': 'מנהלי',
    'tenancy': 'שכירות',
    'consumer': 'צרכנות',
    'corporate': 'תאגידים',
    'torts': 'נזיקין',
    'privacy': 'פרטיות',
    'tax': 'מיסים',
  };
}
