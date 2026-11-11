import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/logger/sinks/sentry_sink.dart';
import 'package:logging/logging.dart';

void main() {
  LogRecord record(Level level, String message, String logger, [Object? error]) =>
      LogRecord(level, message, logger, error);

  group('canSendRecord', () {
    test('sends a severe error of the app', () {
      expect(canSendRecord(record(Level.SEVERE, 'failed to open the database', 'app.Bootstrap')), isTrue);
    });

    test('does not send info or warning', () {
      expect(canSendRecord(record(Level.INFO, 'connected', 'app.HomePage')), isFalse);
      expect(canSendRecord(record(Level.WARNING, 'retrying', 'app.HomePage')), isFalse);
    });

    test('does not send the engine log, however severe', () {
      for (final logger in ['core', 'core.core', 'core.service', 'core.config']) {
        expect(canSendRecord(record(Level.SEVERE, 'connection to example.com failed', logger)), isFalse, reason: logger);
      }
    });

    test('does not send an error the app expects', () {
      expect(canSendRecord(record(Level.SEVERE, 'no route', 'app.HomePage', const SocketException('down'))), isFalse);
    });
  });

  group('redactLogMessage', () {
    test('replaces a subscription link', () {
      expect(
        redactLogMessage('adding https://panel.example/sub/AbC123?token=secret failed'),
        'adding <url> failed',
      );
    });

    test('replaces a share link', () {
      expect(redactLogMessage('vless://uuid@host:443?security=tls dropped'), '<url> dropped');
    });

    test('keeps a message without a link', () {
      expect(redactLogMessage('the core did not start in time'), 'the core did not start in time');
    });
  });
}
