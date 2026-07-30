import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/providers.dart';
import '../../../shared/widgets/states.dart';
import '../domain/legal_source.dart';

/// Corpus statistics, used to tell an empty deployment from an empty result.
final corpusStatsProvider =
    FutureProvider<({int sources, int chunks, bool isEmpty})>(
  (ref) => ref.watch(searchRepositoryProvider).stats(),
);

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key});

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  final _controller = TextEditingController();
  Timer? _debounce;

  SearchFilters _filters = const SearchFilters();
  LegalSearchResult? _result;
  bool _isSearching = false;
  Object? _error;

  @override
  void dispose() {
    _debounce?.cancel();
    _controller.dispose();
    super.dispose();
  }

  void _onQueryChanged(String value) {
    // Debounced so typing a statute name does not fire a request per keystroke.
    _debounce?.cancel();
    if (value.trim().length < 2 && _filters.isEmpty) {
      setState(() => _result = null);
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 450), _search);
  }

  Future<void> _search() async {
    final query = _controller.text.trim();
    if (query.isEmpty && _filters.isEmpty) return;

    setState(() {
      _isSearching = true;
      _error = null;
    });
    try {
      final result = await ref.read(searchRepositoryProvider).search(
            query: query.isEmpty ? null : query,
            filters: _filters,
          );
      if (mounted) setState(() => _result = result);
    } on ApiException catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _isSearching = false);
    }
  }

  Future<void> _openFilters() async {
    final updated = await showModalBottomSheet<SearchFilters>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _FilterSheet(initial: _filters),
    );
    if (updated != null) {
      setState(() => _filters = updated);
      await _search();
    }
  }

  @override
  Widget build(BuildContext context) {
    final stats = ref.watch(corpusStatsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('חיפוש משפטי'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(72),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _controller,
                    onChanged: _onQueryChanged,
                    onSubmitted: (_) => _search(),
                    textInputAction: TextInputAction.search,
                    decoration: InputDecoration(
                      hintText: 'חוק, פסק דין, או שאלה',
                      prefixIcon: const Icon(Icons.search),
                      isDense: true,
                      suffixIcon: _controller.text.isEmpty
                          ? null
                          : IconButton(
                              icon: const Icon(Icons.close),
                              onPressed: () {
                                _controller.clear();
                                setState(() => _result = null);
                              },
                            ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Badge(
                  isLabelVisible: _filters.activeCount > 0,
                  label: Text('${_filters.activeCount}'),
                  child: IconButton.filledTonal(
                    onPressed: _openFilters,
                    icon: const Icon(Icons.tune),
                    tooltip: 'סינון',
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
      body: stats.when(
        loading: () => const LoadingState(),
        error: (error, _) => ErrorState(
          error: error,
          onRetry: () => ref.invalidate(corpusStatsProvider),
        ),
        data: (corpus) {
          if (corpus.isEmpty) return const _EmptyCorpusNotice();
          if (_error != null) return ErrorState(error: _error!, onRetry: _search);
          if (_isSearching && _result == null) return const LoadingState();
          if (_result == null) return _SearchIntro(corpus: corpus);
          return _SearchResults(result: _result!, isRefreshing: _isSearching);
        },
      ),
    );
  }
}

/// Shown when the deployment has no legal corpus loaded at all.
///
/// This is deliberately explicit: without it, an empty result would read as
/// "the law says nothing about this", which would be actively misleading.
class _EmptyCorpusNotice extends StatelessWidget {
  const _EmptyCorpusNotice();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.library_books_outlined,
              size: 56,
              color: theme.colorScheme.onSurfaceVariant,
            ),
            const SizedBox(height: 20),
            Text('מאגר המקורות ריק', style: theme.textTheme.titleMedium),
            const SizedBox(height: 10),
            Text(
              'לא נטענו חקיקה ופסיקה לשרת זה. עד לטעינתם, המערכת תענה '
              'ברמה עקרונית בלבד ולא תציג אסמכתאות — ולא תמציא אותן.',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodyMedium?.copyWith(height: 1.6),
            ),
          ],
        ),
      ),
    );
  }
}

