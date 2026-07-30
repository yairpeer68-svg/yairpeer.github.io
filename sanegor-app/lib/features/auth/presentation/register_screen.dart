import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/config/app_config.dart';
import '../../../shared/utils/validators.dart';
import '../../../shared/widgets/states.dart';
import 'auth_controller.dart';

class RegisterScreen extends ConsumerStatefulWidget {
  const RegisterScreen({super.key});

  @override
  ConsumerState<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends ConsumerState<RegisterScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _emailController = TextEditingController();
  final _phoneController = TextEditingController();
  final _passwordController = TextEditingController();
  final _confirmController = TextEditingController();

  bool _obscure = true;
  bool _acceptedTerms = false;

  @override
  void dispose() {
    _nameController.dispose();
    _emailController.dispose();
    _phoneController.dispose();
    _passwordController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    if (!_acceptedTerms) {
      showMessage(context, 'יש לאשר את ההבהרה המשפטית', isError: true);
      return;
    }
    FocusScope.of(context).unfocus();

    final success = await ref.read(authControllerProvider.notifier).register(
          email: _emailController.text,
          password: _passwordController.text,
          fullName: _nameController.text,
          phone: _phoneController.text.trim().isEmpty
              ? null
              : _phoneController.text.trim(),
        );
    if (!success && mounted) {
      final error = ref.read(authControllerProvider).error;
      if (error != null) showMessage(context, error, isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final auth = ref.watch(authControllerProvider);
    final serverErrors = auth.fieldErrors;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_forward),
          onPressed: () => context.pop(),
          tooltip: 'חזרה',
        ),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 460),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text('יצירת חשבון', style: theme.textTheme.headlineMedium),
                    const SizedBox(height: 8),
                    Text(
                      'כמה פרטים ומתחילים',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 28),

                    TextFormField(
                      controller: _nameController,
                      textInputAction: TextInputAction.next,
                      textCapitalization: TextCapitalization.words,
                      autofillHints: const [AutofillHints.name],
                      decoration: InputDecoration(
                        labelText: 'שם מלא',
                        prefixIcon: const Icon(Icons.person_outline),
                        errorText: serverErrors['full_name'],
                      ),
                      validator: Validators.fullName,
                    ),
                    const SizedBox(height: 16),

                    TextFormField(
                      controller: _emailController,
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.next,
                      textDirection: TextDirection.ltr,
                      autofillHints: const [AutofillHints.email],
                      decoration: InputDecoration(
                        labelText: 'כתובת דוא״ל',
                        prefixIcon: const Icon(Icons.alternate_email),
                        errorText: serverErrors['email'],
                      ),
                      validator: Validators.email,
                    ),
                    const SizedBox(height: 16),

                    TextFormField(
                      controller: _phoneController,
                      keyboardType: TextInputType.phone,
                      textInputAction: TextInputAction.next,
                      textDirection: TextDirection.ltr,
                      autofillHints: const [AutofillHints.telephoneNumber],
                      decoration: InputDecoration(
                        labelText: 'טלפון (רשות)',
                        prefixIcon: const Icon(Icons.phone_outlined),
                        errorText: serverErrors['phone'],
                      ),
                      validator: Validators.phone,
                    ),
                    const SizedBox(height: 16),

                    TextFormField(
                      controller: _passwordController,
                      obscureText: _obscure,
                      textInputAction: TextInputAction.next,
                      textDirection: TextDirection.ltr,
                      autofillHints: const [AutofillHints.newPassword],
                      onChanged: (_) => setState(() {}),
                      decoration: InputDecoration(
                        labelText: 'סיסמה',
                        prefixIcon: const Icon(Icons.lock_outline),
                        errorText: serverErrors['password'],
                        suffixIcon: IconButton(
                          onPressed: () => setState(() => _obscure = !_obscure),
                          icon: Icon(
                            _obscure
                                ? Icons.visibility_outlined
                                : Icons.visibility_off_outlined,
                          ),
                        ),
                      ),
                      validator: Validators.password,
                    ),
                    const SizedBox(height: 8),
                    _PasswordStrengthBar(password: _passwordController.text),
                    const SizedBox(height: 16),

                    TextFormField(
                      controller: _confirmController,
                      obscureText: _obscure,
                      textInputAction: TextInputAction.done,
                      textDirection: TextDirection.ltr,
                      onFieldSubmitted: (_) => _submit(),
                      decoration: const InputDecoration(
                        labelText: 'אימות סיסמה',
                        prefixIcon: Icon(Icons.lock_reset_outlined),
                      ),
                      validator: Validators.confirmPassword(
                        () => _passwordController.text,
                      ),
                    ),
                    const SizedBox(height: 20),

                    CheckboxListTile(
                      value: _acceptedTerms,
                      onChanged: (value) =>
                          setState(() => _acceptedTerms = value ?? false),
                      controlAffinity: ListTileControlAffinity.leading,
                      contentPadding: EdgeInsets.zero,
                      title: Text(
                        'קראתי והבנתי: ${AppConfig.disclaimer}',
                        style: theme.textTheme.bodySmall?.copyWith(height: 1.5),
                      ),
                    ),
                    const SizedBox(height: 16),

                    FilledButton(
                      onPressed: auth.isSubmitting ? null : _submit,
                      child: auth.isSubmitting
                          ? const SizedBox(
                              width: 22,
                              height: 22,
                              child: CircularProgressIndicator(strokeWidth: 2.4),
                            )
                          : const Text('יצירת חשבון'),
                    ),
                    const SizedBox(height: 16),

                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Text('כבר יש לך חשבון?',
                            style: theme.textTheme.bodyMedium),
                        TextButton(
                          onPressed: () => context.pop(),
                          child: const Text('התחברות'),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Live password-strength feedback.
///
/// Scores the same properties the backend enforces, so a password that looks
/// strong here will not be rejected on submit.
class _PasswordStrengthBar extends StatelessWidget {
  const _PasswordStrengthBar({required this.password});

  final String password;

  ({double value, String label, Color color}) _score(ColorScheme scheme) {
    if (password.isEmpty) {
      return (value: 0, label: '', color: scheme.outlineVariant);
    }
    var points = 0;
    if (password.length >= 10) points++;
    if (password.length >= 14) points++;
    if (password.contains(RegExp(r'\d'))) points++;
    if (password.contains(RegExp('[A-Za-zא-ת]'))) points++;
    if (password.contains(RegExp(r'[^\w\s]'))) points++;

    return switch (points) {
      <= 2 => (value: 0.33, label: 'חלשה', color: scheme.error),
      3 || 4 => (value: 0.66, label: 'בינונית', color: Colors.orange),
      _ => (value: 1, label: 'חזקה', color: Colors.green.shade600),
    };
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final result = _score(theme.colorScheme);
    if (password.isEmpty) return const SizedBox.shrink();

    return Row(
      children: [
        Expanded(
          child: ClipRRect(
            borderRadius: BorderRadius.circular(4),
            child: LinearProgressIndicator(
              value: result.value,
              minHeight: 5,
              color: result.color,
              backgroundColor: theme.colorScheme.surfaceContainerHighest,
            ),
          ),
        ),
        const SizedBox(width: 10),
        Text(
          result.label,
          style: theme.textTheme.labelSmall?.copyWith(color: result.color),
        ),
      ],
    );
  }
}
