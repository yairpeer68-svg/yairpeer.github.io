import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/presentation/auth_controller.dart';
import '../../features/auth/presentation/forgot_password_screen.dart';
import '../../features/auth/presentation/login_screen.dart';
import '../../features/auth/presentation/register_screen.dart';
import '../../features/chat/presentation/chat_screen.dart';
import '../../features/documents/presentation/analysis_screen.dart';
import '../../features/documents/presentation/documents_screen.dart';
import '../../features/drafting/presentation/generated_document_screen.dart';
import '../../features/drafting/presentation/template_form_screen.dart';
import '../../features/drafting/presentation/templates_screen.dart';
import '../../features/history/presentation/history_screen.dart';
import '../../features/home/presentation/home_shell.dart';
import '../../features/profile/presentation/profile_screen.dart';
import '../../features/profile/presentation/settings_screen.dart';
import '../../features/search/presentation/search_screen.dart';
import '../../features/splash/presentation/splash_screen.dart';

/// Route names, referenced by `context.goNamed` rather than raw paths.
abstract final class Routes {
  static const splash = 'splash';
  static const login = 'login';
  static const register = 'register';
  static const forgotPassword = 'forgot-password';
  static const chat = 'chat';
  static const documents = 'documents';
  static const analysis = 'analysis';
  static const contracts = 'contracts';
  static const letters = 'letters';
  static const templateForm = 'template-form';
  static const generated = 'generated';
  static const search = 'search';
  static const history = 'history';
  static const profile = 'profile';
  static const settings = 'settings';
}

/// Bridges a Riverpod provider to `GoRouter.refreshListenable`.
class _AuthRefresh extends ChangeNotifier {
  _AuthRefresh(Ref ref) {
    ref.listen(
      authControllerProvider.select((state) => state.status),
      (_, __) => notifyListeners(),
    );
  }
}

final routerProvider = Provider<GoRouter>((ref) {
  final refresh = _AuthRefresh(ref);
  ref.onDispose(refresh.dispose);

  return GoRouter(
    initialLocation: '/',
    refreshListenable: refresh,
    debugLogDiagnostics: false,
    redirect: (context, state) {
      final auth = ref.read(authControllerProvider);
      final location = state.matchedLocation;
      const publicRoutes = {'/login', '/register', '/forgot-password'};

      // Hold on the splash screen until the session has been resolved, so the
      // user never sees a login form flash before an existing session loads.
      if (!auth.isResolved) return location == '/' ? null : '/';

      if (!auth.isAuthenticated) {
        return publicRoutes.contains(location) ? null : '/login';
      }
      if (location == '/' || publicRoutes.contains(location)) return '/chat';
      return null;
    },
    routes: [
      GoRoute(
        path: '/',
        name: Routes.splash,
        builder: (context, state) => const SplashScreen(),
      ),
      GoRoute(
        path: '/login',
        name: Routes.login,
        builder: (context, state) => const LoginScreen(),
      ),
      GoRoute(
        path: '/register',
        name: Routes.register,
        builder: (context, state) => const RegisterScreen(),
      ),
      GoRoute(
        path: '/forgot-password',
        name: Routes.forgotPassword,
        builder: (context, state) => const ForgotPasswordScreen(),
      ),

      // The five primary destinations live inside a persistent shell so the
      // bottom navigation bar does not rebuild between tabs.
      ShellRoute(
        builder: (context, state, child) =>
            HomeShell(location: state.matchedLocation, child: child),
        routes: [
          GoRoute(
            path: '/chat',
            name: Routes.chat,
            builder: (context, state) => ChatScreen(
              conversationId: state.uri.queryParameters['conversation'],
            ),
          ),
          GoRoute(
            path: '/documents',
            name: Routes.documents,
            builder: (context, state) => const DocumentsScreen(),
          ),
          GoRoute(
            path: '/search',
            name: Routes.search,
            builder: (context, state) => const SearchScreen(),
          ),
          GoRoute(
            path: '/history',
            name: Routes.history,
            builder: (context, state) => const HistoryScreen(),
          ),
          GoRoute(
            path: '/profile',
            name: Routes.profile,
            builder: (context, state) => const ProfileScreen(),
          ),
        ],
      ),

      // Full-screen routes, pushed above the shell.
      GoRoute(
        path: '/contracts',
        name: Routes.contracts,
        builder: (context, state) => const TemplatesScreen(category: 'contract'),
      ),
      GoRoute(
        path: '/letters',
        name: Routes.letters,
        builder: (context, state) => const TemplatesScreen(category: 'letter'),
      ),
      GoRoute(
        path: '/templates/:category/:key',
        name: Routes.templateForm,
        builder: (context, state) => TemplateFormScreen(
          category: state.pathParameters['category']!,
          templateKey: state.pathParameters['key']!,
        ),
      ),
      GoRoute(
        path: '/generated',
        name: Routes.generated,
        builder: (context, state) => GeneratedDocumentScreen(
          document: state.extra as GeneratedDocumentArgs?,
        ),
      ),
      GoRoute(
        path: '/documents/:id/analysis',
        name: Routes.analysis,
        builder: (context, state) => AnalysisScreen(
          documentId: state.pathParameters['id']!,
          kind: state.uri.queryParameters['kind'] ?? 'document',
        ),
      ),
      GoRoute(
        path: '/settings',
        name: Routes.settings,
        builder: (context, state) => const SettingsScreen(),
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      appBar: AppBar(title: const Text('שגיאה')),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.explore_off_outlined, size: 48),
            const SizedBox(height: 16),
            const Text('הדף המבוקש לא נמצא'),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => context.goNamed(Routes.chat),
              child: const Text('חזרה לצ׳אט'),
            ),
          ],
        ),
      ),
    ),
  );
});
