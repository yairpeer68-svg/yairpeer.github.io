import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';

import '../../../core/config/app_config.dart';
import '../../../core/theme/app_colors.dart';

/// Shown while the session is being restored.
///
/// The router keeps this screen mounted until auth resolves, so the animation
/// covers real work rather than an artificial delay.
class SplashScreen extends StatelessWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return Scaffold(
      body: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: isDark
                ? [AppColors.indigoDark, theme.colorScheme.surface]
                : [AppColors.indigo.withValues(alpha: 0.08), theme.colorScheme.surface],
          ),
        ),
        child: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const _Mark()
                  .animate()
                  .scale(
                    duration: 600.ms,
                    curve: Curves.easeOutBack,
                    begin: const Offset(0.7, 0.7),
                  )
                  .fadeIn(duration: 400.ms),
              const SizedBox(height: 24),
              Text(
                AppConfig.appName,
                style: theme.textTheme.displaySmall?.copyWith(
                  fontWeight: FontWeight.w700,
                  color: isDark ? Colors.white : AppColors.indigoDark,
                ),
              ).animate(delay: 250.ms).fadeIn(duration: 400.ms).slideY(begin: 0.2),
              const SizedBox(height: 6),
              Text(
                AppConfig.appTagline,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ).animate(delay: 400.ms).fadeIn(duration: 400.ms),
              const SizedBox(height: 48),
              SizedBox(
                width: 32,
                height: 32,
                child: CircularProgressIndicator(
                  strokeWidth: 2.5,
                  color: theme.colorScheme.primary,
                ),
              ).animate(delay: 600.ms).fadeIn(),
            ],
          ),
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(32, 0, 32, 24),
          child: Text(
            'מידע משפטי כללי — אינו ייעוץ משפטי',
            textAlign: TextAlign.center,
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ).animate(delay: 800.ms).fadeIn(),
        ),
      ),
    );
  }
}

/// The app mark: an original geometric scales-of-justice motif drawn in code,
/// so there is no bitmap asset and nothing borrowed from another product.
class _Mark extends StatelessWidget {
  const _Mark();

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final color = isDark ? Colors.white : AppColors.indigo;

    return Container(
      width: 104,
      height: 104,
      decoration: BoxDecoration(
        color: isDark
            ? Colors.white.withValues(alpha: 0.08)
            : AppColors.indigo.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(28),
      ),
      child: CustomPaint(painter: _ScalesPainter(color)),
    );
  }
}

class _ScalesPainter extends CustomPainter {
  const _ScalesPainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 3.2
      ..strokeCap = StrokeCap.round
      ..style = PaintingStyle.stroke;

    final centreX = size.width / 2;
    final top = size.height * 0.26;
    final bottom = size.height * 0.76;

    // Central column
    canvas.drawLine(Offset(centreX, top), Offset(centreX, bottom), paint);
    // Beam
    final beamY = top + 4;
    final armSpan = size.width * 0.26;
    canvas.drawLine(
      Offset(centreX - armSpan, beamY),
      Offset(centreX + armSpan, beamY),
      paint,
    );
    // Base
    canvas.drawLine(
      Offset(centreX - size.width * 0.16, bottom),
      Offset(centreX + size.width * 0.16, bottom),
      paint,
    );

    // Pans, drawn as open arcs so the mark reads at small sizes.
    final panRadius = size.width * 0.13;
    for (final direction in [-1, 1]) {
      final x = centreX + direction * armSpan;
      final panTop = beamY + size.height * 0.12;
      canvas.drawLine(Offset(x, beamY), Offset(x, panTop), paint);
      canvas.drawArc(
        Rect.fromCircle(center: Offset(x, panTop), radius: panRadius),
        0,
        3.14159,
        false,
        paint,
      );
    }

    // Apex dot
    canvas.drawCircle(
      Offset(centreX, top - 2),
      3.2,
      Paint()..color = color,
    );
  }

  @override
  bool shouldRepaint(_ScalesPainter oldDelegate) => oldDelegate.color != color;
}
