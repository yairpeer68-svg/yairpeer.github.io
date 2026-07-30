import 'package:flutter_test/flutter_test.dart';
import 'package:sanegor/features/calculators/domain/deadline_engine.dart';
import 'package:sanegor/features/calculators/domain/employment_calculator.dart';
import 'package:sanegor/features/calculators/domain/legal_rates.dart';
import 'package:sanegor/features/calculators/domain/money_calculators.dart';

void main() {
  group('notice period', () {
    EmploymentInput input({
      required int months,
      PayBasis basis = PayBasis.monthly,
    }) {
      final end = DateTime(2026, 1, 1);
      return EmploymentInput(
        startDate: DateTime(
          end.year,
          end.month - months,
          end.day,
        ),
        endDate: end,
        lastMonthlySalary: 12000,
        payBasis: basis,
      );
    }

    test('monthly employee builds up over the first year', () {
      // 1 day per month for the first six months.
      expect(EmploymentCalculator.noticeDays(input(months: 3)), 3);
      expect(EmploymentCalculator.noticeDays(input(months: 6)), 6);
      // Months 7-12 accrue faster.
      expect(EmploymentCalculator.noticeDays(input(months: 9)), greaterThan(6));
      expect(EmploymentCalculator.noticeDays(input(months: 9)), lessThan(30));
    });

    test('monthly employee reaches a full month after a year', () {
      expect(EmploymentCalculator.noticeDays(input(months: 12)), 30);
      expect(EmploymentCalculator.noticeDays(input(months: 40)), 30);
    });

    test('hourly employee reaches a full month only after three years', () {
      final twoYears = input(months: 24, basis: PayBasis.hourly);
      final threeYears = input(months: 36, basis: PayBasis.hourly);

      expect(EmploymentCalculator.noticeDays(twoYears), lessThan(30));
      expect(EmploymentCalculator.noticeDays(threeYears), 30);
    });

    test('hourly accrues more slowly than monthly at the same seniority', () {
      // Getting these two tables the wrong way round is the classic error.
      final months = 18;
      expect(
        EmploymentCalculator.noticeDays(input(months: months, basis: PayBasis.hourly)),
        lessThan(EmploymentCalculator.noticeDays(input(months: months))),
      );
    });
  });

  group('severance', () {
    test('one month per year, proportional for a partial year', () {
      final result = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
          noticeGivenDays: 30,
        ),
      );
      // Three years at ₪10,000 ≈ ₪30,000, within rounding of 365.25-day years.
      expect(result.total, closeTo(30000, 200));
    });

    test('pension accrual offsets the employer top-up', () {
      final result = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
          noticeGivenDays: 30,
          pensionSeveranceAccrued: 25000,
        ),
      );
      final topUp = result.steps.firstWhere(
        (step) => step.label == 'השלמת פיצויים מהמעסיק',
      );
      expect(topUp.value, closeTo(5000, 200));
    });

    test('plain resignation explains the exceptions rather than refusing', () {
      final result = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
          reason: SeparationReason.resigned,
          noticeGivenDays: 30,
        ),
      );
      expect(result.warnings.any((w) => w.contains('חריגים')), isTrue);
    });

    test('short notice is paid out', () {
      final withNotice = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
          noticeGivenDays: 30,
        ),
      );
      final withoutNotice = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
        ),
      );
      expect(withoutNotice.total, greaterThan(withNotice.total));
    });

    test('every result carries workings and a total step', () {
      final result = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
        ),
      );
      expect(result.steps, isNotEmpty);
      expect(result.steps.last.label, 'סך הכול');
      expect(result.steps.any((s) => s.formula != null), isTrue);
    });

    test('collective-agreement caveat is always present', () {
      final result = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
        ),
      );
      expect(result.warnings.any((w) => w.contains('הסכם קיבוצי')), isTrue);
    });

    test('unverified rates are reported, not hidden', () {
      final result = EmploymentCalculator.calculate(
        EmploymentInput(
          startDate: DateTime(2020, 1, 1),
          endDate: DateTime(2023, 1, 1),
          lastMonthlySalary: 10000,
          recuperationDaysOwed: 7,
        ),
      );
      // The recuperation rate ships unverified, so the result must say so.
      expect(result.isFullyVerified, isFalse);
      expect(result.unverifiedRates, isNotEmpty);
    });
  });

  group('limitation periods', () {
    test('a general claim runs for seven years', () {
      final deadline = DeadlineEngine.limitation(
        claimType: ClaimType.general,
        causeOfActionDate: DateTime(2020, 6, 15),
      );
      expect(deadline.dueDate, DateTime(2027, 6, 15));
    });

    test('registered land runs far longer', () {
      final deadline = DeadlineEngine.limitation(
        claimType: ClaimType.registeredLand,
        causeOfActionDate: DateTime(2000, 1, 1),
      );
      expect(deadline.dueDate.year, 2025);
    });

    test('suspension rules are surfaced, not buried', () {
      final deadline = DeadlineEngine.limitation(
        claimType: ClaimType.general,
        causeOfActionDate: DateTime(2020),
      );
      expect(deadline.notes.any((n) => n.contains('קטין')), isTrue);
      expect(deadline.notes.any((n) => n.contains('נתבע')), isTrue);
    });
  });

  group('procedural deadlines', () {
    test('judgment appeal counts sixty days from service', () {
      final deadline = DeadlineEngine.procedural(
        procedure: ProcedureType.judgmentAppeal,
        triggerDate: DateTime(2026, 1, 1),
      );
      expect(deadline.dueDate, DateTime(2026, 3, 2));
      expect(deadline.triggerLabel, 'מועד ההמצאה');
    });

    test('service, not the decision date, is named as the trigger', () {
      final deadline = DeadlineEngine.procedural(
        procedure: ProcedureType.judgmentAppeal,
        triggerDate: DateTime(2026, 1, 1),
      );
      expect(deadline.notes.any((n) => n.contains('ההמצאה בפועל')), isTrue);
    });

    test('a passed deadline still mentions an extension application', () {
      final deadline = DeadlineEngine.procedural(
        procedure: ProcedureType.judgmentAppeal,
        triggerDate: DateTime(2020),
      );
      expect(deadline.hasPassed, isTrue);
      // Never leave someone believing the door is definitively shut.
      expect(deadline.notes.any((n) => n.contains('הארכת מועד')), isTrue);
    });

    test('reminders are staged, not left to the final day', () {
      final deadline = DeadlineEngine.procedural(
        procedure: ProcedureType.judgmentAppeal,
        triggerDate: DateTime.now(),
      );
      final reminders = deadline.reminderDates();
      expect(reminders.length, greaterThan(2));
      expect(reminders.every((d) => d.isBefore(deadline.dueDate)), isTrue);
    });

    test('urgency is flagged inside two weeks', () {
      final soon = DeadlineEngine.procedural(
        procedure: ProcedureType.parkingTicketObjection,
        triggerDate: DateTime.now().subtract(const Duration(days: 25)),
      );
      expect(soon.isUrgent, isTrue);
    });

    test('prioritise puts live deadlines before passed ones', () {
      final passed = DeadlineEngine.procedural(
        procedure: ProcedureType.judgmentAppeal,
        triggerDate: DateTime(2020),
      );
      final live = DeadlineEngine.procedural(
        procedure: ProcedureType.judgmentAppeal,
        triggerDate: DateTime.now(),
      );
      final ordered = DeadlineEngine.prioritise([passed, live]);
      expect(ordered.first.hasPassed, isFalse);
    });
  });

  group('debt revaluation', () {
    test('linkage applies when both index values are supplied', () {
      final result = DebtCalculator.revalue(
        principal: 10000,
        debtDate: DateTime(2020),
        asOf: DateTime(2025),
        indexAtDebtDate: 100,
        indexNow: 115,
      );
      expect(result.steps.any((s) => s.label.contains('הצמדה')), isTrue);
      expect(result.total, greaterThan(11500));
    });

    test('missing index values disable linkage instead of guessing', () {
      final result = DebtCalculator.revalue(
        principal: 10000,
        debtDate: DateTime(2020),
        asOf: DateTime(2025),
      );
      expect(result.warnings.any((w) => w.contains('המדד')), isTrue);
      expect(result.steps.any((s) => s.label.contains('הצמדה')), isFalse);
    });

    test('arrears interest grows with elapsed time', () {
      final shorter = DebtCalculator.revalue(
        principal: 10000,
        debtDate: DateTime(2024),
        asOf: DateTime(2025),
      );
      final longer = DebtCalculator.revalue(
        principal: 10000,
        debtDate: DateTime(2018),
        asOf: DateTime(2025),
      );
      expect(longer.total, greaterThan(shorter.total));
    });
  });

  group('court fees', () {
    test('a small claim routes to the small-claims track', () {
      expect(CourtFeeCalculator.trackFor(15000), CourtTrack.smallClaims);
    });

    test('above the ceiling routes to the magistrate court', () {
      expect(CourtFeeCalculator.trackFor(200000), CourtTrack.magistrate);
    });

    test('the minimum fee applies to tiny claims', () {
      final result = CourtFeeCalculator.estimate(claimAmount: 1000);
      expect(result.total, LegalRates.minimumCourtFee.value);
    });

    test('small claims notes that no lawyer appears', () {
      final result = CourtFeeCalculator.estimate(claimAmount: 15000);
      expect(result.warnings.any((w) => w.contains('עורך דין')), isTrue);
    });
  });

  group('viability', () {
    test('costs are subtracted from the claim', () {
      final result = ViabilityCalculator.assess(
        claimAmount: 20000,
        debtDate: DateTime(2024),
      );
      expect(result.total, lessThan(20000));
    });

    test('it declines to predict the outcome', () {
      final result = ViabilityCalculator.assess(
        claimAmount: 20000,
        debtDate: DateTime(2024),
      );
      expect(result.warnings.any((w) => w.contains('אינו מעריך את סיכויי')), isTrue);
    });

    test('collection risk is always raised', () {
      final result = ViabilityCalculator.assess(
        claimAmount: 20000,
        debtDate: DateTime(2024),
      );
      // A judgment against someone with no assets is not money.
      expect(result.warnings.any((w) => w.contains('גבייה')), isTrue);
    });

    test('heavy costs relative to the claim trigger a warning', () {
      final result = ViabilityCalculator.assess(
        claimAmount: 8000,
        debtDate: DateTime(2024),
        estimatedLawyerFee: 6000,
      );
      expect(result.warnings.any((w) => w.contains('מחצית')), isTrue);
    });
  });

  group('money formatting', () {
    test('groups thousands', () {
      expect(Money.format(1234567), '₪1,234,567');
      expect(Money.format(999), '₪999');
      expect(Money.format(1000), '₪1,000');
    });

    test('handles negatives', () {
      expect(Money.format(-5000), '-₪5,000');
    });
  });
}
