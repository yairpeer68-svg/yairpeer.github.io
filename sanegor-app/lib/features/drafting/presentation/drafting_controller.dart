import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/providers.dart';
import '../domain/template.dart';

/// Templates for one category, fetched once and cached for the session.
final templatesProvider =
    FutureProvider.family<List<LegalTemplate>, String>((ref, category) {
  final repository = ref.watch(draftingRepositoryProvider);
  return category == 'letter'
      ? repository.letterTemplates()
      : repository.contractTemplates();
});

/// A single template, resolved from the cached list.
final templateProvider = FutureProvider.family<LegalTemplate?,
    ({String category, String key})>((ref, args) async {
  final templates = await ref.watch(templatesProvider(args.category).future);
  for (final template in templates) {
    if (template.key == args.key) return template;
  }
  return null;
});

final generatedDocumentsProvider =
    FutureProvider.family<List<GeneratedDocument>, String?>(
  (ref, category) =>
      ref.watch(draftingRepositoryProvider).listGenerated(category: category),
);

class DraftingState {
  const DraftingState({
    this.isGenerating = false,
    this.document,
    this.error,
  });

  final bool isGenerating;
  final GeneratedDocument? document;
  final String? error;
}

/// Runs one generation request.
class DraftingController extends StateNotifier<DraftingState> {
  DraftingController(this._ref) : super(const DraftingState());

  final Ref _ref;

  Future<GeneratedDocument?> generate({
    required String category,
    required String templateKey,
    required Map<String, dynamic> inputs,
  }) async {
    state = const DraftingState(isGenerating: true);
    try {
      final repository = _ref.read(draftingRepositoryProvider);
      final document = category == 'letter'
          ? await repository.generateLetter(
              templateKey: templateKey,
              inputs: inputs,
            )
          : await repository.generateContract(
              templateKey: templateKey,
              inputs: inputs,
            );
      state = DraftingState(document: document);
      // The new draft belongs in the saved-documents list.
      _ref.invalidate(generatedDocumentsProvider);
      return document;
    } on ApiException catch (error) {
      state = DraftingState(error: error.message);
      return null;
    }
  }
}

final draftingControllerProvider =
    StateNotifierProvider.autoDispose<DraftingController, DraftingState>(
  DraftingController.new,
);
