import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'app_state.dart';
import 'core/api_client.dart';
import 'core/config.dart';
import 'core/i18n.dart';
import 'core/ids.dart';
import 'core/storage.dart';
import 'screens/auth.dart';
import 'screens/server_setup.dart';
import 'screens/shell.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  AppConfig.validate();
  const secure = FlutterSecureStorage(
      aOptions: AndroidOptions(encryptedSharedPreferences: true));
  final store = SecureTokenStore(secure);
  final ids = InstallationId(store);
  final api = ApiClient(store, ids);
  runApp(AiPlatformApp(state: AppState(api, store)));
}

class AiPlatformApp extends StatefulWidget {
  final AppState state;
  const AiPlatformApp({super.key, required this.state});
  @override
  State<AiPlatformApp> createState() => _AiPlatformAppState();
}

class _AiPlatformAppState extends State<AiPlatformApp> {
  @override
  void initState() {
    super.initState();
    widget.state.addListener(_changed);
    widget.state.initialize();
  }

  @override
  void dispose() {
    widget.state.removeListener(_changed);
    super.dispose();
  }

  void _changed() => setState(() {});
  @override
  Widget build(BuildContext context) {
    final s = widget.state;
    return MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'AI Platform',
        themeMode: s.darkMode ? ThemeMode.dark : ThemeMode.light,
        theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
        darkTheme: ThemeData(
            useMaterial3: true,
            colorSchemeSeed: Colors.indigo,
            brightness: Brightness.dark),
        locale: Locale(s.language),
        supportedLocales: Strings.supported,
        localizationsDelegates: const [
          GlobalWidgetsLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate
        ],
        builder: (context, child) => Directionality(
            textDirection:
                s.language == 'he' ? TextDirection.rtl : TextDirection.ltr,
            child: child!),
        home: !s.ready
            ? const Scaffold(body: Center(child: CircularProgressIndicator()))
            : !s.serverConfigured
                ? ServerSetupScreen(state: s)
                : s.authenticated
                    ? Shell(state: s)
                    : AuthScreen(state: s));
  }
}
