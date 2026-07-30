import '../../../core/network/api_client.dart';
import '../domain/template.dart';

/// Contract and letter drafting.
class DraftingRepository {
  const DraftingRepository({required ApiClient client}) : _client = client;

  final ApiClient _client;

  Future<List<LegalTemplate>> contractTemplates() =>
      _templates('/contracts/templates');

  Future<List<LegalTemplate>> letterTemplates() =>
      _templates('/letters/templates');

  Future<List<LegalTemplate>> _templates(String path) async {
    final response = await _client.getList(path);
    return response
        .whereType<Map<String, dynamic>>()
        .map(LegalTemplate.fromJson)
        .toList(growable: false);
  }

  Future<GeneratedDocument> generateContract({
    required String templateKey,
    required Map<String, dynamic> inputs,
  }) =>
      _generate('/contracts/generate', templateKey, inputs);

  Future<GeneratedDocument> generateLetter({
    required String templateKey,
    required Map<String, dynamic> inputs,
  }) =>
      _generate('/letters/generate', templateKey, inputs);

  Future<GeneratedDocument> _generate(
    String path,
    String templateKey,
    Map<String, dynamic> inputs,
  ) async =>
      GeneratedDocument.fromJson(
        await _client.post(
          path,
          // Blank values are dropped so the backend reports them as missing
          // rather than drafting around an empty string.
          body: {
            'template_key': templateKey,
            'inputs': {
              for (final entry in inputs.entries)
                if (entry.value != null && '${entry.value}'.trim().isNotEmpty)
                  entry.key: entry.value,
            },
          },
        ),
      );

  Future<List<GeneratedDocument>> listGenerated({
    String? category,
    int limit = 20,
    int offset = 0,
  }) async {
    final response = await _client.getList(
      '/generated',
      query: {
        'limit': limit,
        'offset': offset,
        if (category != null) 'category': category,
      },
    );
    return response
        .whereType<Map<String, dynamic>>()
        .map(GeneratedDocument.fromJson)
        .toList(growable: false);
  }
}