class _SearchIntro extends StatelessWidget {
  const _SearchIntro({required this.corpus});

  final ({int sources, int chunks, bool isEmpty}) corpus;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return ListView(
      padding: const EdgeInsets.all(24),
      children: [
        const SizedBox(height: 24),
        Icon(
          Icons.gavel_outlined,
          size: 48,
          color: theme.colorScheme.primary,
        ),
        const SizedBox(height: 16),
        Text(
          'חיפוש בחקיקה ובפסיקה',
          style: theme.textTheme.titleMedium,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 8),
        Text(
          'במאגר ${NumberFormat.decimalPattern('he').format(corpus.sources)} '
          'מקורות',
          style: theme.textTheme.bodySmall,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: 28),
        Text('אפשר לחפש לפי:', style: theme.textTheme.labelLarge),
        const SizedBox(height: 10),
        for (final hint in const [
          'שם חוק — למשל "חוק החוזים"',
          'מספר הליך — למשל "ע״א 1234/56"',
          'נושא — למשל "פיצוי מוסכם בשכירות"',
          'שאלה חופשית בשפה יומיומית',
        ])
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Row(
              children: [
                Icon(
                  Icons.chevron_left,
                  size: 16,
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(hint, style: theme.textTheme.bodyMedium),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _SearchResults extends StatelessWidget {
  const _SearchResults({required this.result, required this.isRefreshing});

  final LegalSearchResult result;
  final bool isRefreshing;

  @override
  Widget build(BuildContext context) {
    if (result.isEmpty) {
      return const EmptyState(
        icon: Icons.search_off_outlined,
        title: 'לא נמצאו תוצאות',
        message: 'נסה ניסוח אחר, או הסר חלק מהסינונים',
      );
    }

    return Column(
      children: [
        if (isRefreshing) const LinearProgressIndicator(minHeight: 2),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
            children: [
              if (result.passages.isNotEmpty) ...[
                Text(
                  'קטעים רלוונטיים',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 10),
                for (final passage in result.passages)
                  _PassageCard(passage: passage),
                const SizedBox(height: 20),
              ],
              if (result.sources.isNotEmpty) ...[
                Text(
                  'מקורות (${result.total})',
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 10),
                for (final source in result.sources)
                  _SourceTile(source: source),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _PassageCard extends StatelessWidget {
  const _PassageCard({required this.passage});

  final LegalPassage passage;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    passage.source.title,
                    style: theme.textTheme.titleSmall,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 7,
                    vertical: 2,
                  ),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.primaryContainer,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    LegalTaxonomy.sourceTypes[passage.source.sourceType] ??
                        'מקור',
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onPrimaryContainer,
                    ),
                  ),
                ),
              ],
            ),
            if (passage.heading != null && passage.heading!.isNotEmpty) ...[
              const SizedBox(height: 3),
              Text(passage.heading!, style: theme.textTheme.bodySmall),
            ],
            const SizedBox(height: 8),
            Text(
              passage.snippet,
              style: theme.textTheme.bodyMedium?.copyWith(height: 1.6),
            ),
          ],
        ),
      ),
    );
  }
}

class _SourceTile extends StatelessWidget {
  const _SourceTile({required this.source});

  final LegalSource source;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final subtitleParts = <String>[
      if (source.caseNumber != null) source.caseNumber!,
      if (source.court != null) LegalTaxonomy.courts[source.court] ?? '',
      if (source.publishedAt != null) '${source.publishedAt!.year}',
      if (source.publisher.isNotEmpty) source.publisher,
    ].where((s) => s.isNotEmpty).toList();

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        title: Text(
          source.title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.titleSmall,
        ),
        subtitle: subtitleParts.isEmpty
            ? null
            : Text(subtitleParts.join(' · '), maxLines: 1),
        trailing: Text(
          '${source.chunkCount}',
          style: theme.textTheme.labelSmall,
        ),
      ),
    );
  }
}

