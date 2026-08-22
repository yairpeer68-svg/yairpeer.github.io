import 'dart:async';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import '../app_state.dart';
import '../core/i18n.dart';

class ProjectsScreen extends StatefulWidget {
  final AppState state;
  const ProjectsScreen({super.key, required this.state});
  @override
  State<ProjectsScreen> createState() => _ProjectsScreenState();
}

class _ProjectsScreenState extends State<ProjectsScreen> {
  List<dynamic> projects = [];
  bool loading = true;
  String? error;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    if (mounted) setState(() => loading = true);
    try {
      final r = await widget.state.api.dio.get('/engineering/projects');
      projects = List<dynamic>.from(r.data as List);
      error = null;
    } catch (e) {
      error = widget.state.api.mapError(e).message;
    }
    if (mounted) setState(() => loading = false);
  }

  Future<void> create() async {
    final name = TextEditingController();
    final goal = TextEditingController();
    final ok = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
              title: Text(Strings.of(context).t('newProject')),
              content: Column(mainAxisSize: MainAxisSize.min, children: [
                TextField(
                    controller: name,
                    decoration: InputDecoration(
                        labelText: Strings.of(context).t('projectName'))),
                TextField(
                    controller: goal,
                    maxLines: 4,
                    decoration: InputDecoration(
                        labelText: Strings.of(context).t('buildPrompt'))),
              ]),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(c, false),
                    child: Text(Strings.of(context).t('cancel'))),
                FilledButton(
                    onPressed: () => Navigator.pop(c, true),
                    child: Text(Strings.of(context).t('create')))
              ],
            ));
    if (ok != true || name.text.trim().isEmpty) return;
    try {
      final p = await widget.state.api.dio.post('/engineering/projects',
          data: {'name': name.text.trim(), 'project_type': 'auto'});
      final id = (p.data as Map)['id'];
      final r = await widget.state.api.dio
          .post('/engineering/projects/$id/runs', data: {
        'goal': goal.text.trim().isEmpty
            ? 'Analyze the repository and propose the next highest-value improvements.'
            : goal.text.trim()
      });
      await widget.state.api.dio
          .post("/engineering/runs/${(r.data as Map)['id']}/start");
      await load();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(Strings.of(context).t('runStarted'))));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(widget.state.api.mapError(e).message)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (loading) return const Center(child: CircularProgressIndicator());
    return RefreshIndicator(
        onRefresh: load,
        child: ListView(padding: const EdgeInsets.all(16), children: [
          Row(children: [
            Expanded(
                child: Text('Autonomous Projects',
                    style: Theme.of(context).textTheme.headlineSmall)),
            FilledButton.icon(
                onPressed: create,
                icon: const Icon(Icons.add),
                label: Text(Strings.of(context).t('create')))
          ]),
          const SizedBox(height: 12),
          if (error != null)
            Text(error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ...projects.map((p) => Card(
                  child: ListTile(
                leading: const Icon(Icons.account_tree_outlined),
                title: Text(p['name']?.toString() ?? ''),
                subtitle: Text("${p['project_type']} • ${p['status']}"),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (_) => ProjectRunsScreen(
                            state: widget.state, project: p))),
              ))),
        ]));
  }
}

class ProjectRunsScreen extends StatefulWidget {
  final AppState state;
  final dynamic project;
  const ProjectRunsScreen(
      {super.key, required this.state, required this.project});
  @override
  State<ProjectRunsScreen> createState() => _ProjectRunsScreenState();
}

