import 'dart:async';

import 'package:hiddify/utils/sentry_utils.dart';
import 'package:logging/logging.dart';
import 'package:sentry_flutter/sentry_flutter.dart';

// Adapted from https://github.com/getsentry/sentry-dart/tree/main/logging
//
// This used to be a loggy printer held in LoggerController's map. It is now a
// plain listener on the root stream, attached when the user turns analytics on
// and cancelled when they turn it off.
//
// What leaves the device is the app's own severe errors, and nothing else. The
// engine's lines (logger `core.*`) stay in the app log: they name destinations,
// servers and sometimes credentials, which a crash report must not carry.
//
// No record becomes a breadcrumb, either: a breadcrumb keeps the message of
// every line above INFO until the crash that follows, which put the same data
// back into the report.

/// Records at or above this level are reported as an event of their own.
const _minEventLevel = Level.SEVERE;

StreamSubscription<LogRecord>? _sub;

bool get sentryEnabled => _sub != null;

/// Starts forwarding to Sentry. Safe to call twice.
void logEnableSentry() {
  _sub ??= Logger.root.onRecord.listen(_toSentry);
}

/// Stops forwarding. The records still reach every other sink.
void logDisableSentry() {
  _sub?.cancel();
  _sub = null;
}

/// Kept so SentryFlutter.init can register it as an integration, which is what
/// makes the SDK aware of us.
class SentryLogIntegration implements Integration<SentryOptions> {
  @override
  void call(Hub hub, SentryOptions options) {
    options.sdk.addIntegration('LoggingIntegration');
  }

  @override
  Future<void> close() async {}
}

/// Whether a record may be sent: a severe error of the app's own, whose
/// exception the filter would let through. The engine's lines never qualify.
bool canSendRecord(LogRecord record) =>
    record.level >= _minEventLevel &&
    !isEngineLogger(record.loggerName) &&
    canLogEvent(record.error);

/// The engine's loggers: `core.core`, `core.service`, `core.config`.
bool isEngineLogger(String loggerName) => loggerName == 'core' || loggerName.startsWith('core.');

final _url = RegExp(r'''[a-zA-Z][a-zA-Z0-9+.-]*://[^\s"'<>]+''');

/// A message with every URL replaced by `<url>`: a profile and a subscription
/// link carry a host and, often, a token.
String redactLogMessage(String message) => message.replaceAll(_url, '<url>');

Future<void> _toSentry(LogRecord record) async {
  if (!canSendRecord(record)) return;
  await Sentry.captureEvent(
    record.toSentryEvent(),
    stackTrace: record.stackTrace,
  );
}

extension LogRecordX on LogRecord {
  SentryEvent toSentryEvent() {
    return SentryEvent(
      timestamp: time.toUtc(),
      logger: loggerName,
      level: level.toSentryLevel(),
      message: SentryMessage(redactLogMessage(message)),
      throwable: error,
      // ignore: deprecated_member_use
      extra: <String, Object>{
        if (object != null) 'LogRecord.object': redactLogMessage('$object'),
        'LogRecord.sequenceNumber': sequenceNumber,
      },
    );
  }
}

extension LevelX on Level {
  /// Six levels map onto Sentry's five; FINEST and FINE both mean debug.
  SentryLevel? toSentryLevel() => switch (this) {
    Level.FINEST || Level.FINER || Level.FINE => SentryLevel.debug,
    Level.CONFIG || Level.INFO => SentryLevel.info,
    Level.WARNING => SentryLevel.warning,
    Level.SEVERE => SentryLevel.error,
    Level.SHOUT => SentryLevel.fatal,
    _ => null,
  };
}
