import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/config.dart';
import '../core/i18n.dart';

/// First-run screen that binds the app to a deployment.
///
/// A distributed APK cannot know the operator's domain, so the address is entered here
/// and stored on the device instead of being compiled in.
class ServerSetupScreen extends StatefulWidget {
  final AppState state;

  /// True when opened from Settings to change an already configured server.
  final bool allowCancel;
  const ServerSetupScreen(
      {super.key, required this.state, this.allowCancel = false});

  @override
  State<ServerSetupScreen> createState() => _ServerSetupScreenState();
}

class _ServerSetupScreenState extends State<ServerSetupScreen> {
  late final TextEditingController controller = TextEditingController(
      text: widget.state.serverConfigured ? widget.state.serverBaseUrl : '');
  String? error;
  String? detected;
  bool busy = false;

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  Future<void> connect() async {
    final t = Strings.of(context);
    final raw = controller.text;
    final invalid = AppConfig.validationError(raw);
    if (invalid != null) {
      setState(() {
        error = t.t(invalid);
        detected = null;
      });
      return;
    }
    setState(() {
      busy = true;
      error = null;
      detected = null;
    });
    try {
      // Confirm the address actually serves this platform before storing it, so a typo
      // surfaces here rather than as a failed login later.
      final version = await widget.state.probeServer(raw);
      await widget.state.setServer(raw);
      if (!mounted) return;
      setState(() => detected = version);
      if (widget.allowCancel) Navigator.pop(context);
    } catch (_) {
      if (mounted) setState(() => error = t.t('serverUnreachable'));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return Scaffold(
      appBar:
          widget.allowCancel ? AppBar(title: Text(t.t('serverTitle'))) : null,
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 460),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(Icons.dns_outlined, size: 64),
                  const SizedBox(height: 16),
                  Text(t.t('serverTitle'),
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 8),
                  Text(t.t('serverSubtitle'), textAlign: TextAlign.center),
                  const SizedBox(height: 24),
                  TextField(
                    controller: controller,
                    autofocus: true,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.go,
                    onSubmitted: (_) => busy ? null : connect(),
                    textDirection: TextDirection.ltr,
                    decoration: InputDecoration(
                      labelText: t.t('serverAddress'),
                      hintText: 'https://api.example.com',
                      border: const OutlineInputBorder(),
                      prefixIcon: const Icon(Icons.link),
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(t.t('serverHint'),
                      style: Theme.of(context).textTheme.bodySmall),
                  const SizedBox(height: 20),
                  FilledButton.icon(
                    onPressed: busy ? null : connect,
                    icon: busy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.login),
                    label: Text(
                        busy ? t.t('serverChecking') : t.t('serverConnect')),
                  ),
                  if (widget.allowCancel) ...[
                    const SizedBox(height: 8),
                    TextButton(
                      onPressed: busy ? null : () => Navigator.pop(context),
                      child: Text(t.t('cancel')),
                    ),
                  ],
                  if (detected != null) ...[
                    const SizedBox(height: 16),
                    Text(
                        '${t.t('serverConnected')} — ${t.t('currentVersion')} $detected',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.primary)),
                  ],
                  if (error != null) ...[
                    const SizedBox(height: 16),
                    Text(error!,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.error)),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
