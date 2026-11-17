import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/analytics/analytics_filter.dart';
import 'package:sentry_flutter/sentry_flutter.dart';

void main() {
  // `extra` is deprecated in the SDK but is what the app's events carry, so the test uses it.
  SentryEvent event({String? message, Map<String, dynamic>? extra, Object? throwable}) {
    return SentryEvent(
      message: message == null ? null : SentryMessage(message),
      // ignore: deprecated_member_use
      extra: extra,
      throwable: throwable,
    );
  }

  test('a message with a link is sent redacted', () async {
    final filtered = await sentryBeforeSend(
      event(message: 'failed https://panel.example.com/sub?token=abc', throwable: Exception('x')),
      Hint(),
    );
    expect(filtered, isNotNull);
    expect(filtered!.message!.formatted, 'failed <redacted-url>');
  });

  test('extra values are redacted, other types are kept', () async {
    final filtered = await sentryBeforeSend(
      event(extra: {'url': 'https://example.com/a', 'count': 3}, throwable: Exception('x')),
      Hint(),
    );
    // ignore: deprecated_member_use
    expect(filtered!.extra!['url'], '<redacted-url>');
    // ignore: deprecated_member_use
    expect(filtered.extra!['count'], 3);
  });

  test('the user is emptied', () async {
    final filtered = await sentryBeforeSend(event(throwable: Exception('x')), Hint());
    expect(filtered!.user!.ipAddress, '0.0.0.0');
    expect(filtered.user!.email, '');
  });
}
