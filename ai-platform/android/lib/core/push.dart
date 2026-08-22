/// Push registration abstraction. FCM can be connected without coupling the
/// rest of the application to a concrete Firebase implementation.
abstract interface class PushRegistrationProvider {
  bool get configured;
  Future<String?> currentToken();
}

final class NotConfiguredPushRegistrationProvider
    implements PushRegistrationProvider {
  const NotConfiguredPushRegistrationProvider();

  @override
  bool get configured => false;

  @override
  Future<String?> currentToken() async => null;
}
