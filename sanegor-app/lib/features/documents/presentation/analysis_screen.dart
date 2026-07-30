import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:share_plus/share_plus.dart';

import '../../../core/providers.dart';
import '../../../core/theme/app_colors.dart';
import '../../../shared/widgets/citation_card.dart';
import '../../../shared/widgets/disclaimer_banner.dart';
import '../../../shared/widgets/markdown_body.dart';
import '../../../shared/widgets/states.dart';
import '../../chat/domain/citation.dart';
import '../domain/document.dart';
import 'documents_controller.dart';

/// Renders one analysis run: summary, scores, risks and recommendations.
class AnalysisScreen extends ConsumerWidget {
  const AnalysisScreen({
    super.key,
    required this.documentId,
    required this.kind,
  });

  final String documentId;
  final String kind;

  String get _title => switch (kind) {
        'contract' => 'ניתוח חוזה',
        'case_summary' => 'סיכום פסק דין',
        _ => 'ניתוח מסמך',
      };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final args = (documentId: documentId, kind: kind);
    final analysis = ref.watch(analysisProvider(args));

    return Scaffold(
      appBar: AppBar(
        title: Text(_title),
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'ניתוח מחדש',
            onPressed: () => ref.invalidate(analysisProvider(args)),
          ),
        ],
      ),
      body: analysis.when(
        loading: () => const LoadingState(
          message: 'מנתח את המסמך…\nזה עשוי לקחת עד דקה',
        ),
        error: (error, _) => ErrorState(
          error: error,
          onRetry: () => ref.invalidate(analysisProvider(args)),
        ),
        data: (result) => _AnalysisBody(result: result, title: _title),
      ),
    );
  }
}

class _AnalysisBody extends StatelessWidget {
  const _AnalysisBody({required this.result, required this.title});

  final DocumentAnalysis result;
  final String title;

