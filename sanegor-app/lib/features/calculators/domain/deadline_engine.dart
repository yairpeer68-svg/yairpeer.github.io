/// Limitation periods and procedural deadlines.
///
/// The feature that prevents the most damage: a missed appeal window closes a
/// case permanently, and nothing later in the app can undo it.
///
/// Two deliberate design choices:
///
/// * **Never report a deadline as passed without saying it may be extendable.**
///   Courts can extend for cause, and a person told flatly "too late" may
///   abandon a claim that was still live.
/// * **Count from the event the law counts from.** Appeal windows usually run
///   from service, not from the date on the decision, and conflating them is
///   the most common way people lose weeks they actually had.
library;

/// What kind of clock is running.
enum DeadlineKind {
  limitation('התיישנות'),
  appeal('ערעור'),
  objection('התנגדות או השגה'),
  defence('כתב הגנה'),
  response('מענה');

  const DeadlineKind(this.label);

  final String label;
}

/// A limitation period under the limitation law.
enum ClaimType {
  general('תביעה אזרחית רגילה', 7),
  contract('הפרת חוזה', 7),
  tort('נזיקין', 7),
  unregisteredLand('מקרקעין לא מוסדרים', 15),
  registeredLand('מקרקעין מוסדרים', 25);

  const ClaimType(this.label, this.years);

  final String label;
  final int years;
}

/// A procedural window, expressed in days from a trigger event.
enum ProcedureType {
  judgmentAppeal('ערעור על פסק דין', 60, DeadlineKind.appeal),
  decisionLeaveToAppeal('בקשת רשות ערעור על החלטה', 30, DeadlineKind.appeal),
  smallClaimsAppeal('ערעור בתביעות קטנות (ברשות)', 15, DeadlineKind.appeal),
  statementOfDefence('הגשת כתב הגנה', 60, DeadlineKind.defence),
  parkingTicketObjection('בקשה לביטול דוח חניה', 30, DeadlineKind.objection),
  municipalTaxObjection('השגה על ארנונה', 90, DeadlineKind.objection);

  const ProcedureType(this.label, this.days, this.kind);

  final String label;
  final int days;
  final DeadlineKind kind;

  /// Whether the clock starts on service rather than on the decision date.
  bool get runsFromService =>
      kind == DeadlineKind.appeal || kind == DeadlineKind.defence;
}

/// A computed deadline, ready to display or turn into a reminder.
class Deadline {
  const Deadline({
    required this.title,
    required this.kind,
    required this.dueDate,
    required this.triggerDate,
    required this.triggerLabel,
    this.notes = const [],
  });

  final String title;
  final DeadlineKind kind;
  final DateTime dueDate;
  final DateTime triggerDate;
  final String triggerLabel;
  final List<String> notes;

  int daysRemaining([DateTime? from]) =>
      dueDate.difference(_atMidnight(from ?? DateTime.now())).inDays;

  bool get hasPassed => daysRemaining() < 0;
  bool get isUrgent => !hasPassed && daysRemaining() <= 14;

  /// When to raise reminders: early enough to act, then again as it closes.
  ///
  /// A single reminder on the last day is useless — by then there is no time
  /// to reach a lawyer.
  List<DateTime> reminderDates() {
    const offsets = [30, 14, 7, 3, 1];
    final now = _atMidnight(DateTime.now());
    return [
      for (final days in offsets)
        if (dueDate.subtract(Duration(days: days)).isAfter(now))
          dueDate.subtract(Duration(days: days)),
    ];
  }

  String get statusText {
    final remaining = daysRemaining();
    if (remaining < 0) return 'המועד חלף לפני ${-remaining} ימים';
    if (remaining == 0) return 'המועד הוא היום';
    if (remaining == 1) return 'נותר יום אחד';
    return 'נותרו $remaining ימים';
  }

  static DateTime _atMidnight(DateTime value) =>
      DateTime(value.year, value.month, value.day);
}

abstract final class DeadlineEngine {
  /// Limitation on a claim, counted from when the cause of action arose.
  static Deadline limitation({
    required ClaimType claimType,
    required DateTime causeOfActionDate,
  }) {
    final due = DateTime(
      causeOfActionDate.year + claimType.years,
      causeOfActionDate.month,
      causeOfActionDate.day,
    );

    return Deadline(
      title: 'התיישנות — ${claimType.label}',
      kind: DeadlineKind.limitation,
      dueDate: due,
      triggerDate: causeOfActionDate,
      triggerLabel: 'מועד היווצרות העילה',
      notes: [
        'תקופת ההתיישנות היא ${claimType.years} שנים.',
        // These suspensions are common enough that omitting them would give a
        // materially wrong answer to a large share of users.
        'המרוץ עשוי להיעצר או להתחיל מחדש: אם לא ידעת על העובדות, אם היית '
            'קטין, אם הנתבע הודה בזכות, או במקרים של תרמית והונאה.',
        'התיישנות אינה מוחקת את הזכות — היא חוסמת את התביעה רק אם הנתבע '
            'טוען אותה.',
      ],
    );
  }

  /// A procedural window, counted from the triggering event.
  static Deadline procedural({
    required ProcedureType procedure,
    required DateTime triggerDate,
  }) {
    final due = triggerDate.add(Duration(days: procedure.days));

    return Deadline(
      title: procedure.label,
      kind: procedure.kind,
      dueDate: due,
      triggerDate: triggerDate,
      triggerLabel: procedure.runsFromService
          ? 'מועד ההמצאה'
          : 'מועד ההחלטה או האירוע',
      notes: [
        'המניין הוא ${procedure.days} ימים.',
        if (procedure.runsFromService)
          'המניין מתחיל ממועד ההמצאה בפועל, לא מהתאריך שמופיע על ההחלטה. '
              'אם קיבלת את המסמך מאוחר יותר — זה התאריך הקובע.',
        // Never leave someone believing the door is definitively shut.
        'גם אם המועד חלף, ניתן להגיש בקשה להארכת מועד מטעמים מיוחדים. '
            'כדאי לפנות לעורך דין במהירות ולא לוותר מראש.',
        'פגרות בתי המשפט עשויות להשפיע על המניין בחלק מההליכים.',
      ],
    );
  }

  /// Every deadline implied by a single event, so one input produces the
  /// full picture rather than only the one the user thought to ask about.
  static List<Deadline> forJudgment(DateTime serviceDate) => [
        procedural(
          procedure: ProcedureType.judgmentAppeal,
          triggerDate: serviceDate,
        ),
      ];

  /// Sort by urgency, with passed deadlines last — what needs action first
  /// belongs at the top of the list.
  static List<Deadline> prioritise(List<Deadline> deadlines) {
    final live = deadlines.where((d) => !d.hasPassed).toList()
      ..sort((a, b) => a.dueDate.compareTo(b.dueDate));
    final passed = deadlines.where((d) => d.hasPassed).toList()
      ..sort((a, b) => b.dueDate.compareTo(a.dueDate));
    return [...live, ...passed];
  }
}
