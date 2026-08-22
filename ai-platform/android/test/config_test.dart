import 'package:ai_platform/core/app_info.dart';
import 'package:ai_platform/core/config.dart';
import 'package:ai_platform/core/i18n.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AppConfig', () {
    test('default API base URL is parseable', () {
      final uri = Uri.parse(AppConfig.defaultApiBaseUrl);
      expect(uri.hasScheme, isTrue);
      expect(uri.hasAuthority, isTrue);
    });

    test('validate accepts the shipped configuration', () {
      expect(AppConfig.validate, returnsNormally);
    });
  });

  group('AppInfo.compare', () {
    test('orders released versions', () {
      expect(AppInfo.compare('2.1.0', '2.0.0'), greaterThan(0));
      expect(AppInfo.compare('2.0.0', '2.1.0'), lessThan(0));
      expect(AppInfo.compare('2.1.0', '2.1.0'), equals(0));
    });

    test('ignores build and pre-release suffixes', () {
      expect(AppInfo.compare('2.1.0+3', '2.1.0'), equals(0));
      expect(AppInfo.compare('2.1.0-beta.1', '2.1.0'), equals(0));
    });

    test(
        'the shipped version is not below itself, so the update gate cannot self-block',
        () {
      expect(AppInfo.compare(AppInfo.version, AppInfo.version), equals(0));
      expect(AppInfo.version, equals('2.1.1'));
    });
  });

  group('AppConfig.normalize', () {
    test('adds the scheme and the API path', () {
      expect(AppConfig.normalize('api.example.com'),
          'https://api.example.com/api/v1');
    });

    test('keeps an address that already carries the API path', () {
      expect(AppConfig.normalize('https://api.example.com/api/v1'),
          'https://api.example.com/api/v1');
    });

    test('trims whitespace and trailing slashes', () {
      expect(AppConfig.normalize('  https://api.example.com//  '),
          'https://api.example.com/api/v1');
    });
  });

  group('AppConfig.validationError', () {
    test('accepts an https address', () {
      expect(AppConfig.validationError('api.example.com'), isNull);
    });

    test('rejects http, which the release manifest blocks outright', () {
      expect(AppConfig.validationError('http://api.example.com'),
          'serverNeedsHttps');
    });

    test('rejects an unparseable address', () {
      expect(AppConfig.validationError('https://'), 'serverInvalid');
    });
  });

  group('Strings', () {
    test('every Hebrew key has an English counterpart', () {
      const he = Strings(Locale('he'));
      const en = Strings(Locale('en'));
      for (final key in [
        'app',
        'login',
        'darkMode',
        'approve',
        'reject',
        'importArchive',
        'noRuns'
      ]) {
        expect(he.t(key), isNot(equals(key)),
            reason: 'missing Hebrew for $key');
        expect(en.t(key), isNot(equals(key)),
            reason: 'missing English for $key');
      }
    });

    test('falls back to English rather than throwing on an unknown key', () {
      const he = Strings(Locale('he'));
      expect(he.t('definitely_not_a_key'), equals('definitely_not_a_key'));
    });

    test('resolves the Hebrew table for the he locale', () {
      const he = Strings(Locale('he'));
      const en = Strings(Locale('en'));
      expect(he.t('login'), isNot(equals(en.t('login'))));
    });
  });
}
