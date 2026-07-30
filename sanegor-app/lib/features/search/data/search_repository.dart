import '../../../core/network/api_client.dart';
import '../domain/legal_source.dart';

/// Corpus search and statistics.
class SearchRepository {
  const SearchRepository({required ApiClient client}) : _client = client;

  final ApiClient _client;

  Future<LegalSearchResult> search({
    String? query,
    SearchFilters filters = const SearchFilters(),
    bool semantic = true,
    int limit = 20,
    int offset = 0,
  }) async =>
      LegalSearchResult.fromJson(
        await _client.post(
          '/search',
          body: {
            if (query != null && query.trim().isNotEmpty) 'query': query.trim(),
            ...filters.toJson(),
            'semantic': semantic,
            'limit': limit,
            'offset': offset,
          },
        ),
      );

  Future<LegalSource> getSource(String citationKey) async =>
      LegalSource.fromJson(await _client.get('/search/sources/$citationKey'));

  Future<List<({String citationKey, String title, String sourceType})>> suggest(
    String query,
  ) async {
    final response = await _client.getList('/search/suggest', query: {'q': query});
    return response
        .whereType<Map<String, dynamic>>()
        .map(
          (item) => (
            citationKey: (item['citation_key'] ?? '').toString(),
            title: (item['title'] ?? '').toString(),
            sourceType: (item['source_type'] ?? '').toString(),
          ),
        )
        .toList(growable: false);
  }

  /// Corpus composition — used to warn when nothing has been loaded.
  Future<({int sources, int chunks, bool isEmpty})> stats() async {
    final response = await _client.get('/search/stats');
    return (
      sources: (response['sources_total'] as num?)?.toInt() ?? 0,
      chunks: (response['chunks_total'] as num?)?.toInt() ?? 0,
      isEmpty: response['corpus_empty'] == true,
    );
  }
}