class _ProjectRunsScreenState extends State<ProjectRunsScreen> {
  List<dynamic> runs = [];
  Timer? timer;
  String? error;
  bool importing = false;
  @override
  void initState() {
    super.initState();
    load();
    timer =
        Timer.periodic(const Duration(seconds: 4), (_) => load(silent: true));
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> load({bool silent = false}) async {
    try {
      final r = await widget.state.api.dio
          .get("/engineering/projects/${widget.project['id']}/runs");
      if (mounted) {
        setState(() {
          runs = List<dynamic>.from(r.data as List);
          error = null;
        });
      }
    } catch (e) {
      if (!silent && mounted) {
        setState(() => error = widget.state.api.mapError(e).message);
      }
    }
  }

  Future<void> newRun() async {
    final goal = TextEditingController();
    final ok = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
              title: Text(Strings.of(context).t('newRun')),
              content: TextField(
                  controller: goal,
                  maxLines: 6,
                  autofocus: true,
                  decoration: InputDecoration(
                      labelText: Strings.of(context).t('buildPrompt'),
                      border: const OutlineInputBorder())),
              actions: [
                TextButton(
                    onPressed: () => Navigator.pop(c, false),
                    child: Text(Strings.of(context).t('cancel'))),
                FilledButton(
                    onPressed: () => Navigator.pop(c, true),
                    child: Text(Strings.of(context).t('startRun')))
              ],
            ));
    if (ok != true || goal.text.trim().length < 3) return;
    try {
      final r = await widget.state.api.dio.post(
          '/engineering/projects/${widget.project['id']}/runs',
          data: {'goal': goal.text.trim()});
      final id = (r.data as Map)['id'].toString();
      await widget.state.api.dio.post('/engineering/runs/$id/start');
      await load();
      if (mounted) {
        Navigator.push(
            context,
            MaterialPageRoute(
                builder: (_) =>
                    RunDetailScreen(state: widget.state, runId: id)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(widget.state.api.mapError(e).message)));
      }
    }
  }

  /// Import a source ZIP into this project.
  ///
  /// Without this the client could create projects and start runs, but had no way to
  /// put any code in the workspace, so every run operated on an empty tree.
  Future<void> importArchive() async {
    final t = Strings.of(context);
    final picked = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['zip'],
      withData: false,
    );
    final path = picked?.files.single.path;
    if (path == null) return;
    setState(() => importing = true);
    try {
      final result = await widget.state.api.uploadFile(
        "/engineering/projects/${widget.project['id']}/archive",
        path,
        picked!.files.single.name,
      );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text('${t.t('importDone')}: ${result['files']} files')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(widget.state.api.mapError(e).message)));
      }
    } finally {
      if (mounted) setState(() => importing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final t = Strings.of(context);
    return Scaffold(
      appBar: AppBar(
          title: Text(widget.project['name']?.toString() ?? 'Project'),
          actions: [
            IconButton(
              tooltip: t.t('importArchive'),
              icon: importing
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.drive_folder_upload),
              onPressed: importing ? null : importArchive,
            ),
            IconButton(
                tooltip: t.t('codeSearch'),
                icon: const Icon(Icons.manage_search),
                onPressed: () => Navigator.push(
                    context,
                    MaterialPageRoute(
                        builder: (_) => CodeSearchScreen(
                            state: widget.state,
                            projectId: widget.project['id'].toString())))),
            IconButton(
                tooltip: t.t('retry'),
                icon: const Icon(Icons.refresh),
                onPressed: load),
          ]),
      floatingActionButton: FloatingActionButton.extended(
          onPressed: newRun,
          icon: const Icon(Icons.play_arrow),
          label: Text(Strings.of(context).t('startRun'))),
      body: RefreshIndicator(
          onRefresh: load,
          child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 96),
              children: [
                if (error != null)
                  Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(error!,
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.error))),
                if (runs.isEmpty)
                  Card(
                      child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Text(Strings.of(context).t('noRuns')))),
                ...runs.map((r) => Card(
                        child: ListTile(
                      title: Text(r['goal']?.toString() ?? ''),
                      subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text("${r['status']} • ${r['stage']}"),
                            const SizedBox(height: 6),
                            LinearProgressIndicator(
                                value: ((r['progress'] ?? 0) as num) / 100)
                          ]),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => Navigator.push(
                          context,
                          MaterialPageRoute(
                              builder: (_) => RunDetailScreen(
                                  state: widget.state,
                                  runId: r['id'].toString()))),
                    ))),
              ])),
    );
  }
}

