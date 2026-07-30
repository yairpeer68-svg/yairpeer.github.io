import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../shared/widgets/citation_card.dart';
import '../../../../shared/widgets/markdown_body.dart';
import '../../../../shared/widgets/states.dart';
import '../../domain/chat_message.dart';

/// One turn in the conversation.
class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.message,
    this.onPin,
    this.onRetry,
    this.onShare,
  });

  final ChatMessage message;
  final VoidCallback? onPin;
  final VoidCallback? onRetry;
  final void Function(String content)? onShare;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final isUser = message.isUser;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Column(
        crossAxisAlignment:
            isUser ? CrossAxisAlignment.end : CrossAxisAlignment.start,
        children: [
          ConstrainedBox(
            constraints: BoxConstraints(
              maxWidth: MediaQuery.sizeOf(context).width * (isUser ? 0.82 : 0.94),
            ),
            child: GestureDetector(
              onLongPress: () => _showActions(context),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 14,
                  vertical: 12,
                ),
                decoration: BoxDecoration(
                  color: isUser
                      ? scheme.primaryContainer
                      : scheme.surfaceContainerLow,
                  borderRadius: BorderRadius.only(
                    topRight: const Radius.circular(18),
                    topLeft: const Radius.circular(18),
                    bottomRight: Radius.circular(isUser ? 4 : 18),
                    bottomLeft: Radius.circular(isUser ? 18 : 4),
                  ),
                  border: isUser
                      ? null
                      : Border.all(
                          color: scheme.outlineVariant.withValues(alpha: 0.6),
                        ),
                ),
                child: _content(context),
              ),
            ),
          ),
          if (message.isPinned)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.push_pin, size: 12, color: scheme.primary),
                  const SizedBox(width: 4),
                  Text(
                    'נעוץ',
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: scheme.primary,
                    ),
                  ),
                ],
              ),
            ),
          if (message.isAssistant && message.hasCitations)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: CitationList(citations: message.citations),
            ),
        ],
      ),
    );
  }

  Widget _content(BuildContext context) {
    final theme = Theme.of(context);

    if (message.hasFailed) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.error_outline, size: 18, color: theme.colorScheme.error),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              message.error ?? 'התשובה נכשלה',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: theme.colorScheme.error,
              ),
            ),
          ),
          if (onRetry != null) ...[
            const SizedBox(width: 4),
            TextButton(onPressed: onRetry, child: const Text('נסה שוב')),
          ],
        ],
      );
    }

    if (message.isUser) {
      return Text(
        message.content,
        style: theme.textTheme.bodyMedium?.copyWith(height: 1.6),
      );
    }

    if (message.content.isEmpty && message.isStreaming) {
      return const _TypingIndicator();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        LegalMarkdown(data: message.content, selectable: !message.isStreaming),
        if (message.isStreaming) ...[
          const SizedBox(height: 6),
          const _Caret(),
        ],
      ],
    );
  }

  void _showActions(BuildContext context) {
    if (message.content.isEmpty) return;
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.copy_outlined),
              title: const Text('העתקה'),
              onTap: () async {
                Navigator.of(sheetContext).pop();
                await Clipboard.setData(ClipboardData(text: message.content));
                if (context.mounted) showMessage(context, 'הועתק');
              },
            ),
            if (onShare != null)
              ListTile(
                leading: const Icon(Icons.ios_share),
                title: const Text('שיתוף'),
                onTap: () {
                  Navigator.of(sheetContext).pop();
                  onShare?.call(message.content);
                },
              ),
            if (onPin != null)
              ListTile(
                leading: Icon(
                  message.isPinned
                      ? Icons.push_pin_outlined
                      : Icons.push_pin,
                ),
                title: Text(message.isPinned ? 'ביטול נעיצה' : 'נעיצת הודעה'),
                onTap: () {
                  Navigator.of(sheetContext).pop();
                  onPin?.call();
                },
              ),
          ],
        ),
      ),
    );
  }
}

/// Three-dot indicator shown before the first token arrives.
class _TypingIndicator extends StatefulWidget {
  const _TypingIndicator();

  @override
  State<_TypingIndicator> createState() => _TypingIndicatorState();
}

class _TypingIndicatorState extends State<_TypingIndicator>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1200),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.onSurfaceVariant;
    return SizedBox(
      height: 20,
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) => Row(
          mainAxisSize: MainAxisSize.min,
          children: List.generate(3, (index) {
            // Stagger each dot by a third of the cycle.
            final phase = (_controller.value - index * 0.22) % 1.0;
            final opacity = phase < 0.5 ? 0.3 + phase * 1.4 : 1.7 - phase * 1.4;
            return Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2.5),
              child: Container(
                width: 7,
                height: 7,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: opacity.clamp(0.25, 1.0)),
                  shape: BoxShape.circle,
                ),
              ),
            );
          }),
        ),
      ),
    );
  }
}

/// Blinking caret appended while text streams in.
class _Caret extends StatefulWidget {
  const _Caret();

  @override
  State<_Caret> createState() => _CaretState();
}

class _CaretState extends State<_Caret> with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 700),
  )..repeat(reverse: true);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => FadeTransition(
        opacity: _controller,
        child: Container(
          width: 8,
          height: 14,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.primary,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
      );
}
