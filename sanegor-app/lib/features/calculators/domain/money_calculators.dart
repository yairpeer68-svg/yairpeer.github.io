/// Debt indexation, arrears interest, court fees and a viability check.
///
/// All arithmetic, all inspectable. The rates come from [LegalRates] so a
/// stale figure is visible rather than silently baked into a formula.
library;

import 'dart:math' as math;

import 'legal_rates.dart';

/// How a debt is linked over time.
enum LinkageBasis {
  none('ללא הצמדה'),
  cpi('מדד המחירים לצרכן'),
  contractual('לפי הסכם');

  const LinkageBasis(this.label);

  final String label;
}

abstract final class DebtCalculator {
  /// What a debt is worth today, with linkage and arrears interest.
  ///
  /// CPI linkage needs the index at both dates. Rather than invent them, the
  /// caller supplies them; when they are missing the calculation still runs
  /// without linkage and says so, instead of guessing an inflation rate.
  static CalculationResult revalue({
    required double principal,
    required DateTime debtDate,
    DateTime? asOf,
    LinkageBasis linkage = LinkageBasis.cpi,
    double? indexAtDebtDate,
    double? indexNow,
    bool includeArrearsInterest = true,
  }) {
    final now = asOf ?? DateTime.now();
    final steps = <CalculationStep>[];
    final warnings = <String>[];
    final unverified = <LegalRate<Object>>[];

    final days = now.difference(debtDate).inDays;
    final years = days / 365.25;

    steps.add(
      CalculationStep(
        label: 'קרן החוב',
        value: principal,
        formula: 'נכון ל-${_formatDate(debtDate)}',
      ),
    );

    var linkedAmount = principal;

    if (linkage == LinkageBasis.cpi) {
      if (indexAtDebtDate != null && indexNow != null && indexAtDebtDate > 0) {
        linkedAmount = principal * (indexNow / indexAtDebtDate);
        steps.add(
          CalculationStep(
            label: 'הפרשי הצמדה למדד',
            value: linkedAmount - principal,
            formula: 'מדד $indexNow ÷ מדד $indexAtDebtDate',
          ),
        );
      } else {
        warnings.add(
          'לא הוזנו ערכי המדד, ולכן החישוב אינו כולל הפרשי הצמדה. '
          'ניתן להזין את המדד הידוע במועד החוב ואת המדד העדכני מאתר '
          'הלשכה המרכזית לסטטיסטיקה.',
        );
      }
    }

    var total = linkedAmount;

    if (includeArrearsInterest && years > 0) {
      final rate = LegalRates.arrearsInterestAnnualRate;
      unverified.add(rate);

      // Simple interest on the linked principal, which is the conventional
      // presentation for a claim; compounding is not assumed.
      final interest = linkedAmount * rate.value * years;
      steps.add(
        CalculationStep(
          label: 'ריבית פיגורים',
          value: interest,
          formula: '${(rate.value * 100).toStringAsFixed(2)}% לשנה × '
              '${years.toStringAsFixed(2)} שנים',
          note: rate.note,
        ),
      );
      total += interest;
    }

    steps.add(
      CalculationStep(label: 'סך הכול היום', value: total, isSubtotal: true),
    );

    warnings.add(
      'בית המשפט מוסמך לפסוק ריבית והצמדה לפי שיקול דעתו, ולא בהכרח '
      'בשיעור המלא.',
    );

    return CalculationResult(
      title: 'שערוך חוב',
      total: total,
      steps: steps,
      warnings: warnings,
      unverifiedRates: unverified.where((r) => !r.verified).toList(),
    );
  }

  static String _formatDate(DateTime value) =>
      '${value.day}/${value.month}/${value.year}';
}

enum CourtTrack {
  smallClaims('תביעות קטנות'),
  magistrate('בית משפט שלום'),
  district('בית משפט מחוזי');

  const CourtTrack(this.label);

  final String label;
}

abstract final class CourtFeeCalculator {
  /// Which track a claim of this size belongs to.
  static CourtTrack trackFor(double claimAmount) {
    if (claimAmount <= LegalRates.smallClaimsCeiling.value) {
      return CourtTrack.smallClaims;
    }
    // The magistrate/district boundary depends on subject matter as well as
    // amount, so this is a starting point rather than a determination.
    return claimAmount <= 2500000
        ? CourtTrack.magistrate
        : CourtTrack.district;
  }