  @override
  Widget build(BuildContext context) {
    final markdown = result.markdown;

    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
      children: [
        if (result.cached)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              children: [
                Icon(
                  Icons.history,
                  size: 14,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
                const SizedBox(width: 6),
                Text(
                  'תוצאה שמורה — לחץ על רענון לניתוח מחדש',
                  style: Theme.of(context).textTheme.labelSmall,
                ),
              ],
            ),
          ),

        if (result.riskScore != null || result.complexityScore != null)
          _ScoreRow(
            riskScore: result.riskScore,
            complexityScore: result.complexityScore,
          ),

        // Case summaries come back as prose; the other kinds are structured.
        if (markdown != null && markdown.isNotEmpty) ...[
          const SizedBox(height: 16),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: LegalMarkdown(data: markdown),
            ),
          ),
        ] else ...[
          if (result.summary.isNotEmpty)
            _Section(
              title: 'סיכום',
              icon: Icons.summarize_outlined,
              child: Text(
                result.summary,
                style: Theme.of(context)
                    .textTheme
                    .bodyMedium
                    ?.copyWith(height: 1.7),
              ),
            ),

          if (result.risks.isNotEmpty)
            _Section(
              title: 'סיכונים',
              icon: Icons.warning_amber_outlined,
              child: Column(
                children: [
                  for (final risk in result.risks) _RiskCard(risk: risk),
                ],
              ),
            ),

          if (result.keyPoints.isNotEmpty)
            _Section(
              title: 'נקודות מרכזיות',
              icon: Icons.key_outlined,
              child: Column(
                children: [
                  for (final point in result.keyPoints)
                    _DetailTile(
                      title: (point['title'] ?? '').toString(),
                      detail: (point['detail'] ?? '').toString(),
                    ),
                ],
              ),
            ),

          if (result.obligations.isNotEmpty)
            _Section(
              title: 'התחייבויות',
              icon: Icons.assignment_turned_in_outlined,
              child: Column(
                children: [
                  for (final item in result.obligations)
                    _DetailTile(
                      title: (item['party'] ?? '').toString(),
                      detail: (item['obligation'] ?? '').toString(),
                    ),
                ],
              ),
            ),

          if (result.dates.isNotEmpty)
            _Section(
              title: 'מועדים',
              icon: Icons.event_outlined,
              child: Column(
                children: [
                  for (final item in result.dates)
                    _DetailTile(
                      title:
                          '${item['label'] ?? ''} — ${item['date'] ?? 'לא צוין'}',
                      detail: (item['significance'] ?? '').toString(),
                      icon: Icons.schedule,
                    ),
                ],
              ),
            ),

          if (result.missingClauses.isNotEmpty)
            _Section(
              title: 'סעיפים חסרים',
              icon: Icons.playlist_add_outlined,
              child: _BulletList(items: result.missingClauses),
            ),

          if (result.problematicTerms.isNotEmpty)
            _Section(
              title: 'תנאים בעייתיים',
              icon: Icons.report_gmailerrorred_outlined,
              child: Column(
                children: [
                  for (final term in result.problematicTerms)
                    _DetailTile(
                      title: (term['clause'] ?? '').toString(),
                      detail: [
                        (term['why'] ?? '').toString(),
                        if (term['suggested_wording'] != null)
                          'נוסח מוצע: ${term['suggested_wording']}',
                      ].where((s) => s.isNotEmpty).join('\n\n'),
                    ),
                ],
              ),
            ),

          if (result.contradictions.isNotEmpty)
            _Section(
              title: 'סתירות',
              icon: Icons.compare_arrows_outlined,
              child: Column(
                children: [
                  for (final item in result.contradictions)
                    _DetailTile(
                      title: (item['between'] ?? '').toString(),
                      detail: (item['detail'] ?? '').toString(),
                    ),
                ],
              ),
            ),

          if (result.negotiationPoints.isNotEmpty)
            _Section(
              title: 'נקודות למשא ומתן',
              icon: Icons.handshake_outlined,
              child: _BulletList(items: result.negotiationPoints, numbered: true),
            ),

          if (result.recommendations.isNotEmpty)
            _Section(
              title: 'המלצות',
              icon: Icons.lightbulb_outline,
              child: _BulletList(items: result.recommendations, numbered: true),
            ),

          if (result.questionsForLawyer.isNotEmpty)
            _Section(
              title: 'שאלות לעורך דין',
              icon: Icons.contact_support_outlined,
              child: _BulletList(items: result.questionsForLawyer),
            ),
        ],

        if (result.citations.isNotEmpty) ...[
          const SizedBox(height: 8),
          CitationList(
            citations: result.citations
                .whereType<Map<String, dynamic>>()
                .map(Citation.fromJson)
                .toList(),
            initiallyExpanded: true,
          ),
        ],

        const SizedBox(height: 8),
        const DisclaimerBanner(margin: EdgeInsets.zero),
        const SizedBox(height: 16),
        Consumer(
          builder: (context, ref, _) => OutlinedButton.icon(
            onPressed: () => _export(context, ref),
            icon: const Icon(Icons.ios_share),
            label: const Text('ייצוא ושיתוף'),
          ),
        ),
      ],
    );
  }

  Future<void> _export(BuildContext context, WidgetRef ref) async {
    showMessage(context, 'מכין קובץ…');
    try {
      final file = await ref.read(documentsRepositoryProvider).export(
            format: 'pdf',
            content: _asMarkdown(),
            title: title,
          );
      await SharePlus.instance.share(
        ShareParams(files: [XFile(file.path)], title: title),
      );
    } on Object catch (error) {
      if (context.mounted) showMessage(context, '$error', isError: true);
    }
  }

  /// Flatten the structured analysis back into markdown for export.
  String _asMarkdown() {
    if (result.markdown case final markdown? when markdown.isNotEmpty) {
      return markdown;
    }
    final buffer = StringBuffer()
      ..writeln('## סיכום')
      ..writeln()
      ..writeln(result.summary)
      ..writeln();

    void section(String heading, List<String> lines) {
      if (lines.isEmpty) return;
      buffer
        ..writeln('## $heading')
        ..writeln();
      for (final line in lines) {
        buffer.writeln('- $line');
      }
      buffer.writeln();
    }

    section(
      'סיכונים',
      [
        for (final risk in result.risks)
          '**${risk['title'] ?? ''}** — ${risk['detail'] ?? ''}'
              '${risk['recommendation'] != null ? '\n  המלצה: ${risk['recommendation']}' : ''}',
      ],
    );
    section('סעיפים חסרים', result.missingClauses);
    section('נקודות למשא ומתן', result.negotiationPoints);
    section('המלצות', result.recommendations);
    section('שאלות לעורך דין', result.questionsForLawyer);
    return buffer.toString();
  }
}

class _ScoreRow extends StatelessWidget {
  const _ScoreRow({this.riskScore, this.complexityScore});

