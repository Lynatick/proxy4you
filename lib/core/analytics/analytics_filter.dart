import 'dart:async';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:hiddify/core/logger/sinks/sentry_sink.dart';
import 'package:hiddify/core/model/failures.dart';
import 'package:hiddify/features/proxy/model/proxy_failure.dart';
import 'package:sentry_flutter/sentry_flutter.dart';

/// The one policy of what leaves for Sentry: a network error the app already handles is not a
/// crash, and whatever is sent carries no address, link or token.
FutureOr<SentryEvent?> sentryBeforeSend(SentryEvent event, Hint hint) {
  if (!canSendEvent(event.throwable)) return null;
  return event.copyWith(
    user: SentryUser(email: "", username: "", ipAddress: "0.0.0.0"),
    message: event.message == null ? null : SentryMessage(redactLogMessage(event.message!.formatted)),
    // ignore: deprecated_member_use
    extra: event.extra == null ? null : _redactExtra(event.extra!),
  );
}

Map<String, dynamic> _redactExtra(Map<String, dynamic> extra) => {
  for (final MapEntry(:key, :value) in extra.entries)
    key: switch (value) {
      final String text => redactLogMessage(text),
      final Iterable<Object?> list => list.map((item) => item is String ? redactLogMessage(item) : item).toList(),
      _ => value,
    },
};

bool canSendEvent(dynamic throwable) {
  return switch (throwable) {
    UnexpectedFailure(:final error) => canSendEvent(error),
    DioException _ => false,
    SocketException _ => false,
    UnknownIp _ => false,
    HttpException _ => false,
    HandshakeException _ => false,
    ExpectedFailure _ => false,
    ExpectedMeasuredFailure _ => false,
    _ => true,
  };
}

bool canLogEvent(dynamic throwable) => switch (throwable) {
  ExpectedMeasuredFailure _ => true,
  _ => canSendEvent(throwable),
};
