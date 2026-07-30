import 'package:flutter/material.dart';

import '../../features/chat/domain/citation.dart';

/// A source card shown under an answer.
///
/// Every citation the app displays resolves to a record in the backend's
/// corpus, so tapping one always leads somewhere real. The numbered badge
/// matches the `[מקור N]` marker inside the answer text.
class CitationCard extends StatelessWidget {
  const CitationCard({super.key, required this.citation, this.onTap});

  final Citation citation;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final accent = citation.typeColor;

    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 34,
                height: 34,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: accent.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  '${citation.index}',
                  style: theme.textTheme.labelLarge?.copyWith(
                    color: accent,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(citation.typeIcon, size: 14, color: accent),
                        const SizedBox(width: 5),
                        Text(
                          citation.typeLabel,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: accent,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      citation.title,
                      style: theme.textTheme.titleSmall,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (citation.subtitle.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        citation.subtitle,
                        style: theme.textTheme.bodySmall,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    if (citation.snippet.isNotEmpty) ...[
                      const SizedBox(height: 8),
                      Text(
                        citation.snippet,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                          height: 1.5,
                        ),
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              Icon(
                Icons.chevron_left,
                size: 20,
                color: scheme.onSurfaceVariant,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Collapsible list of the sources behind one answer.
class CitationList extends StatefulWidget {
  const CitationList({
    super.key,
    required this.citations,
    this.title = 'מקורות',
    this.initiallyExpanded = false,
  });

  final List<Citation> citations;
  final String title;
  final bool initiallyExpanded;

  @override
  State<CitationList> createState() => _CitationListState();
}

class _CitationListState extends State<CitationList> {
  late bool _expanded = widget.initiallyExpanded;

  @override
  Widget build(BuildContext context) {
    if (widget.citations.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: () => setState(() => _expanded = !_expanded),
          borderRadius: BorderRadius.circular(10),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(
                  Icons.menu_book_outlined,
                  size: 16,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 6),
                Text(
                  '${widget.title} (${widget.citations.length})',
                  style: theme.textTheme.labelMedium?.copyWith(
                    color: theme.colorScheme.primary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                Icon(
                  _expanded ? Icons.expand_less : Icons.expand_more,
                  size: 18,
                  color: theme.colorScheme.primary,
                ),
              ],
            ),
          ),
        ),
        AnimatedCrossFade(
          duration: const Duration(milliseconds: 220),
          crossFadeState:
              _expanded ? CrossFadeState.showSecond : CrossFadeState.showFirst,
          firstChild: const SizedBox(width: double.infinity),
          secondChild: Column(
            children: [
              for (final citation in widget.citations)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: CitationCard(
                    citation: citation,
                    onTap: () => _showDetail(context, citation),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  void _showDetail(BuildContext context, Citation citation) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.6,
        maxChildSize: 0.9,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
          children: [
            Row(
              children: [
                Icon(citation.typeIcon, color: citation.typeColor),
                const SizedBox(width: 8),
                Text(
                  citation.typeLabel,
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: citation.typeColor,
                      ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(citation.title, style: Theme.of(context).textTheme.titleLarge),
            if (citation.subtitle.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                citation.subtitle,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ],
            const Divider(height: 32),
            if (citation.snippet.isNotEmpty)
              Text(
                citation.snippet,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      height: 1.7,
                    ),
              ),
            const SizedBox(height: 24),
            Text(
              'מזהה במאגר: ${citation.citationKey}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}
