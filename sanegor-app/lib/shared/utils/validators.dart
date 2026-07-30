/// Form validators returning Hebrew messages.
///
/// The rules mirror the backend's so the user is told about a problem before
/// a round trip, but the server remains the authority — nothing here is a
/// security control.
abstract final class Validators {
  static final _emailPattern = RegExp(
    r"^[\w.!#$%&'*+/=?^`{|}~-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$",
  );

  /// Israeli mobile and landline numbers, with or without +972.
  static final _phonePattern = RegExp(r'^(\+972|0)([23489]|5\d|7\d)\d{7}$');

  static String? email(String? value) {
    final input = value?.trim() ?? '';
    if (input.isEmpty) return 'יש להזין כתובת דוא״ל';
    if (!_emailPattern.hasMatch(input)) return 'כתובת הדוא״ל אינה תקינה';
    return null;
  }

  static String? password(String? value) {
    final input = value ?? '';
    if (input.isEmpty) return 'יש להזין סיסמה';
    if (input.length < 10) return 'הסיסמה חייבת להכיל לפחות 10 תווים';
    if (!input.contains(RegExp(r'\d'))) return 'הסיסמה חייבת להכיל ספרה';
    if (!input.contains(RegExp('[A-Za-zא-ת]'))) {
      return 'הסיסמה חייבת להכיל אות';
    }
    return null;
  }

  static String? Function(String?) confirmPassword(String Function() original) =>
      (value) => value == original() ? null : 'הסיסמאות אינן תואמות';

  static String? fullName(String? value) {
    final input = value?.trim() ?? '';
    if (input.length < 2) return 'יש להזין שם מלא';
    if (input.length > 120) return 'השם ארוך מדי';
    return null;
  }

  static String? phone(String? value) {
    final input = (value ?? '').replaceAll(RegExp(r'[\s-]'), '');
    if (input.isEmpty) return null; // optional field
    if (!_phonePattern.hasMatch(input)) return 'מספר הטלפון אינו תקין';
    return null;
  }

  static String? Function(String?) required(String message) =>
      (value) => (value?.trim().isEmpty ?? true) ? message : null;

  static String? Function(String?) maxLength(int limit) =>
      (value) => (value?.length ?? 0) > limit ? 'הטקסט ארוך מדי' : null;

  /// Positive amount, accepting a comma as a thousands separator.
  static String? amount(String? value) {
    final input = (value ?? '').replaceAll(',', '').trim();
    if (input.isEmpty) return null;
    final parsed = double.tryParse(input);
    if (parsed == null) return 'יש להזין מספר';
    if (parsed < 0) return 'הסכום אינו יכול להיות שלילי';
    return null;
  }

  /// Israeli ID check digit (Luhn-like, weights alternating 1 and 2).
  static String? israeliId(String? value) {
    final input = (value ?? '').replaceAll(RegExp(r'\D'), '');
    if (input.isEmpty) return null;
    if (input.length > 9) return 'מספר הזהות אינו תקין';

    final padded = input.padLeft(9, '0');
    var total = 0;
    for (var index = 0; index < 9; index++) {
      final digit = int.parse(padded[index]) * (index.isEven ? 1 : 2);
      total += digit > 9 ? digit - 9 : digit;
    }
    return total % 10 == 0 ? null : 'מספר הזהות אינו תקין';
  }
}
