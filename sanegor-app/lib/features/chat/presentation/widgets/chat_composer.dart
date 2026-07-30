import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:speech_to_text/speech_to_text.dart';

import '../../../../shared/widgets/states.dart';

/// The message input: text, attachments, and Hebrew voice dictation.
class ChatComposer extends StatefulWidget {
  const ChatComposer({
    super.key,
    required this.onSend,
    required this.isSending,
    this.onCancel,
    this.onAttach,
    this.attachmentCount = 0,
    this.onRemoveAttachments,
    this.initialText,
  });

  final void Function(String text) onSend;
  final bool isSending;
  final VoidCallback? onCancel;
  final VoidCallback? onAttach;
  final int attachmentCount;
  final VoidCallback? onRemoveAttachments;
  final String? initialText;

  @override
  State<ChatComposer> createState() => ChatComposerState();
}

class ChatComposerState extends State<ChatComposer> {
  final TextEditingController _controller = TextEditingController();
  final FocusNode _focusNode = FocusNode();
  final SpeechToText _speech = SpeechToText();

  bool _listening = false;
  bool _speechReady = false;

  @override
  void initState() {
    super.initState();
    if (widget.initialText != null) _controller.text = widget.initialText!;
    _controller.addListener(_onTextChanged);
  }

  @override
  void dispose() {
    _controller
      ..removeListener(_onTextChanged)
      ..dispose();
    _focusNode.dispose();
    if (_listening) _speech.stop();
    super.dispose();
  }

  void _onTextChanged() => setState(() {});

  /// Fill the field from outside (used by the suggested-question chips).
  void setText(String text) {
    _controller.text = text;
    _controller.selection =
        TextSelection.collapsed(offset: _controller.text.length);
    _focusNode.requestFocus();
  }

  void _send() {
    final text = _controller.text.trim();
    if (text.isEmpty || widget.isSending) return;
    _controller.clear();
    widget.onSend(text);
  }

  Future<void> _toggleDictation() async {
    if (_listening) {
      await _speech.stop();
      setState(() => _listening = false);
      return;
    }

    if (!_speechReady) {
      _speechReady = await _speech.initialize(
        onStatus: (status) {
          if (status == 'done' || status == 'notListening') {
            if (mounted) setState(() => _listening = false);
          }
        },
        onError: (error) {
          if (mounted) {
            setState(() => _listening = false);
            showMessage(context, 'זיהוי הדיבור נכשל', isError: true);
          }
        },
      );
    }
    if (!_speechReady) {
      if (mounted) {
        showMessage(
          context,
          'זיהוי דיבור אינו זמין במכשיר זה',
          isError: true,
        );
      }
      return;
    }

    // Append rather than replace, so dictation can extend a typed question.
    final existing = _controller.text;
    await _speech.listen(
      localeId: 'he_IL',
      listenOptions: SpeechListenOptions(
        listenMode: ListenMode.dictation,
        partialResults: true,
        cancelOnError: true,
      ),
      onResult: (result) {
        final prefix = existing.isEmpty ? '' : '$existing ';
        _controller.text = '$prefix${result.recognizedWords}';
        _controller.selection =
            TextSelection.collapsed(offset: _controller.text.length);
      },
    );
    setState(() => _listening = true);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hasText = _controller.text.trim().isNotEmpty;

    return Container(
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(
          top: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.6)),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (widget.attachmentCount > 0)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 10, 16, 0),
                child: Row(
                  children: [
                    Icon(
                      Icons.attach_file,
                      size: 16,
                      color: scheme.primary,
                    ),
                    const SizedBox(width: 6),
                    Text(
                      '${widget.attachmentCount} מסמכים מצורפים',
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: scheme.primary,
                      ),
                    ),
                    const Spacer(),
                    TextButton(
                      onPressed: widget.onRemoveAttachments,
                      child: const Text('הסר'),
                    ),
                  ],
                ),
              ),
            Padding(
              padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  IconButton(
                    onPressed: widget.isSending ? null : widget.onAttach,
                    icon: const Icon(Icons.attach_file),
                    tooltip: 'צירוף מסמך',
                  ),
                  Expanded(
                    child: ConstrainedBox(
                      // Grow up to five lines, then scroll inside the field —
                      // a long question must not push the send button away.
                      constraints: const BoxConstraints(maxHeight: 140),
                      child: TextField(
                        controller: _controller,
                        focusNode: _focusNode,
                        maxLines: null,
                        minLines: 1,
                        textInputAction: TextInputAction.newline,
                        keyboardType: TextInputType.multiline,
                        enabled: !widget.isSending,
                        decoration: InputDecoration(
                          hintText: _listening
                              ? 'מקשיב…'
                              : 'שאל שאלה משפטית…',
                          border: InputBorder.none,
                          enabledBorder: InputBorder.none,
                          focusedBorder: InputBorder.none,
                          filled: false,
                          contentPadding: const EdgeInsets.symmetric(
                            horizontal: 4,
                            vertical: 12,
                          ),
                        ),
                        style: theme.textTheme.bodyMedium,
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: widget.isSending ? null : _toggleDictation,
                    icon: Icon(_listening ? Icons.stop_circle : Icons.mic_none),
                    color: _listening ? scheme.error : null,
                    tooltip: _listening ? 'עצור הקלטה' : 'הקלדה קולית',
                  ),
                  const SizedBox(width: 2),
                  _SendButton(
                    isSending: widget.isSending,
                    enabled: hasText,
                    onSend: () {
                      HapticFeedback.selectionClick();
                      _send();
                    },
                    onCancel: widget.onCancel,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SendButton extends StatelessWidget {
  const _SendButton({
    required this.isSending,
    required this.enabled,
    required this.onSend,
    this.onCancel,
  });

  final bool isSending;
  final bool enabled;
  final VoidCallback onSend;
  final VoidCallback? onCancel;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;

    // While an answer streams the same control becomes "stop", so the user is
    // never stuck watching a response they no longer want.
    if (isSending) {
      return IconButton.filledTonal(
        onPressed: onCancel,
        icon: const Icon(Icons.stop),
        tooltip: 'עצירה',
      );
    }

    return AnimatedScale(
      scale: enabled ? 1 : 0.9,
      duration: const Duration(milliseconds: 150),
      child: IconButton.filled(
        onPressed: enabled ? onSend : null,
        icon: const Icon(Icons.arrow_upward, size: 20),
        tooltip: 'שליחה',
        style: IconButton.styleFrom(
          backgroundColor:
              enabled ? scheme.primary : scheme.surfaceContainerHighest,
          foregroundColor:
              enabled ? scheme.onPrimary : scheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
