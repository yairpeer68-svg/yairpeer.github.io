/// Client-side abstraction for Google Play Integrity.
///
/// Production integrations should obtain a fresh integrity token from the
/// official Play Integrity SDK and send it to the backend for verification.
/// The backend remains the trust boundary; this interface never treats a
/// missing provider as successful attestation.
abstract interface class AttestationProvider {
  bool get configured;
  Future<String?> requestToken({required String nonce});
}

final class NotConfiguredAttestationProvider implements AttestationProvider {
  const NotConfiguredAttestationProvider();

  @override
  bool get configured => false;

  @override
  Future<String?> requestToken({required String nonce}) async => null;
}
