/// Subscription tiers and the feature gates that depend on them.
///
/// Two tiers only: [AppTier.free] and [AppTier.premium].
///
/// A hard rule runs through this file: **the free tier is narrower, never
/// less accurate.** Quotas limit how much a user may do, not how trustworthy
/// the answer is. Nothing here gates a disclaimer, a "no source found"
/// notice, or a deadline warning — those reach every user, always. Making
/// safety a paid feature would be the wrong product and the wrong ethics.
library;

import 'package:flutter/material.dart';

enum AppTier {
  free('free', 'רגיל'),
  premium('premium', 'פרימיום');

  const AppTier(this.key, this.label);

  final String key;
  final String label;

  bool get isPremium => this == AppTier.premium;

  static AppTier fromKey(String? key) =>
      key == 'premium' ? AppTier.premium : AppTier.free;
}

/// Everything a tier can gate.
enum Feature {
  chat,
  documentAnalysis,
  contractAnalysis,
  documentGeneration,
  documentExport,
  ocr,
  contractComparison,
  deadlineReminders,
  legalSearch,
  fullHistory;

  /// Hebrew name, used in the paywall sheet.
  String get label => switch (this) {
        Feature.chat => 'שאלות לצ׳אט',
        Feature.documentAnalysis => 'ניתוח מסמך',
        Feature.contractAnalysis => 'ניתוח חוזה',
        Feature.documentGeneration => 'יצירת מסמך',
        Feature.documentExport => 'ייצוא PDF ו-Word',
        Feature.ocr => 'סריקת מסמך (OCR)',
        Feature.contractComparison => 'השוואת גרסאות חוזה',
        Feature.deadlineReminders => 'תזכורות למועדים',
        Feature.legalSearch => 'חיפוש בחקיקה ובפסיקה',
        Feature.fullHistory => 'היסטוריה מלאה',
      };

  String get pitch => switch (this) {
        Feature.chat => 'שאל כמה שאלות שתרצה, בלי הגבלה יומית',
        Feature.documentAnalysis => 'נתח כמה מסמכים שתרצה',
        Feature.contractAnalysis => 'בדוק כל חוזה לפני שאתה חותם',
        Feature.documentGeneration => 'קבל את המסמך המלא, לא רק הצצה',
        Feature.documentExport => 'שלח את המסמך כקובץ מוכן לחתימה',
        Feature.ocr => 'צלם כל מסמך והפוך אותו לטקסט',
        Feature.contractComparison => 'ראה בדיוק מה שינו לך בנוסח',
        Feature.deadlineReminders => 'אל תפספס מועד ערעור או התיישנות',
        Feature.legalSearch => 'חפש בחוקים ובפסקי דין',
        Feature.fullHistory => 'כל השיחות שלך נשמרות',
      };

  IconData get icon => switch (this) {
        Feature.chat => Icons.forum_outlined,
        Feature.documentAnalysis => Icons.fact_check_outlined,
        Feature.contractAnalysis => Icons.balance_outlined,
        Feature.documentGeneration => Icons.description_outlined,
        Feature.documentExport => Icons.download_outlined,
        Feature.ocr => Icons.document_scanner_outlined,
        Feature.contractComparison => Icons.compare_arrows_outlined,
        Feature.deadlineReminders => Icons.alarm_outlined,
        Feature.legalSearch => Icons.gavel_outlined,
        Feature.fullHistory => Icons.history,
      };
}

/// How a feature is limited for a given tier.
sealed class FeatureLimit {
  const FeatureLimit();
}

/// No limit at all.
class Unlimited extends FeatureLimit {
  const Unlimited();
}

/// Not available on this tier.
class Locked extends FeatureLimit {
  const Locked();
}

/// Allowed [count] times per [period].
class Quota extends FeatureLimit {
  const Quota(this.count, this.period);

  final int count;
  final QuotaPeriod period;
}

enum QuotaPeriod {
  day('ליום'),
  month('לחודש'),
  total('בסך הכול');

  const QuotaPeriod(this.label);

  final String label;
}

/// The tier → feature limit matrix. This is the single source of truth for
/// what each tier may do; nothing else in the app should hard-code a number.
abstract final class TierPolicy {
  static const Map<Feature, FeatureLimit> _free = {
    Feature.chat: Quota(5, QuotaPeriod.day),
    Feature.documentAnalysis: Quota(1, QuotaPeriod.month),
    Feature.contractAnalysis: Quota(1, QuotaPeriod.month),
    // Drafting is allowed, but the result is truncated to a preview.
    Feature.documentGeneration: Quota(2, QuotaPeriod.month),
    Feature.documentExport: Locked(),
    Feature.ocr: Quota(3, QuotaPeriod.month),
    Feature.contractComparison: Locked(),
    Feature.deadlineReminders: Locked(),
    Feature.legalSearch: Locked(),
    Feature.fullHistory: Quota(10, QuotaPeriod.total),
  };

  static const Map<Feature, FeatureLimit> _premium = {
    Feature.chat: Unlimited(),
    Feature.documentAnalysis: Unlimited(),
    Feature.contractAnalysis: Unlimited(),
    Feature.documentGeneration: Unlimited(),
    Feature.documentExport: Unlimited(),
    Feature.ocr: Unlimited(),
    Feature.contractComparison: Unlimited(),
    Feature.deadlineReminders: Unlimited(),
    // Corpus search needs the server; it stays locked until that ships,
    // on both tiers, rather than being sold before it exists.
    Feature.legalSearch: Locked(),
    Feature.fullHistory: Unlimited(),
  };

  static FeatureLimit limitFor(AppTier tier, Feature feature) =>
      (tier.isPremium ? _premium : _free)[feature] ?? const Locked();

  /// Features to advertise on the paywall, in persuasion order.
  static const List<Feature> sellingPoints = [
    Feature.chat,
    Feature.documentExport,
    Feature.contractAnalysis,
    Feature.contractComparison,
    Feature.deadlineReminders,
    Feature.ocr,
  ];
}

/// Outcome of asking "may this user do X right now?".
class AccessDecision {
  const AccessDecision._({
    required this.allowed,
    required this.feature,
    this.remaining,
    this.limit,
    this.reason,
  });

  const AccessDecision.allow(Feature feature, {int? remaining, int? limit})
      : this._(
          allowed: true,
          feature: feature,
          remaining: remaining,
          limit: limit,
        );

  const AccessDecision.deny(Feature feature, String reason, {int? limit})
      : this._(allowed: false, feature: feature, reason: reason, limit: limit);

  final bool allowed;
  final Feature feature;

  /// Uses left in the current window, when the feature is quota-limited.
  final int? remaining;
  final int? limit;
  final String? reason;

  bool get isBlocked => !allowed;

  /// True when the user is close enough to the limit to warn them.
  bool get isNearLimit => remaining != null && remaining! <= 1;
}
