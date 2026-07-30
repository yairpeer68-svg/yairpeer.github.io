import 'package:flutter/material.dart';

/// The application's colour system.
///
/// An original palette built for the product rather than borrowed from any
/// existing legal tool. The seed is a deep indigo — authoritative without the
/// corporate-navy cliché — paired with a warm amber used sparingly for
/// caution. Risk severities get dedicated colours because "how bad is this
/// clause" is the most important signal in the whole app and must not be
/// confused with ordinary emphasis.
abstract final class AppColors {
  static const Color seed = Color(0xFF3F3D91);

  // Brand
  static const Color indigo = Color(0xFF3F3D91);
  static const Color indigoLight = Color(0xFF6B68C4);
  static const Color indigoDark = Color(0xFF232160);
  static const Color amber = Color(0xFFC98A26);
  static const Color parchment = Color(0xFFFBF8F3);
  static const Color ink = Color(0xFF14131F);

  // Risk severity — deliberately distinguishable without relying on hue alone
  // (each is paired with an icon and a label in the UI, for colour-blind users
  // and for anyone reading in bright sunlight).
  static const Color riskHigh = Color(0xFFB3261E);
  static const Color riskMedium = Color(0xFFB26B00);
  static const Color riskLow = Color(0xFF2E6B4F);

  static const Color riskHighContainer = Color(0xFFFBE4E2);
  static const Color riskMediumContainer = Color(0xFFFBEFDC);
  static const Color riskLowContainer = Color(0xFFE0F0E8);

  static const Color riskHighDark = Color(0xFFFFB4AB);
  static const Color riskMediumDark = Color(0xFFF2C078);
  static const Color riskLowDark = Color(0xFF8FD6B4);

  static const Color riskHighContainerDark = Color(0xFF4E1310);
  static const Color riskMediumContainerDark = Color(0xFF4A3208);
  static const Color riskLowContainerDark = Color(0xFF10331F);

  /// Source-type accents, used on citation chips.
  static const Color legislation = Color(0xFF3F3D91);
  static const Color regulation = Color(0xFF4C6B8A);
  static const Color ruling = Color(0xFF7A4B8C);
  static const Color guideline = Color(0xFF5B6B57);

  static ColorScheme light() => ColorScheme.fromSeed(
        seedColor: seed,
        brightness: Brightness.light,
      ).copyWith(
        surface: const Color(0xFFFDFCFA),
        surfaceContainerLowest: Colors.white,
        surfaceContainerLow: const Color(0xFFF8F6F2),
        surfaceContainer: const Color(0xFFF2EFEA),
      );

  static ColorScheme dark() => ColorScheme.fromSeed(
        seedColor: seed,
        brightness: Brightness.dark,
      ).copyWith(
        surface: const Color(0xFF131218),
        surfaceContainerLowest: const Color(0xFF0D0C11),
        surfaceContainerLow: const Color(0xFF1A1921),
        surfaceContainer: const Color(0xFF222029),
      );
}

/// Severity levels shown on analysis findings.
enum RiskSeverity {
  high('high', 'סיכון גבוה', Icons.error_outline),
  medium('medium', 'סיכון בינוני', Icons.warning_amber_outlined),
  low('low', 'סיכון נמוך', Icons.info_outline);

  const RiskSeverity(this.key, this.label, this.icon);

  final String key;
  final String label;
  final IconData icon;

  static RiskSeverity fromKey(String? key) => switch (key) {
        'high' => RiskSeverity.high,
        'low' => RiskSeverity.low,
        _ => RiskSeverity.medium,
      };

  Color color(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    return switch (this) {
      RiskSeverity.high =>
        isDark ? AppColors.riskHighDark : AppColors.riskHigh,
      RiskSeverity.medium =>
        isDark ? AppColors.riskMediumDark : AppColors.riskMedium,
      RiskSeverity.low => isDark ? AppColors.riskLowDark : AppColors.riskLow,
    };
  }

  Color container(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    return switch (this) {
      RiskSeverity.high => isDark
          ? AppColors.riskHighContainerDark
          : AppColors.riskHighContainer,
      RiskSeverity.medium => isDark
          ? AppColors.riskMediumContainerDark
          : AppColors.riskMediumContainer,
      RiskSeverity.low =>
        isDark ? AppColors.riskLowContainerDark : AppColors.riskLowContainer,
    };
  }
}
