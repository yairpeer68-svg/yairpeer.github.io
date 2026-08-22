/// Single source of truth for the shipped client version.
///
/// The version used to be hard-coded separately in device registration, the update
/// gate and the About screen. They drifted from pubspec.yaml, so the update gate
/// compared the wrong number and could lock users out of a build they already ran.
/// `--dart-define=APP_VERSION=...` lets CI stamp the pubspec value at build time.
class AppInfo {
  static const version =
      String.fromEnvironment('APP_VERSION', defaultValue: '2.1.1');
  static const buildNumber =
      String.fromEnvironment('APP_BUILD_NUMBER', defaultValue: '4');
  static const displayVersion = '$version+$buildNumber';

  /// Compares dotted numeric versions, ignoring any build/pre-release suffix.
  static int compare(String a, String b) {
    List<int> parts(String s) => s
        .split('.')
        .take(3)
        .map((x) => int.tryParse(x.replaceAll(RegExp(r'[^0-9].*'), '')) ?? 0)
        .toList();
    final left = parts(a), right = parts(b);
    for (var i = 0; i < 3; i++) {
      final l = i < left.length ? left[i] : 0;
      final r = i < right.length ? right[i] : 0;
      if (l != r) return l.compareTo(r);
    }
    return 0;
  }
}
