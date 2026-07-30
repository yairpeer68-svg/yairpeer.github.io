/// Tier state and local usage accounting.
///
/// While the app runs without a server there is nothing to verify a purchase
/// against, so premium is stored locally. That is deliberate and temporary:
/// this file is the seam where a real receipt check (Google Play Billing, or
/// the backend once it exists) plugs in without touching any call site.
///
/// Because the store is local it is trivially editable by a determined user.
/// That is an accepted cost at this stage — the app is not yet distributed,
/// and the alternative (shipping a shared secret to enforce it) would be
/// worse. Enforcement becomes real when the server does.
library;

import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../providers.dart';
import 'tier.dart';

/// Per-feature usage counters, bucketed by the window they belong to.
class UsageLedger {
  const UsageLedger(this._counters);

  /// Key format: `<feature>|<window>` → count.
  final Map<String, int> _counters;

  static const UsageLedger empty = UsageLedger({});

  static String _window(QuotaPeriod period, DateTime now) => switch (period) {
        QuotaPeriod.day =>
          '${now.year}-${now.month.toString().padLeft(2, '0')}-'
              '${now.day.toString().padLeft(2, '0')}',
        QuotaPeriod.month =>
          '${now.year}-${now.month.toString().padLeft(2, '0')}',
        QuotaPeriod.total => 'all',
      };

  static String _key(Feature feature, QuotaPeriod period, DateTime now) =>
      '${feature.name}|${_window(period, now)}';

  int countFor(Feature feature, QuotaPeriod period, DateTime now) =>
      _counters[_key(feature, period, now)] ?? 0;

  UsageLedger increment(Feature feature, QuotaPeriod period, DateTime now) {
    final key = _key(feature, period, now);
    return UsageLedger({..._counters, key: (_counters[key] ?? 0) + 1});
  }

  /// Drop counters for windows that have passed, so the map cannot grow
  /// without bound over months of use.
  UsageLedger prune(DateTime now) {
    final live = {
      _window(QuotaPeriod.day, now),
      _window(QuotaPeriod.month, now),
      'all',
    };
    return UsageLedger({
      for (final entry in _counters.entries)
        if (live.contains(entry.key.split('|').last)) entry.key: entry.value,
    });
  }

  Map<String, int> toJson() => _counters;

  factory UsageLedger.fromJson(Map<String, dynamic> json) => UsageLedger({
        for (final entry in json.entries)
          if (entry.value is int) entry.key: entry.value as int,
      });
}

class EntitlementState {
  const EntitlementState({
    this.tier = AppTier.free,
    this.usage = UsageLedger.empty,
    this.expiresAt,
  });

  final AppTier tier;
  final UsageLedger usage;

  /// When a premium period ends. Null means perpetual (or not premium).
  final DateTime? expiresAt;

  bool get isPremium =>
      tier.isPremium &&
      (expiresAt == null || expiresAt!.isAfter(DateTime.now()));

  /// The tier actually in force, accounting for an expired subscription.
  AppTier get effectiveTier => isPremium ? AppTier.premium : AppTier.free;
}

class EntitlementController extends StateNotifier<EntitlementState> {
  EntitlementController(this._prefs) : super(const EntitlementState()) {
    _load();
  }

  final SharedPreferences _prefs;

  static const _tierKey = 'entitlement_tier';
  static const _usageKey = 'entitlement_usage';
  static const _expiryKey = 'entitlement_expires_at';

  void _load() {
    final rawUsage = _prefs.getString(_usageKey);
    var ledger = UsageLedger.empty;
    if (rawUsage != null) {
      try {
        final decoded = jsonDecode(rawUsage);
        if (decoded is Map<String, dynamic>) {
          ledger = UsageLedger.fromJson(decoded).prune(DateTime.now());
        }
      } on FormatException {
        // A corrupt ledger costs the user nothing to reset.
      }
    }

    final rawExpiry = _prefs.getString(_expiryKey);
    state = EntitlementState(
      tier: AppTier.fromKey(_prefs.getString(_tierKey)),
      usage: ledger,
      expiresAt: rawExpiry == null ? null : DateTime.tryParse(rawExpiry),
    );
  }

  /// May the user perform [feature] right now?
  ///
  /// This only reports; it does not consume. Call [consume] after the action
  /// actually succeeds, so a failed request does not cost the user a use.
  AccessDecision check(Feature feature) {
    final limit = TierPolicy.limitFor(state.effectiveTier, feature);
    switch (limit) {
      case Unlimited():
        return AccessDecision.allow(feature);
      case Locked():
        return AccessDecision.deny(
          feature,
          state.isPremium
              ? 'היכולת הזו תופעל בגרסה הבאה'
              : 'זמין בגרסת פרימיום',
        );
      case Quota(:final count, :final period):
        final used = state.usage.countFor(feature, period, DateTime.now());
        if (used >= count) {
          return AccessDecision.deny(
            feature,
            'הגעת למכסה של $count ${period.label} בגרסה הרגילה',
            limit: count,
          );
        }
        return AccessDecision.allow(
          feature,
          remaining: count - used,
          limit: count,
        );
    }
  }

  /// Record one successful use of [feature].
  Future<void> consume(Feature feature) async {
    final limit = TierPolicy.limitFor(state.effectiveTier, feature);
    if (limit is! Quota) return;

    final ledger = state.usage.increment(feature, limit.period, DateTime.now());
    state = EntitlementState(
      tier: state.tier,
      usage: ledger,
      expiresAt: state.expiresAt,
    );
    await _prefs.setString(_usageKey, jsonEncode(ledger.toJson()));
  }

  /// Grant premium. Replace the body with a verified receipt check when
  /// billing or the server lands; every call site stays unchanged.
  Future<void> grantPremium({DateTime? until}) async {
    state = EntitlementState(
      tier: AppTier.premium,
      usage: state.usage,
      expiresAt: until,
    );
    await _prefs.setString(_tierKey, AppTier.premium.key);
    if (until != null) {
      await _prefs.setString(_expiryKey, until.toIso8601String());
    } else {
      await _prefs.remove(_expiryKey);
    }
  }

  Future<void> revokePremium() async {
    state = EntitlementState(usage: state.usage);
    await _prefs.setString(_tierKey, AppTier.free.key);
    await _prefs.remove(_expiryKey);
  }

  /// Development helper — resets the counters without changing the tier.
  Future<void> resetUsage() async {
    state = EntitlementState(
      tier: state.tier,
      expiresAt: state.expiresAt,
    );
    await _prefs.remove(_usageKey);
  }
}

final entitlementProvider =
    StateNotifierProvider<EntitlementController, EntitlementState>(
  (ref) => EntitlementController(ref.watch(sharedPreferencesProvider)),
);

/// Convenience: current effective tier.
final currentTierProvider = Provider<AppTier>(
  (ref) => ref.watch(entitlementProvider).effectiveTier,
);

/// Convenience: access decision for one feature, recomputed as usage changes.
final accessProvider = Provider.family<AccessDecision, Feature>(
  (ref, feature) {
    ref.watch(entitlementProvider);
    return ref.read(entitlementProvider.notifier).check(feature);
  },
);
