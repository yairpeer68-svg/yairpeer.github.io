import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class AuthScreen extends StatefulWidget {
  final AppState state;
  const AuthScreen({super.key, required this.state});
  @override
  State<AuthScreen> createState() => _AuthScreenState();
}

class _AuthScreenState extends State<AuthScreen> {
  bool register = false, loading = false;
  String error = '';
  final email = TextEditingController(),
      password = TextEditingController(),
      name = TextEditingController();
  Future<void> submit() async {
    setState(() {
      loading = true;
      error = '';
    });
    try {
      if (register) {
        await widget.state.register(email.text.trim(), password.text,
            name.text.trim().isEmpty ? null : name.text.trim());
      } else {
        await widget.state.login(email.text.trim(), password.text);
      }
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  void dispose() {
    email.dispose();
    password.dispose();
    name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return Scaffold(
        body: SafeArea(
            child: Center(
                child: SingleChildScrollView(
                    padding: const EdgeInsets.all(24),
                    child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 480),
                        child: Card(
                            child: Padding(
                                padding: const EdgeInsets.all(24),
                                child: Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Icon(Icons.smart_toy_outlined,
                                          size: 64,
                                          color: Theme.of(context)
                                              .colorScheme
                                              .primary),
                                      const SizedBox(height: 12),
                                      Text(t.t('app'),
                                          style: Theme.of(context)
                                              .textTheme
                                              .headlineMedium),
                                      const SizedBox(height: 24),
                                      if (register)
                                        TextField(
                                            controller: name,
                                            textInputAction:
                                                TextInputAction.next,
                                            decoration: InputDecoration(
                                                labelText: t.t('displayName'),
                                                border:
                                                    const OutlineInputBorder())),
                                      if (register) const SizedBox(height: 12),
                                      TextField(
                                          controller: email,
                                          keyboardType:
                                              TextInputType.emailAddress,
                                          textInputAction: TextInputAction.next,
                                          autofillHints: const [
                                            AutofillHints.username
                                          ],
                                          decoration: InputDecoration(
                                              labelText: t.t('email'),
                                              border:
                                                  const OutlineInputBorder())),
                                      const SizedBox(height: 12),
                                      TextField(
                                          controller: password,
                                          obscureText: true,
                                          onSubmitted: (_) => submit(),
                                          autofillHints: const [
                                            AutofillHints.password
                                          ],
                                          decoration: InputDecoration(
                                              labelText: t.t('password'),
                                              border:
                                                  const OutlineInputBorder(),
                                              helperText: register
                                                  ? '10+ characters'
                                                  : null)),
                                      if (error.isNotEmpty)
                                        Padding(
                                            padding:
                                                const EdgeInsets.only(top: 12),
                                            child: Text(error,
                                                style: TextStyle(
                                                    color: Theme.of(context)
                                                        .colorScheme
                                                        .error))),
                                      const SizedBox(height: 20),
                                      FilledButton(
                                          onPressed: loading ? null : submit,
                                          child: loading
                                              ? const SizedBox(
                                                  width: 20,
                                                  height: 20,
                                                  child:
                                                      CircularProgressIndicator(
                                                          strokeWidth: 2))
                                              : Text(t.t(register
                                                  ? 'register'
                                                  : 'login'))),
                                      TextButton(
                                          onPressed: loading
                                              ? null
                                              : () => setState(
                                                  () => register = !register),
                                          child: Text(t.t(register
                                              ? 'login'
                                              : 'newAccount')))
                                    ]))))))));
  }
}
