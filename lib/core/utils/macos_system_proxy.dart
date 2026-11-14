import 'dart:io';

import 'package:hiddify/utils/utils.dart';

/// The system proxy on macOS needs root, and the core the app runs as the user can only fail
/// `networksetup` (exit status 14) — taking the whole connection down with it. The core is
/// therefore started without `set_system_proxy` on macOS, and once the connection is up the app
/// points the SOCKS proxy at the mixed port itself, with one administrator prompt; stopping the
/// core turns it off again.
abstract final class MacosSystemProxy {
  static const _service = 'Wi-Fi';
  static bool _applied = false;

  static bool get applied => _applied;

  static Future<bool> apply(int port) async {
    if (!PlatformUtils.isMacOS) return false;
    final applied = await _run(
      '-setsocksfirewallproxy $_service 127.0.0.1 $port; /usr/sbin/networksetup -setsocksfirewallproxystate $_service on',
    );
    _applied = applied;
    return applied;
  }

  static Future<bool> clear() async {
    if (!PlatformUtils.isMacOS || !_applied) return true;
    final cleared = await _run('-setsocksfirewallproxystate $_service off');
    if (cleared) _applied = false;
    return cleared;
  }

  static Future<bool> _run(String command) async {
    final script = 'do shell script "/usr/sbin/networksetup $command" with administrator privileges';
    final logger = infraLogger('MacosSystemProxy');
    try {
      final result = await Process.run('/usr/bin/osascript', ['-e', script]);
      if (result.exitCode != 0) {
        logger.warning('networksetup failed: ${result.stderr}');
        return false;
      }
      return true;
    } catch (error) {
      logger.warning('could not run networksetup', error);
      return false;
    }
  }
}