  final int? riskScore;
  final int? complexityScore;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          if (riskScore != null)
            Expanded(
              child: _ScoreCard(
                label: 'רמת סיכון',
                score: riskScore!,
                icon: Icons.shield_outlined,
                // High risk should read as high, so the scale is not inverted.
                severity: RiskSeverity.fromKey(
                  riskScore! >= 7 ? 'high' : (riskScore! >= 4 ? 'medium' : 'low'),
                ),
              ),
            ),
          if (riskScore != null && complexityScore != null)
            const SizedBox(width: 12),
          if (complexityScore != null)
            Expanded(
              child: _ScoreCard(
                label: 'מורכבות',
                score: complexityScore!,
                icon: Icons.account_tree_outlined,
                severity: RiskSeverity.fromKey(
                  complexityScore! >= 7
                      ? 'high'
                      : (complexityScore! >= 4 ? 'medium' : 'low'),
                ),
              ),
            ),
        ],
      );
}

class _ScoreCard extends StatelessWidget {
  const _ScoreCard({
    required this.label,
    required this.score,
    required this.icon,
    required this.severity,
  });

  final String label;
  final int score;
  final IconData icon;
  final RiskSeverity severity;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = severity.color(theme.brightness);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, size: 16, color: color),
                const SizedBox(width: 6),
                Text(label, style: theme.textTheme.labelMedium),
              ],
            ),
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Text(
                  '$score',
                  style: theme.textTheme.headlineMedium?.copyWith(
                    color: color,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(' / 10', style: theme.textTheme.bodySmall),
              ],
            ),
            const SizedBox(height: 8),
            ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: score / 10,
                minHeight: 5,
                color: color,
                backgroundColor: theme.colorScheme.surfaceContainerHighest,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.icon, required this.child});

  final String title;
  final IconData icon;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: theme.colorScheme.primary),
              const SizedBox(width: 8),
              Text(title, style: theme.textTheme.titleMedium),
            ],
          ),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

class _RiskCard extends StatelessWidget {
  const _RiskCard({required this.risk});

  final Map<String, dynamic> risk;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final severity = RiskSeverity.fromKey(risk['severity']?.toString());
    final color = severity.color(theme.brightness);
    final recommendation = risk['recommendation']?.toString();
    final clause = risk['clause']?.toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: severity.container(theme.brightness),
        borderRadius: BorderRadius.circular(16),
        // The severity bar sits on the leading (right) edge in RTL.
        border: Border(right: BorderSide(color: color, width: 3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(severity.icon, size: 16, color: color),
              const SizedBox(width: 6),
              Text(
                severity.label,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: color,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if ((risk['title'] ?? '').toString().isNotEmpty)
            Text(
              risk['title'].toString(),
              style: theme.textTheme.titleSmall,
            ),
          if (clause != null && clause.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              clause,
              style: theme.textTheme.bodySmall?.copyWith(
                fontStyle: FontStyle.italic,
              ),
            ),
          ],
          const SizedBox(height: 6),
          Text(
            (risk['detail'] ?? '').toString(),
            style: theme.textTheme.bodyMedium?.copyWith(height: 1.6),
          ),
          if (recommendation != null && recommendation.isNotEmpty) ...[
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.arrow_back, size: 14, color: color),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    recommendation,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontWeight: FontWeight.w600,
                      height: 1.5,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

class _DetailTile extends StatelessWidget {
  const _DetailTile({required this.title, required this.detail, this.icon});

  final String title;
  final String detail;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    if (title.isEmpty && detail.isEmpty) return const SizedBox.shrink();

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: theme.colorScheme.outlineVariant),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 16, color: theme.colorScheme.primary),
            const SizedBox(width: 8),
          ],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (title.isNotEmpty)
                  Text(title, style: theme.textTheme.titleSmall),
                if (detail.isNotEmpty) ...[
                  if (title.isNotEmpty) const SizedBox(height: 4),
                  Text(
                    detail,
                    style: theme.textTheme.bodyMedium?.copyWith(height: 1.6),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _BulletList extends StatelessWidget {
  const _BulletList({required this.items, this.numbered = false});

  final List<String> items;
  final bool numbered;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final (index, item) in items.indexed)
          Padding(
            padding: const EdgeInsets.only(bottom: 10),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (numbered)
                  Container(
                    width: 22,
                    height: 22,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: theme.colorScheme.primaryContainer,
                      shape: BoxShape.circle,
                    ),
                    child: Text(
                      '${index + 1}',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.onPrimaryContainer,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  )
                else
                  Padding(
                    padding: const EdgeInsets.only(top: 7),
                    child: Container(
                      width: 5,
                      height: 5,
                      decoration: BoxDecoration(
                        color: theme.colorScheme.primary,
                        shape: BoxShape.circle,
                      ),
                    ),
                  ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    item,
                    style: theme.textTheme.bodyMedium?.copyWith(height: 1.6),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