class CodeSearchScreen extends StatefulWidget {
  final AppState state;
  final String projectId;
  const CodeSearchScreen(
      {super.key, required this.state, required this.projectId});
  @override
  State<CodeSearchScreen> createState() => _CodeSearchScreenState();
}

class _CodeSearchScreenState extends State<CodeSearchScreen> {
  final query = TextEditingController();
  List<dynamic> hits = [];
  bool loading = false;
  String? error;
  Future<void> search() async {
    if (query.text.trim().length < 2) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final r = await widget.state.api.dio.get(
          '/engineering/projects/${widget.projectId}/code-search',
          queryParameters: {'q': query.text.trim(), 'limit': 20});
      if (mounted) setState(() => hits = List<dynamic>.from(r.data as List));
    } catch (e) {
      if (mounted) setState(() => error = widget.state.api.mapError(e).message);
    }
    if (mounted) setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: Text(Strings.of(context).t('smartCodeSearch'))),
        body: ListView(padding: const EdgeInsets.all(16), children: [
          TextField(
              controller: query,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => search(),
              decoration: InputDecoration(
                  labelText: Strings.of(context).t('searchHint'),
                  suffixIcon: IconButton(
                      onPressed: search, icon: const Icon(Icons.search)),
                  border: const OutlineInputBorder())),
          const SizedBox(height: 12),
          if (loading) const LinearProgressIndicator(),
          if (error != null)
            Text(error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ...hits.map((h) => Card(
                  child: ExpansionTile(
                title: Text(h['path']?.toString() ?? ''),
                subtitle: Text("${h['language']} • score ${h['score']}"),
                children: [
                  Padding(
                      padding: const EdgeInsets.all(12),
                      child: SelectableText(h['excerpt']?.toString() ?? '',
                          style: const TextStyle(fontFamily: 'monospace')))
                ],
              ))),
        ]),
      );
}

class RunDetailScreen extends StatefulWidget {
  final AppState state;
  final String runId;
  const RunDetailScreen({super.key, required this.state, required this.runId});
  @override
  State<RunDetailScreen> createState() => _RunDetailScreenState();
}

