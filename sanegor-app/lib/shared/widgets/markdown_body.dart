import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

/// Markdown renderer tuned for right-to-left Hebrew legal text.
///
/// `flutter_markdown` inherits alignment from the ambient [Directionality],
/// but the defaults still need overriding: list bullets, block quotes and
/// code blocks all sit on the wrong side without explicit RTL styling, and
/// legal prose needs a taller line height than the Material defaults give.
class LegalMarkdown extends StatelessWidget {
  const LegalMarkdown({
    super.key,
    required this.data,
    this.selectable = true,
    this.onLinkTap,
  });

  final String data;
  final bool selectable;
  final void Function(String url)? onLinkTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return MarkdownBody(
      data: data,
      selectable: selectable,
      softLineBreak: true,
      onTapLink: (text, href, title) {
        if (href != null) onLinkTap?.call(href);
      },
      styleSheet: MarkdownStyleSheet(
        p: theme.textTheme.bodyMedium?.copyWith(height: 1.7),
        pPadding: const EdgeInsets.only(bottom: 8),
        h1: theme.textTheme.headlineSmall?.copyWith(
          fontWeight: FontWeight.w700,
        ),
        h1Padding: const EdgeInsets.only(top: 16, bottom: 8),
        h2: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700),
        h2Padding: const EdgeInsets.only(top: 14, bottom: 6),
        h3: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        h3Padding: const EdgeInsets.only(top: 12, bottom: 4),
        h4: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
        strong: const TextStyle(fontWeight: FontWeight.w700),
        em: const TextStyle(fontStyle: FontStyle.italic),
        listBullet: theme.textTheme.bodyMedium?.copyWith(height: 1.7),
        listIndent: 20,
        blockquoteDecoration: BoxDecoration(
          color: scheme.surfaceContainerHighest.withValues(alpha: 0.5),
          borderRadius: BorderRadius.circular(10),
          // The accent bar belongs on the right in RTL text.
          border: Border(
            right: BorderSide(color: scheme.primary, width: 3),
          ),
        ),
        blockquotePadding: const EdgeInsets.fromLTRB(12, 10, 14, 10),
        code: theme.textTheme.bodySmall?.copyWith(
          fontFamily: 'monospace',
          backgroundColor: scheme.surfaceContainerHighest,
          letterSpacing: 0,
        ),
        codeblockDecoration: BoxDecoration(
          color: scheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        codeblockPadding: const EdgeInsets.all(12),
        horizontalRuleDecoration: BoxDecoration(
          border: Border(
            top: BorderSide(color: scheme.outlineVariant, width: 1),
          ),
        ),
        tableBorder: TableBorder.all(color: scheme.outlineVariant),
        tableCellsPadding: const EdgeInsets.symmetric(
          horizontal: 10,
          vertical: 6,
        ),
        tableHead: theme.textTheme.labelMedium?.copyWith(
          fontWeight: FontWeight.w700,
        ),
        a: TextStyle(
          color: scheme.primary,
          decoration: TextDecoration.underline,
        ),
        blockSpacing: 10,
      ),
      shrinkWrap: true,
      fitContent: true,
      listItemCrossAxisAlignment: MarkdownListItemCrossAxisAlignment.start,
      bulletBuilder: (parameters) => Padding(
        padding: const EdgeInsets.only(top: 7),
        child: Container(
          width: 5,
          height: 5,
          decoration: BoxDecoration(
            color: scheme.primary,
            shape: BoxShape.circle,
          ),
        ),
      ),
    );
  }
}