/// Bottom sheet for source-type, court, domain and date filters.
class _FilterSheet extends StatefulWidget {
  const _FilterSheet({required this.initial});

  final SearchFilters initial;

  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  late List<String> _sourceTypes = [...widget.initial.sourceTypes];
  late List<String> _courts = [...widget.initial.courts];
  late List<String> _domains = [...widget.initial.domains];
  late DateTime? _from = widget.initial.dateFrom;
  late DateTime? _to = widget.initial.dateTo;

  void _toggle(List<String> target, String value) => setState(() {
        if (target.contains(value)) {
          target.remove(value);
        } else {
          target.add(value);
        }
      });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.75,
      maxChildSize: 0.92,
      builder: (context, controller) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 12, 8),
            child: Row(
              children: [
                Text('סינון', style: theme.textTheme.titleMedium),
                const Spacer(),
                TextButton(
                  onPressed: () => setState(() {
                    _sourceTypes = [];
                    _courts = [];
                    _domains = [];
                    _from = null;
                    _to = null;
                  }),
                  child: const Text('ניקוי'),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: ListView(
              controller: controller,
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
              children: [
                _FilterGroup(
                  title: 'סוג מקור',
                  options: LegalTaxonomy.sourceTypes,
                  selected: _sourceTypes,
                  onToggle: (key) => _toggle(_sourceTypes, key),
                ),
                const SizedBox(height: 20),
                _FilterGroup(
                  title: 'ערכאה',
                  options: LegalTaxonomy.courts,
                  selected: _courts,
                  onToggle: (key) => _toggle(_courts, key),
                ),
                const SizedBox(height: 20),
                _FilterGroup(
                  title: 'תחום',
                  options: LegalTaxonomy.domains,
                  selected: _domains,
                  onToggle: (key) => _toggle(_domains, key),
                ),
                const SizedBox(height: 20),
                Text('טווח תאריכים', style: theme.textTheme.titleSmall),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: () => _pickDate(isFrom: true),
                        icon: const Icon(Icons.event_outlined, size: 18),
                        label: Text(
                          _from == null
                              ? 'מתאריך'
                              : DateFormat('d/M/yyyy').format(_from!),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: OutlinedButton.icon(
                        onPressed: () => _pickDate(isFrom: false),
                        icon: const Icon(Icons.event_outlined, size: 18),
                        label: Text(
                          _to == null
                              ? 'עד תאריך'
                              : DateFormat('d/M/yyyy').format(_to!),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
              child: SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(
                    SearchFilters(
                      sourceTypes: _sourceTypes,
                      courts: _courts,
                      domains: _domains,
                      dateFrom: _from,
                      dateTo: _to,
                    ),
                  ),
                  child: const Text('החל סינון'),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _pickDate({required bool isFrom}) async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: (isFrom ? _from : _to) ?? now,
      firstDate: DateTime(1948),
      lastDate: now,
      locale: const Locale('he', 'IL'),
    );
    if (picked != null) {
      setState(() {
        if (isFrom) {
          _from = picked;
        } else {
          _to = picked;
        }
      });
    }
  }
}

class _FilterGroup extends StatelessWidget {
  const _FilterGroup({
    required this.title,
    required this.options,
    required this.selected,
    required this.onToggle,
  });

  final String title;
  final Map<String, String> options;
  final List<String> selected;
  final void Function(String key) onToggle;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: 10),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final entry in options.entries)
                FilterChip(
                  label: Text(entry.value),
                  selected: selected.contains(entry.key),
                  onSelected: (_) => onToggle(entry.key),
                ),
            ],
          ),
        ],
      );
}
