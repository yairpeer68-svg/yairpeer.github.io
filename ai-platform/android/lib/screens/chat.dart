import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class ChatScreen extends StatefulWidget {
  final AppState state;
  const ChatScreen({super.key, required this.state});
  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final input = TextEditingController();
  final messages = <Map<String, String>>[];
  bool sending = false;
  String error = '';
  Future<void> send() async {
    final text = input.text.trim();
    if (text.isEmpty || sending) return;
    setState(() {
      messages.add({'role': 'user', 'content': text});
      input.clear();
      sending = true;
      error = '';
    });
    try {
      final r = await widget.state.api.dio.post('/ai/chat', data: {
        'messages': messages
            .map((m) => {'role': m['role'], 'content': m['content']})
            .toList(),
        'max_tokens': 1024,
        'temperature': 0.7,
        'cache': true
      });
      final d = Map<String, dynamic>.from(r.data as Map);
      setState(() => messages
          .add({'role': 'assistant', 'content': d['content'] as String}));
    } catch (e) {
      setState(() => error = widget.state.api.mapError(e).message);
    } finally {
      if (mounted) setState(() => sending = false);
    }
  }

  @override
  void dispose() {
    input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return Column(children: [
      if (error.isNotEmpty)
        MaterialBanner(content: Text(error), actions: [
          TextButton(
              onPressed: () => setState(() => error = ''),
              child: const Text('OK'))
        ]),
      Expanded(
          child: messages.isEmpty
              ? const Center(child: Text('Start a conversation'))
              : ListView.builder(
                  padding: const EdgeInsets.all(12),
                  itemCount: messages.length,
                  itemBuilder: (c, i) {
                    final m = messages[i];
                    final mine = m['role'] == 'user';
                    return Align(
                        alignment: mine
                            ? AlignmentDirectional.centerEnd
                            : AlignmentDirectional.centerStart,
                        child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 700),
                            child: Card(
                                child: Padding(
                                    padding: const EdgeInsets.all(12),
                                    child: SelectableText(m['content']!)))));
                  })),
      SafeArea(
          top: false,
          child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(children: [
                Expanded(
                    child: TextField(
                        controller: input,
                        minLines: 1,
                        maxLines: 5,
                        onSubmitted: (_) => send(),
                        decoration: const InputDecoration(
                            border: OutlineInputBorder(),
                            hintText: 'Message'))),
                const SizedBox(width: 8),
                IconButton.filled(
                    onPressed: sending ? null : send,
                    icon: sending
                        ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.send),
                    tooltip: t.t('send'))
              ])))
    ]);
  }
}
