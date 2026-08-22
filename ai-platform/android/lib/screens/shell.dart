import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';
import 'about.dart';
import 'chat.dart';
import 'devices.dart';
import 'history.dart';
import 'home.dart';
import 'notifications.dart';
import 'profile.dart';
import 'security.dart';
import 'settings.dart';
import 'version_gate.dart';
import 'projects.dart';

class Shell extends StatefulWidget {
  final AppState state;
  const Shell({super.key, required this.state});
  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int index = 0;
  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    final entries = [
      (t.t('home'), Icons.home_outlined, HomeScreen(state: widget.state)),
      (t.t('chat'), Icons.chat_bubble_outline, ChatScreen(state: widget.state)),
      (
        t.t('projects'),
        Icons.account_tree_outlined,
        ProjectsScreen(state: widget.state)
      ),
      (t.t('history'), Icons.history, HistoryScreen(state: widget.state)),
      (t.t('devices'), Icons.devices, DevicesScreen(state: widget.state)),
      (
        t.t('profile'),
        Icons.person_outline,
        ProfileScreen(state: widget.state)
      ),
      (
        t.t('settings'),
        Icons.settings_outlined,
        SettingsScreen(state: widget.state)
      ),
      (t.t('security'), Icons.security, SecurityScreen(state: widget.state)),
      (
        t.t('notifications'),
        Icons.notifications_none,
        NotificationsScreen(state: widget.state)
      ),
      (t.t('about'), Icons.info_outline, const AboutScreen())
    ];
    final selected = entries[index];
    return VersionGate(
        state: widget.state,
        child: Scaffold(
            appBar: AppBar(title: Text(selected.$1)),
            drawer: Drawer(
                child: SafeArea(
                    child: Column(children: [
              DrawerHeader(
                  child: Center(
                      child: Text(t.t('app'),
                          style: Theme.of(context).textTheme.headlineSmall))),
              Expanded(
                  child: ListView.builder(
                      itemCount: entries.length,
                      itemBuilder: (context, i) => ListTile(
                          leading: Icon(entries[i].$2),
                          title: Text(entries[i].$1),
                          selected: index == i,
                          onTap: () {
                            setState(() => index = i);
                            Navigator.pop(context);
                          }))),
              ListTile(
                  leading: const Icon(Icons.logout),
                  title: Text(t.t('logout')),
                  onTap: widget.state.logout)
            ]))),
            body: selected.$3));
  }
}
