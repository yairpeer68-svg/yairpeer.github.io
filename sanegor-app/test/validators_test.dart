import 'package:flutter_test/flutter_test.dart';
import 'package:sanegor/shared/utils/validators.dart';

void main() {
  group('email', () {
    test('accepts ordinary addresses', () {
      expect(Validators.email('dana@example.co.il'), isNull);
      expect(Validators.email('  a.b+tag@sub.domain.com  '), isNull);
    });

    test('rejects malformed addresses', () {
      for (final input in ['', 'no-at-sign', 'a@b', 'a@@b.com', '@example.com']) {
        expect(Validators.email(input), isNotNull, reason: input);
      }
    });
  });

  group('password', () {
    test('accepts a password meeting the backend policy', () {
      expect(Validators.password('Sisma-Hazaka-2026'), isNull);
    });

    test('rejects short, letterless or digitless passwords', () {
      expect(Validators.password('short1'), isNotNull);
      expect(Validators.password('1234567890123'), isNotNull);
      expect(Validators.password('onlylettershere'), isNotNull);
    });

    test('accepts a Hebrew-letter password', () {
      // The backend counts any letter, not only Latin ones.
      expect(Validators.password('סיסמהחזקה12'), isNull);
    });

    test('confirmPassword compares against the live value', () {
      var original = 'first-value-1';
      final validator = Validators.confirmPassword(() => original);
      expect(validator('first-value-1'), isNull);
      original = 'changed-value-1';
      expect(validator('first-value-1'), isNotNull);
    });
  });

  group('phone', () {
    test('accepts Israeli mobile and landline formats', () {
      for (final input in ['0501234567', '+972501234567', '036123456', '054-1234567']) {
        expect(Validators.phone(input), isNull, reason: input);
      }
    });

    test('rejects malformed numbers', () {
      for (final input in ['12345', '0601234567', '+1234567890']) {
        expect(Validators.phone(input), isNotNull, reason: input);
      }
    });

    test('is optional', () {
      expect(Validators.phone(''), isNull);
      expect(Validators.phone(null), isNull);
    });
  });

  group('israeliId', () {
    test('accepts a valid check digit', () {
      // Known-valid test identifiers.
      expect(Validators.israeliId('000000018'), isNull);
      expect(Validators.israeliId('123456782'), isNull);
    });

    test('rejects a wrong check digit', () {
      expect(Validators.israeliId('123456789'), isNotNull);
    });

    test('pads short input before validating', () {
      expect(Validators.israeliId('18'), isNull);
    });

    test('is optional', () => expect(Validators.israeliId(''), isNull));
  });

  group('amount', () {
    test('accepts numbers with thousands separators', () {
      expect(Validators.amount('4,500'), isNull);
      expect(Validators.amount('4500.50'), isNull);
    });

    test('rejects text and negatives', () {
      expect(Validators.amount('abc'), isNotNull);
      expect(Validators.amount('-100'), isNotNull);
    });
  });

  group('fullName', () {
    test('requires at least two characters', () {
      expect(Validators.fullName('דנה כהן'), isNull);
      expect(Validators.fullName('א'), isNotNull);
      expect(Validators.fullName('  '), isNotNull);
    });
  });
}
