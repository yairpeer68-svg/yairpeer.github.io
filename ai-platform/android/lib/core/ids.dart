import 'dart:math';
import 'storage.dart';

class InstallationId {
  final SecureTokenStore store;
  InstallationId(this.store);

  Future<String> get() async {
    final existing = await store.installationId();
    if (existing != null && existing.length >= 32) return existing;
    final random = Random.secure();
    final bytes = List<int>.generate(32, (_) => random.nextInt(256));
    final id = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    await store.writeInstallationId(id);
    return id;
  }
}
