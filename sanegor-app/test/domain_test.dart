import 'package:flutter_test/flutter_test.dart';
import 'package:sanegor/features/auth/domain/user.dart';
import 'package:sanegor/features/chat/domain/chat_message.dart';
import 'package:sanegor/features/chat/domain/citation.dart';
import 'package:sanegor/features/documents/domain/document.dart';
import 'package:sanegor/features/drafting/domain/template.dart';
import 'package:sanegor/features/search/domain/legal_source.dart';

void main() {
  group('Citation', () {
    test('parses a ruling and builds an attribution line', () {
      final citation = Citation.fromJson(const {
        'index': 1,
        'citation_key': 'ca-1234-56',
        'title': 'פלוני נגד אלמוני',
        'source_type': 'ruling',
        'court': 'supreme',
        'case_number': 'ע״א 1234/56',
        'published_at': '1998-04-12',
      });

      expect(citation.typeLabel, 'פסיקה');
      expect(citation.courtLabel, 'בית המשפט העליון');
      expect(citation.subtitle, contains('ע״א 1234/56'));
      expect(citation.subtitle, contains('1998'));
    });

    test('tolerates a payload with only the required fields', () {
      final citation = Citation.fromJson(const {'title': 'חוק כלשהו'});
      expect(citation.index, 0);
      expect(citation.typeLabel, 'מקור');
      expect(citation.subtitle, isEmpty);
    });
  });

  group('ChatMessage', () {
    test('parses an assistant turn with citations', () {
      final message = ChatMessage.fromJson(const {
        'id': 'm1',
        'role': 'assistant',
        'content': 'לפי הדין [מקור 1]',
        'created_at': '2026-01-01T10:00:00Z',
        'citations': [
          {'index': 1, 'title': 'חוק החוזים', 'source_type': 'legislation'},
        ],
      });

      expect(message.isAssistant, isTrue);
      expect(message.hasCitations, isTrue);
      expect(message.citations.single.typeLabel, 'חקיקה');
    });

    test('an empty answer carrying an error counts as failed', () {
      final message = ChatMessage(
        id: 'm2',
        role: MessageRole.assistant,
        content: '   ',
        createdAt: DateTime(2026),
        error: 'upstream_error',
      );
      expect(message.hasFailed, isTrue);
    });

    test('an answer with text and an error is not treated as failed', () {
      final message = ChatMessage(
        id: 'm3',
        role: MessageRole.assistant,
        content: 'תשובה חלקית',
        createdAt: DateTime(2026),
        error: 'upstream_error',
      );
      // A truncated answer is still worth showing.
      expect(message.hasFailed, isFalse);
    });

    test('copyWith preserves identity fields', () {
      final original = ChatMessage(
        id: 'm4',
        role: MessageRole.user,
        content: 'שאלה',
        createdAt: DateTime(2026),
      );
      final updated = original.copyWith(content: 'שאלה מעודכנת');
      expect(updated.id, original.id);
      expect(updated.role, original.role);
      expect(updated.createdAt, original.createdAt);
      expect(updated.content, 'שאלה מעודכנת');
    });
  });

  group('AppUser', () {
    test('derives initials from a Hebrew full name', () {
      const user = AppUser(
        id: '1',
        email: 'dana@example.co.il',
        fullName: 'דנה כהן',
        role: UserRole.user,
      );
      expect(user.initials, 'דכ');
    });

    test('falls back to the email when no name is set', () {
      const user = AppUser(
        id: '1',
        email: 'dana@example.co.il',
        fullName: '',
        role: UserRole.user,
      );
      expect(user.initials, 'D');
    });

    test('unknown role defaults to user', () {
      expect(UserRole.fromKey('nonsense'), UserRole.user);
      expect(UserRole.fromKey('admin'), UserRole.admin);
    });
  });

  group('LegalDocument', () {
    test('formats size and metadata', () {
      final document = LegalDocument.fromJson(const {
        'id': 'd1',
        'filename': 'חוזה.pdf',
        'content_type': 'application/pdf',
        'size_bytes': 2 * 1024 * 1024,
        'status': 'ready',
        'page_count': 12,
        'word_count': 3400,
        'used_ocr': true,
      });

      expect(document.isReady, isTrue);
      expect(document.sizeLabel, '2.0 MB');
      expect(document.subtitle, contains('12 עמודים'));
      expect(document.subtitle, contains('OCR'));
    });
  });

  group('DocumentAnalysis', () {
    test('normalises missing_clauses from both shapes', () {
      // Document analysis returns strings; contract analysis returns objects.
      final asStrings = DocumentAnalysis.fromJson(const {
        'document_id': 'd1',
        'payload': {
          'missing_clauses': ['סעיף בוררות'],
        },
      });
      final asObjects = DocumentAnalysis.fromJson(const {
        'document_id': 'd1',
        'payload': {
          'missing_clauses': [
            {'clause': 'סעיף בוררות', 'why': 'חשוב'},
          ],
        },
      });

      expect(asStrings.missingClauses, ['סעיף בוררות']);
      expect(asObjects.missingClauses, ['סעיף בוררות']);
    });

    test('an absent payload yields empty collections, not nulls', () {
      final analysis =
          DocumentAnalysis.fromJson(const {'document_id': 'd1'});
      expect(analysis.risks, isEmpty);
      expect(analysis.recommendations, isEmpty);
      expect(analysis.markdown, isNull);
    });
  });

  group('LegalTemplate', () {
    test('parses fields and maps the icon name', () {
      final template = LegalTemplate.fromJson(const {
        'key': 'rental',
        'name': 'חוזה שכירות',
        'description': 'הסכם שכירות',
        'category': 'contract',
        'icon': 'home',
        'fields': [
          {'key': 'rent', 'label': 'דמי שכירות', 'type': 'currency', 'required': true},
          {'key': 'pets', 'label': 'חיות מחמד', 'type': 'boolean'},
        ],
      });

      expect(template.isContract, isTrue);
      expect(template.fields, hasLength(2));
      expect(template.fields.first.type, TemplateFieldType.currency);
      expect(template.fields.first.required, isTrue);
      expect(template.fields[1].type, TemplateFieldType.boolean);
    });

    test('an unknown icon name degrades instead of throwing', () {
      final template = LegalTemplate.fromJson(const {
        'key': 'x',
        'name': 'x',
        'description': '',
        'category': 'contract',
        'icon': 'not-a-real-icon',
        'fields': [],
      });
      expect(template.materialIcon, isNotNull);
    });
  });

  group('SearchFilters', () {
    test('serialises only the filters that are set', () {
      const filters = SearchFilters(sourceTypes: ['ruling'], courts: ['supreme']);
      final json = filters.toJson();

      expect(json.keys, containsAll(['source_types', 'courts']));
      expect(json.containsKey('domains'), isFalse);
      expect(filters.activeCount, 2);
    });

    test('formats dates as plain ISO days', () {
      final filters = SearchFilters(dateFrom: DateTime(2020, 3, 15));
      expect(filters.toJson()['date_from'], '2020-03-15');
    });

    test('clearDates removes both bounds', () {
      final filters = SearchFilters(dateFrom: DateTime(2020), dateTo: DateTime(2021));
      expect(filters.copyWith(clearDates: true).isEmpty, isTrue);
    });
  });

  group('LegalSearchResult', () {
    test('distinguishes an empty corpus from an empty result', () {
      final emptyCorpus =
          LegalSearchResult.fromJson(const {'corpus_empty': true, 'notice': 'ריק'});
      final noMatches = LegalSearchResult.fromJson(const {'sources': []});

      expect(emptyCorpus.corpusEmpty, isTrue);
      expect(emptyCorpus.notice, isNotNull);
      expect(noMatches.corpusEmpty, isFalse);
      expect(noMatches.isEmpty, isTrue);
    });
  });
}