class _RunDetailScreenState extends State<RunDetailScreen> {
  Map<String, dynamic>? run;
  List<dynamic> events = [];
  List<dynamic> tasks = [];
  List<dynamic> approvals = [];
  Timer? timer;
  @override
  void initState() {
    super.initState();
    load();
    timer = Timer.periodic(const Duration(seconds: 3), (_) => load());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> load() async {
    try {
      final rs = await Future.wait([
        widget.state.api.dio.get('/engineering/runs/${widget.runId}'),
        widget.state.api.dio
            .get('/engineering/runs/${widget.runId}/events?limit=100'),
        widget.state.api.dio.get('/engineering/runs/${widget.runId}/tasks'),
        widget.state.api.dio.get('/engineering/runs/${widget.runId}/approvals'),
      ]);
      if (mounted) {
        setState(() {
          run = Map<String, dynamic>.from(rs[0].data as Map);
          events = List<dynamic>.from(rs[1].data as List);
          tasks = List<dynamic>.from(rs[2].data as List);
          approvals = List<dynamic>.from(rs[3].data as List);
        });
      }
    } catch (_) {}
  }

  Future<void> decide(dynamic a, String decision) async {
    try {
      await widget.state.api.dio.post(
          "/engineering/approvals/${a['id']}/decision",
          data: {'decision': decision});
      await load();
      if (mounted) {
        final t = Strings.of(context);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content:
                  Text(t.t(decision == 'approved' ? 'approved' : 'rejected'))),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(widget.state.api.mapError(e).message)));
      }
    }
  }

  Future<void> showDiff() async {
    try {
      final r = await widget.state.api.dio
          .get('/engineering/runs/${widget.runId}/diff');
      final data = Map<String, dynamic>.from(r.data as Map);
      if (!mounted) return;
      Navigator.push(
          context, MaterialPageRoute(builder: (_) => DiffScreen(data: data)));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(widget.state.api.mapError(e).message)));
      }
    }
  }

  Future<void> cancel() async {
    try {
      await widget.state.api.dio
          .post('/engineering/runs/${widget.runId}/cancel');
      await load();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(widget.state.api.mapError(e).message)));
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Agent Run'), actions: [
          if (run?['status'] == 'completed')
            IconButton(
                tooltip: 'Diff',
                icon: const Icon(Icons.difference_outlined),
                onPressed: showDiff),
          if (run != null &&
              !['completed', 'failed', 'cancelled'].contains(run!['status']))
            IconButton(
                tooltip: Strings.of(context).t('cancelRun'),
                icon: const Icon(Icons.stop_circle_outlined),
                onPressed: cancel),
        ]),
        body: run == null
            ? const Center(child: CircularProgressIndicator())
            : ListView(padding: const EdgeInsets.all(16), children: [
                Text(run!['goal']?.toString() ?? '',
                    style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                LinearProgressIndicator(
                    value: ((run!['progress'] ?? 0) as num) / 100),
                Text(
                    "${run!['status']} • ${run!['stage']} • ${run!['progress']}%"),
                if (run!['stage'] == 'verification_parallel')
                  Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child:
                          Text(Strings.of(context).t('parallelVerification'))),
                if (run!['error'] != null)
                  Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(run!['error'].toString(),
                          style: TextStyle(
                              color: Theme.of(context).colorScheme.error))),
                if (approvals.any((a) => a['status'] == 'pending')) ...[
                  const Divider(),
                  Text(Strings.of(context).t('approvalsRequired'),
                      style: Theme.of(context).textTheme.titleLarge),
                  ...approvals.where((a) => a['status'] == 'pending').map((a) =>
                      Card(
                          child: Padding(
                              padding: const EdgeInsets.all(12),
                              child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(a['reason']?.toString() ?? ''),
                                    const SizedBox(height: 8),
                                    Row(children: [
                                      FilledButton(
                                          onPressed: () =>
                                              decide(a, 'approved'),
                                          child: Text(Strings.of(context)
                                              .t('approve'))),
                                      const SizedBox(width: 8),
                                      OutlinedButton(
                                          onPressed: () =>
                                              decide(a, 'rejected'),
                                          child: Text(
                                              Strings.of(context).t('reject')))
                                    ]),
                                  ])))),
                ],
                const Divider(),
                Text('Tasks', style: Theme.of(context).textTheme.titleLarge),
                ...tasks.map((t) => ListTile(
                    dense: true,
                    leading: Icon(t['status'] == 'completed'
                        ? Icons.check_circle_outline
                        : t['status'] == 'waiting_approval'
                            ? Icons.approval_outlined
                            : Icons.pending_outlined),
                    title: Text(t['title']?.toString() ?? ''),
                    subtitle: Text("${t['role']} • ${t['status']}"))),
                const Divider(),
                Text('Live events',
                    style: Theme.of(context).textTheme.titleLarge),
                ...events.reversed.take(40).map((e) => ListTile(
                    dense: true,
                    title: Text(e['message']?.toString() ?? ''),
                    subtitle: Text("${e['event_type']} • ${e['level']}"))),
              ]),
      );
}

class DiffScreen extends StatelessWidget {
  final Map<String, dynamic> data;
  const DiffScreen({super.key, required this.data});
  @override
  Widget build(BuildContext context) {
    final files = List<dynamic>.from(data['files'] as List? ?? const []);
    return Scaffold(
        appBar: AppBar(title: Text('Diff • ${files.length} files')),
        body: ListView(padding: const EdgeInsets.all(16), children: [
          if (data['truncated'] == true)
            Card(
                child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(Strings.of(context).t('diffTruncated')))),
          if (files.isNotEmpty)
            Wrap(
                spacing: 6,
                runSpacing: 6,
                children: files
                    .take(80)
                    .map((f) => Chip(label: Text(f.toString())))
                    .toList()),
          const SizedBox(height: 12),
          SelectableText(data['diff']?.toString() ?? '',
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
        ]));
  }
}
