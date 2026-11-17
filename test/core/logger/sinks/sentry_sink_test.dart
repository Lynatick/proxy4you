import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/logger/sinks/sentry_sink.dart';

void main() {
  group('redactLogMessage', () {
    test('masks urls', () {
      expect(redactLogMessage('failed https://user:pass@example.com/sub?token=abc'), 'failed <redacted-url>');
    });

    test('masks long tokens', () {
      expect(redactLogMessage('key ${'a' * 40} failed'), 'key <redacted> failed');
    });

    test('keeps short words', () {
      expect(redactLogMessage('a short message'), 'a short message');
    });
  });

  group('isEngineLogger', () {
    test('engine lines stay local', () {
      expect(isEngineLogger('core.core'), isTrue);
      expect(isEngineLogger('bridge.handler'), isTrue);
      expect(isEngineLogger('app.ConnectionNotifier'), isFalse);
      expect(isEngineLogger(null), isTrue);
    });
  });
}