  static CalculationResult estimate({
    required double claimAmount,
    CourtTrack? track,
  }) {
    final resolved = track ?? trackFor(claimAmount);
    final steps = <CalculationStep>[];
    final unverified = <LegalRate<Object>>[];

    final rate = resolved == CourtTrack.smallClaims
        ? LegalRates.smallClaimsFeeRate
        : LegalRates.civilCourtFeeRate;
    unverified
      ..add(rate)
      ..add(LegalRates.minimumCourtFee);

    final calculated = claimAmount * rate.value;
    final fee = math.max(calculated, LegalRates.minimumCourtFee.value);

    steps.add(
      CalculationStep(
        label: 'סכום התביעה',
        value: claimAmount,
      ),
    );
    steps.add(
      CalculationStep(
        label: 'אגרה מחושבת',
        value: calculated,
        formula: '${(rate.value * 100).toStringAsFixed(2)}% מסכום התביעה',
      ),
    );
    if (fee > calculated) {
      steps.add(
        CalculationStep(
          label: 'אגרת מינימום',
          value: fee,
          note: 'האגרה המחושבת נמוכה מהמינימום',
        ),
      );
    }
    steps.add(CalculationStep(label: 'אגרה משוערת', value: fee, isSubtotal: true));

    return CalculationResult(
      title: 'אגרת בית משפט — ${resolved.label}',
      total: fee,
      steps: steps,
      warnings: [
        'בהליכים אזרחיים האגרה משולמת לרוב בשני שלבים — מחצית בהגשה '
            'ומחצית לפני הדיון.',
        'ניתן להגיש בקשה לפטור מאגרה מטעמי מצב כלכלי.',
        'אם התביעה מסתיימת בפשרה מוקדמת, ייתכן החזר חלקי.',
        if (resolved == CourtTrack.smallClaims)
          'בתביעות קטנות אין ייצוג על ידי עורך דין, וזה בדיוק מה שהופך '
              'את ההליך לנגיש.',
      ],
      unverifiedRates: unverified.where((r) => !r.verified).toList(),
    );
  }
}

/// Is a claim worth bringing?
///
/// Explicitly **not** a prediction of who wins. It compares what the process
/// costs against what is being claimed — a question with an arithmetic answer,
/// unlike the outcome, which does not have one.
abstract final class ViabilityCalculator {
  static CalculationResult assess({
    required double claimAmount,
    required DateTime debtDate,
    double estimatedLawyerFee = 0,
  }) {
    final fees = CourtFeeCalculator.estimate(claimAmount: claimAmount);
    final track = CourtFeeCalculator.trackFor(claimAmount);

    final costs = fees.total + estimatedLawyerFee;
    final net = claimAmount - costs;

    final steps = <CalculationStep>[
      CalculationStep(label: 'סכום התביעה', value: claimAmount),
      CalculationStep(
        label: 'אגרה משוערת',
        value: -fees.total,
        formula: track.label,
      ),
      if (estimatedLawyerFee > 0)
        CalculationStep(label: 'שכר טרחה משוער', value: -estimatedLawyerFee),
      CalculationStep(label: 'נטו לפני גבייה', value: net, isSubtotal: true),
    ];

    final warnings = <String>[
      'החישוב אינו מעריך את סיכויי התביעה — רק את העלות מול הסכום.',
      // The step everyone forgets, and the one that most often makes a
      // technically winnable claim not worth bringing.
      'פסק דין אינו כסף. אם לנתבע אין נכסים או הכנסה, גבייה בהוצאה לפועל '
          'עלולה להיכשל גם אחרי ניצחון.',
      if (track == CourtTrack.smallClaims)
        'בתביעות קטנות אין שכר טרחת עורך דין, ולכן העלות נמוכה משמעותית.',
      if (net < claimAmount * 0.5)
        'העלויות אוכלות יותר ממחצית התביעה. שווה לשקול פנייה מקדימה '
            'ומכתב דרישה לפני הגשה.',
    ];

    return CalculationResult(
      title: 'כדאיות הגשת תביעה',
      total: net,
      steps: steps,
      warnings: warnings,
      unverifiedRates: fees.unverifiedRates,
    );
  }
}
