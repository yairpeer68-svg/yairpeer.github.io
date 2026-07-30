/// Employment termination entitlements.
///
/// The single most-asked question by anyone who has just been dismissed in
/// Israel, and the one most often answered wrongly — usually in the employer's
/// favour, because the employee does not know the notice table.
///
/// Everything here is arithmetic on declared inputs. Nothing is inferred by a
/// language model: a severance figure a person will take to their employer has
/// to be reproducible and checkable line by line.
library;

import 'legal_rates.dart';

enum PayBasis {
  monthly('משכורת חודשית'),
  hourly('שכר שעתי או יומי');

  const PayBasis(this.label);

  final String label;
}

enum SeparationReason {
  dismissed('פוטרתי'),
  resigned('התפטרתי'),
  resignedForCause('התפטרתי בנסיבות המזכות בפיצויים'),
  fixedTermEnded('חוזה לתקופה קצובה שהסתיים');

  const SeparationReason(this.label);

  final String label;

  /// Whether severance is due at all.
  ///
  /// Plain resignation generally is not compensable, but the exceptions are
  /// wide (health, relocation, a material worsening of terms, and more), so
  /// the calculator never says a flat "you get nothing" — it explains.
  bool get severanceLikely => this != SeparationReason.resigned;
}

class EmploymentInput {
  const EmploymentInput({
    required this.startDate,
    required this.endDate,
    required this.lastMonthlySalary,
    this.payBasis = PayBasis.monthly,
    this.reason = SeparationReason.dismissed,
    this.weeklyWorkDays = 5,
    this.unusedVacationDays = 0,
    this.recuperationDaysOwed = 0,
    this.noticeGivenDays = 0,
    this.pensionSeveranceAccrued = 0,
  });

  final DateTime startDate;
  final DateTime endDate;

  /// Determining salary for severance. For an hourly worker this is the
  /// average of the last twelve months, not the final month.
  final double lastMonthlySalary;

  final PayBasis payBasis;
  final SeparationReason reason;
  final int weeklyWorkDays;
  final int unusedVacationDays;
  final int recuperationDaysOwed;

  /// Days of notice the employer actually gave. A shortfall is payable.
  final int noticeGivenDays;

  /// Severance already accumulated in the pension fund, which offsets what
  /// the employer still owes directly.
  final double pensionSeveranceAccrued;

  /// Employment length in whole days.
  int get totalDays => endDate.difference(startDate).inDays;

  /// Employment length in years, fractional — severance is proportional, so
  /// a partial final year still counts.
  double get years => totalDays / 365.25;

  /// Completed calendar months of employment.
  ///
  /// Counted on the calendar rather than by dividing days by an average
  /// month length: 365 / 30.44 floors to 11, so exactly one year of service
  /// would fall into the under-a-year notice bracket and understate the
  /// entitlement by twelve days. The notice table turns on whole months, so
  /// it has to be counted the way a person would.
  int get wholeMonths {
    var months =
        (endDate.year - startDate.year) * 12 + (endDate.month - startDate.month);
    if (endDate.day < startDate.day) months -= 1;
    return months < 0 ? 0 : months;
  }
}

abstract final class EmploymentCalculator {
  /// Statutory notice, in days.
  ///
  /// Two separate tables under the notice law: monthly-paid employees build
  /// up to a full month across the first year, hourly-paid employees across
  /// three. Getting these backwards is the most common error.
  static int noticeDays(EmploymentInput input) {
    final months = input.wholeMonths;

    if (input.payBasis == PayBasis.monthly) {
      if (months >= 12) return 30;
      if (months <= 6) return months;
      // Months 7-12: six days, plus 2.5 for each month beyond the sixth.
      return (6 + (months - 6) * 2.5).floor();
    }

    // Hourly or daily.
    if (months >= 36) return 30;
    if (months < 12) return months;
    if (months < 24) return 14 + ((months - 12) / 2).floor();
    return 21 + ((months - 24) / 2).floor();
  }

