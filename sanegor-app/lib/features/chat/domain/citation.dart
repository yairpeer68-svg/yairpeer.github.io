import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';

/// A legal source backing an answer.
///
/// The backend only returns citations that resolve to a row in its corpus, so
/// a [Citation] in the app is always something the user can open and verify.
@immutable
class Citation {
  const Citation({
    required this.index,
    required this.citationKey,
    required this.title,
    required this.sourceType,
    this.heading,
    this.court,
    this.caseNumber,
    this.publishedAt,
    this.url,
    this.snippet = '',
    this.score = 0,
  });

  final int index;
  final String citationKey;
  final String title;
  final String sourceType;
  final String? heading;
  final String? court;
  final String? caseNumber;
  final String? publishedAt;
  final String? url;
  final String snippet;
  final double score;

  factory Citation.fromJson(Map<String, dynamic> json) => Citation(
        index: (json['index'] as num?)?.toInt() ?? 0,
        citationKey: (json['citation_key'] ?? '').toString(),
        title: (json['title'] ?? '').toString(),
        sourceType: (json['source_type'] ?? 'other').toString(),
        heading: json['heading']?.toString(),
        court: json['court']?.toString(),
        caseNumber: json['case_number']?.toString(),
        publishedAt: json['published_at']?.toString(),
        url: json['url']?.toString(),
        snippet: (json['snippet'] ?? '').toString(),
        score: (json['score'] as num?)?.toDouble() ?? 0,
      );

  Map<String, dynamic> toJson() => {
        'index': index,
        'citation_key': citationKey,
        'title': title,
        'source_type': sourceType,
        'heading': heading,
        'court': court,
        'case_number': caseNumber,
        'published_at': publishedAt,
        'url': url,
        'snippet': snippet,
        'score': score,
      };

  String get typeLabel => switch (sourceType) {
        'legislation' => 'חקיקה',
        'regulation' => 'תקנות',
        'ruling' => 'פסיקה',
        'guideline' => 'הנחיות',
        'form' => 'טופס',
        _ => 'מקור',
      };

  Color get typeColor => switch (sourceType) {
        'legislation' => AppColors.legislation,
        'regulation' => AppColors.regulation,
        'ruling' => AppColors.ruling,
        'guideline' => AppColors.guideline,
        _ => AppColors.indigoLight,
      };

  IconData get typeIcon => switch (sourceType) {
        'legislation' => Icons.account_balance_outlined,
        'regulation' => Icons.rule_outlined,
        'ruling' => Icons.gavel_outlined,
        'guideline' => Icons.menu_book_outlined,
        'form' => Icons.description_outlined,
        _ => Icons.article_outlined,
      };

  String get courtLabel => switch (court) {
        'supreme' => 'בית המשפט העליון',
        'district' => 'בית משפט מחוזי',
        'magistrate' => 'בית משפט שלום',
        'labor_national' => 'בית הדין הארצי לעבודה',
        'labor_regional' => 'בית דין אזורי לעבודה',
        'family' => 'בית משפט לענייני משפחה',
        'traffic' => 'בית משפט לתעבורה',
        'administrative' => 'בית משפט לעניינים מנהליים',
        _ => '',
      };

  /// One-line attribution shown under the source title.
  String get subtitle {
    final parts = <String>[
      if (heading != null && heading!.isNotEmpty) heading!,
      if (caseNumber != null && caseNumber!.isNotEmpty) caseNumber!,
      if (courtLabel.isNotEmpty) courtLabel,
      if (publishedAt != null && publishedAt!.isNotEmpty)
        publishedAt!.split('-').first,
    ];
    return parts.join(' · ');
  }
}
