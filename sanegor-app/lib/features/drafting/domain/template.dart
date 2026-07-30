import 'package:flutter/material.dart';

/// Input types the backend can ask the client to render.
enum TemplateFieldType {
  text,
  multiline,
  number,
  date,
  currency,
  select,
  boolean;

  static TemplateFieldType fromKey(String? key) => switch (key) {
        'multiline' => TemplateFieldType.multiline,
        'number' => TemplateFieldType.number,
        'date' => TemplateFieldType.date,
        'currency' => TemplateFieldType.currency,
        'select' => TemplateFieldType.select,
        'boolean' => TemplateFieldType.boolean,
        _ => TemplateFieldType.text,
      };
}

@immutable
class TemplateField {
  const TemplateField({
    required this.key,
    required this.label,
    required this.type,
    this.required = false,
    this.hint,
    this.options = const [],
  });

  final String key;
  final String label;
  final TemplateFieldType type;
  final bool required;
  final String? hint;
  final List<String> options;

  factory TemplateField.fromJson(Map<String, dynamic> json) => TemplateField(
        key: (json['key'] ?? '').toString(),
        label: (json['label'] ?? '').toString(),
        type: TemplateFieldType.fromKey(json['type']?.toString()),
        required: json['required'] == true,
        hint: json['hint']?.toString(),
        options: (json['options'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(growable: false),
      );
}

/// A contract or letter the backend can draft.
///
/// The form is generated from [fields], so adding a document type on the
/// server needs no client release.
@immutable
class LegalTemplate {
  const LegalTemplate({
    required this.key,
    required this.name,
    required this.description,
    required this.category,
    required this.fields,
    this.icon = 'description',
    this.requiredSections = const [],
    this.legalNotes = const [],
  });

  final String key;
  final String name;
  final String description;
  final String category;
  final List<TemplateField> fields;
  final String icon;
  final List<String> requiredSections;
  final List<String> legalNotes;

  bool get isContract => category == 'contract';

  factory LegalTemplate.fromJson(Map<String, dynamic> json) => LegalTemplate(
        key: (json['key'] ?? '').toString(),
        name: (json['name'] ?? '').toString(),
        description: (json['description'] ?? '').toString(),
        category: (json['category'] ?? 'contract').toString(),
        icon: (json['icon'] ?? 'description').toString(),
        fields: (json['fields'] as List? ?? const [])
            .whereType<Map<String, dynamic>>()
            .map(TemplateField.fromJson)
            .toList(growable: false),
        requiredSections: (json['required_sections'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(growable: false),
        legalNotes: (json['legal_notes'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(growable: false),
      );

  /// Map the server's icon name onto a Material icon.
  ///
  /// A lookup table rather than a dynamic icon factory: constant IconData is
  /// required for tree-shaking, and an unknown name degrades to a default
  /// instead of throwing.
  IconData get materialIcon => switch (icon) {
        'home' => Icons.home_outlined,
        'work' => Icons.work_outline,
        'handshake' => Icons.handshake_outlined,
        'lock' => Icons.lock_outline,
        'engineering' => Icons.engineering_outlined,
        'sell' => Icons.sell_outlined,
        'trending_up' => Icons.trending_up,
        'payments' => Icons.payments_outlined,
        'construction' => Icons.construction_outlined,
        'warning' => Icons.warning_amber_outlined,
        'request_quote' => Icons.request_quote_outlined,
        'location_city' => Icons.location_city_outlined,
        'gavel' => Icons.gavel_outlined,
        'badge' => Icons.badge_outlined,
        'undo' => Icons.undo_outlined,
        'fact_check' => Icons.fact_check_outlined,
        'shield' => Icons.shield_outlined,
        'article' => Icons.article_outlined,
        _ => Icons.description_outlined,
      };
}

/// A document the backend generated.
@immutable
class GeneratedDocument {
  const GeneratedDocument({
    required this.id,
    required this.title,
    required this.bodyMarkdown,
    required this.category,
    required this.templateKey,
    this.citations = const [],
    this.missingFields = const [],
    this.createdAt,
  });

  final String id;
  final String title;
  final String bodyMarkdown;
  final String category;
  final String templateKey;
  final List<dynamic> citations;

  /// Required fields the user left blank; the draft marks them as `______`.
  final List<String> missingFields;
  final DateTime? createdAt;

  factory GeneratedDocument.fromJson(Map<String, dynamic> json) =>
      GeneratedDocument(
        id: (json['id'] ?? '').toString(),
        title: (json['title'] ?? '').toString(),
        bodyMarkdown: (json['body_markdown'] ?? '').toString(),
        category: (json['category'] ?? 'contract').toString(),
        templateKey: (json['template_key'] ?? '').toString(),
        citations: (json['citations'] as List?) ?? const [],
        missingFields: (json['missing_fields'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(growable: false),
        createdAt: DateTime.tryParse((json['created_at'] ?? '').toString()),
      );
}