  static CalculationResult calculate(EmploymentInput input) {
    final steps = <CalculationStep>[];
    final warnings = <String>[];
    final unverified = <LegalRate<Object>>[];
    var total = 0.0;

    // ---------------------------------------------------------- severance
    // One month's salary per year worked, proportional for a partial year.
    final severanceGross = input.lastMonthlySalary * input.years;

    if (input.reason.severanceLikely) {
      steps.add(
        CalculationStep(
          label: 'פיצויי פיטורים',
          value: severanceGross,
          formula: '${Money.format(input.lastMonthlySalary)} × '
              '${input.years.toStringAsFixed(2)} שנים',
          note: 'משכורת אחרונה כפול שנות הוותק, כולל חלק יחסי',
        ),
      );

      if (input.pensionSeveranceAccrued > 0) {
        // What sits in the fund is credited against the employer's liability.
        final balance = severanceGross - input.pensionSeveranceAccrued;
        steps.add(
          CalculationStep(
            label: 'בניכוי שנצבר בקרן הפנסיה',
            value: -input.pensionSeveranceAccrued,
            formula: 'רכיב פיצויים שכבר צבור על שמך',
          ),
        );
        steps.add(
          CalculationStep(
            label: 'השלמת פיצויים מהמעסיק',
            value: balance,
            isSubtotal: true,
            note: balance <= 0
                ? 'הצבירה מכסה את מלוא הפיצויים'
                : 'הסכום שהמעסיק עדיין חייב להשלים',
          ),
        );
        total += severanceGross > input.pensionSeveranceAccrued
            ? severanceGross
            : input.pensionSeveranceAccrued;
      } else {
        total += severanceGross;
        warnings.add(
          'לא הוזן סכום שנצבר בקרן הפנסיה. אם קיימת צבירת רכיב פיצויים, '
          'היא מקוזזת מהסכום שהמעסיק משלים.',
        );
      }
    } else {
      warnings.add(
        'התפטרות אינה מזכה בפיצויים כברירת מחדל — אך יש חריגים רחבים '
        '(הרעה מוחשית בתנאים, מצב בריאותי, טיפול בילד, מעבר מגורים ועוד). '
        'אם אחד מהם מתקיים אצלך, ייתכן שמגיעים לך פיצויים מלאים.',
      );
    }

    // ------------------------------------------------------------- notice
    final requiredNotice = noticeDays(input);
    final shortfall = requiredNotice - input.noticeGivenDays;

    steps.add(
      CalculationStep(
        label: 'הודעה מוקדמת שמגיעה',
        value: 0,
        formula: '$requiredNotice ימים לפי הוותק וסוג השכר',
      ),
    );

    if (shortfall > 0) {
      final dailyRate = input.lastMonthlySalary / 30;
      final noticePay = dailyRate * shortfall;
      steps.add(
        CalculationStep(
          label: 'תמורת הודעה מוקדמת שלא ניתנה',
          value: noticePay,
          formula: '${Money.format(dailyRate)} ליום × $shortfall ימים',
          note: 'המעסיק נתן ${input.noticeGivenDays} מתוך $requiredNotice ימים',
        ),
      );
      total += noticePay;
    }

    // ----------------------------------------------------------- vacation
    if (input.unusedVacationDays > 0) {
      // Valued on working days, so the divisor follows the working week.
      final workingDaysPerMonth = input.weeklyWorkDays * 4.33;
      final vacationDayValue = input.lastMonthlySalary / workingDaysPerMonth;
      final vacationPay = vacationDayValue * input.unusedVacationDays;

      steps.add(
        CalculationStep(
          label: 'פדיון חופשה',
          value: vacationPay,
          formula: '${Money.format(vacationDayValue)} ליום × '
              '${input.unusedVacationDays} ימים',
          note: 'לפי שבוע עבודה של ${input.weeklyWorkDays} ימים',
        ),
      );
      total += vacationPay;
    } else {
      warnings.add(
        'לא הוזנו ימי חופשה שלא נוצלו. יתרת החופשה מופיעה בתלוש — '
        'שווה לבדוק, זה סכום שנשכח לעיתים קרובות.',
      );
    }

    // ------------------------------------------------------- recuperation
    if (input.recuperationDaysOwed > 0) {
      final rate = LegalRates.recuperationDayRate;
      final recuperationPay = rate.value * input.recuperationDaysOwed;
      unverified.add(rate);

      steps.add(
        CalculationStep(
          label: 'דמי הבראה',
          value: recuperationPay,
          formula: '${Money.format(rate.value)} ליום × '
              '${input.recuperationDaysOwed} ימים',
          note: rate.note,
        ),
      );
      total += recuperationPay;
    }

    steps.add(
      CalculationStep(
        label: 'סך הכול',
        value: total,
        isSubtotal: true,
      ),
    );

    // Two conditions the calculator genuinely cannot see, and both can move
    // the number materially.
    warnings.add(
      'החישוב מבוסס על הזכויות מכוח החוק בלבד. הסכם קיבוצי, צו הרחבה או '
      'הסכם אישי מיטיב עשויים להעלות את הסכום.',
    );
    if (input.years < 1 && input.reason.severanceLikely) {
      warnings.add(
        'ותק של פחות משנה — בדוק את הזכאות לפיצויים, היא אינה מובנת מאליה '
        'בכל מקרה.',
      );
    }

    return CalculationResult(
      title: 'זכויות בסיום העסקה',
      total: total,
      steps: steps,
      warnings: warnings,
      unverifiedRates: unverified.where((r) => !r.verified).toList(),
      disclaimer: 'חישוב לפי הנתונים שהזנת. אינו תחליף לבדיקה של הזכויות '
          'בתלושי השכר ובהסכם ההעסקה.',
    );
  }
}
