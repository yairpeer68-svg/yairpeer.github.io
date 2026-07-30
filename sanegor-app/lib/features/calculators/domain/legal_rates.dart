/// Statutory parameters used by the calculators.
///
/// Every number that comes from Israeli law or from an annually-updated
/// schedule lives here, never inline in a formula. Each carries the date it
/// applies from, where it comes from, and — critically — whether it has been
/// verified against the current official source.
///
/// The reason for the [verified] flag: a wrong severance figure is worse than
/// no calculator at all, because someone will take it to their employer. When
/// a calculation depends on an unverified value the result is still shown, but
/// it is labelled, and the user is told exactly which number to check and
/// where. The arithmetic is exact; the inputs are declared, dated and
/// challengeable.
library;

/// One statutory parameter with its provenance.
class LegalRate<T> {
  const LegalRate({
    required this.value,
    required this.label,
    required this.source,
    required this.effectiveFrom,
    this.verified = false,
    this.note,
  });

  final T value;

  /// Human-readable name, shown in the calculation breakdown.
  final String label;

  /// Where to check this number — a statute, regulation, or publishing body.
  final String source;

  final DateTime effectiveFrom;

  /// True only once confirmed against the current official publication.
  /// Left false by default: an unchecked number must not look authoritative.
  final bool verified;

  final String? note;
}

/// Values that change annually and must be re-checked each year.
///
/// These are deliberately conservative placeholders. Update them from the
/// official source and flip [verified] — the calculators pick the change up
/// with no other edit, and the warning banner disappears on its own.
abstract final class LegalRates {
  /// Recuperation pay per day. Differs between the public and private
  /// sectors and is republished yearly.
  static final recuperationDayRate = LegalRate<double>(
    value: 418,
    label: 'תעריף יום הבראה',
    source: 'צו ההרחבה בדבר תשלום דמי הבראה — מתעדכן שנתית',
    effectiveFrom: DateTime(2024),
    note: 'התעריף במגזר הציבורי שונה. יש לאמת מול הצו העדכני.',
  );

  /// Arrears interest under the interest-and-linkage law, set by the
  /// Accountant General and revised periodically.
  static final arrearsInterestAnnualRate = LegalRate<double>(
    value: 0.0625,
    label: 'ריבית פיגורים שנתית',
    source: 'חוק פסיקת ריבית והצמדה — שיעור שנקבע בידי החשב הכללי',
    effectiveFrom: DateTime(2024),
    note: 'משתנה מעת לעת. יש לאמת מול הפרסום העדכני.',
  );

  /// Civil claim fee, as a share of the amount claimed.
  static final civilCourtFeeRate = LegalRate<double>(
    value: 0.025,
    label: 'שיעור אגרת תביעה אזרחית',
    source: 'תקנות בתי המשפט (אגרות)',
    effectiveFrom: DateTime(2024),
    note: 'קיימים מדרגות, מינימום ותשלום בשני שלבים. יש לאמת.',
  );

  static final smallClaimsFeeRate = LegalRate<double>(
    value: 0.01,
    label: 'שיעור אגרת תביעות קטנות',
    source: 'תקנות בתי המשפט (אגרות)',
    effectiveFrom: DateTime(2024),
  );

  static final smallClaimsCeiling = LegalRate<double>(
    value: 38900,
    label: 'תקרת תביעות קטנות',
    source: 'חוק בתי המשפט — מתעדכן לפי המדד',
    effectiveFrom: DateTime(2024),
    note: 'התקרה מתעדכנת. יש לאמת לפני הגשה.',
  );

  static final minimumCourtFee = LegalRate<double>(
    value: 350,
    label: 'אגרת מינימום',
    source: 'תקנות בתי המשפט (אגרות)',
    effectiveFrom: DateTime(2024),
  );

  /// Every rate the app can depend on, used to build the "unverified inputs"
  /// warning without each calculator having to enumerate its own.
  static List<LegalRate<Object>> get all => [
        recuperationDayRate,
        arrearsInterestAnnualRate,
        civilCourtFeeRate,
        smallClaimsFeeRate,
        smallClaimsCeiling,
        minimumCourtFee,
      ];

  static List<LegalRate<Object>> get unverified =>
      all.where((rate) => !rate.verified).toList();
}

/// One line in a calculation, so a result can always be shown as workings
/// rather than a bare number.
///
/// A user who takes a figure to an employer or a court needs to be able to
/// show how it was reached; an unexplained total is not usable.
class CalculationStep {
  const CalculationStep({
    required this.label,
    required this.value,
    this.formula,
    this.note,
    this.isSubtotal = false,
  });

  final String label;
  final double value;

  /// The arithmetic in words, e.g. `12,000 × 3.5 שנים`.
  final String? formula;
  final String? note;
  final bool isSubtotal;
}

/// The shape every calculator returns.
class CalculationResult {
  const CalculationResult({
    required this.title,
    required this.total,
    required this.steps,
    this.warnings = const [],
    this.unverifiedRates = const [],
    this.disclaimer,
  });

  final String title;
  final double total;
  final List<CalculationStep> steps;

  /// Conditions the user must check that the calculator cannot determine —
  /// eligibility questions, exceptions, collective agreements.
  final List<String> warnings;

  /// Rates the result depends on that have not been verified.
  final List<LegalRate<Object>> unverifiedRates;

  final String? disclaimer;

  bool get isFullyVerified => unverifiedRates.isEmpty;
}

/// Shared formatting so every calculator prints money the same way.
abstract final class Money {
  static String format(double amount) {
    final rounded = amount.round();
    final digits = rounded.abs().toString();
    final buffer = StringBuffer();
    for (var i = 0; i < digits.length; i++) {
      if (i > 0 && (digits.length - i) % 3 == 0) buffer.write(',');
      buffer.write(digits[i]);
    }
    return '${rounded < 0 ? '-' : ''}₪${buffer.toString()}';
  }
}
