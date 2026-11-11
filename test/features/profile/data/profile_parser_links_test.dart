import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/core/http_client/dio_http_client.dart';
import 'package:hiddify/features/profile/data/profile_parser.dart';
import 'package:hiddify/features/profile/model/profile_failure.dart';

void main() {
  final parser = ProfileParser(
    httpClient: DioHttpClient(timeout: const Duration(seconds: 2), userAgent: 'test', debug: false),
  );

  group('isAllowedInnerLink', () {
    test('refuses the device itself and its network', () {
      for (final line in [
        'http://127.0.0.1:8080/sub',
        'http://localhost/sub',
        'http://panel.local/sub',
        'http://0.0.0.0/sub',
        'http://10.0.0.1/sub',
        'http://172.16.0.1/sub',
        'http://192.168.1.1/sub',
        'http://169.254.169.254/latest/meta-data',
        'http://[::1]/sub',
        'http://[fd00::1]/sub',
      ]) {
        expect(ProfileParser.isAllowedInnerLink(line), isFalse, reason: line);
      }
    });

    test('follows a public subscription', () {
      expect(ProfileParser.isAllowedInnerLink('https://panel.example.com/sub/abc'), isTrue);
      expect(ProfileParser.isAllowedInnerLink('http://1.1.1.1/sub'), isTrue);
    });

    test('refuses anything but http and https', () {
      expect(ProfileParser.isAllowedInnerLink('ftp://example.com/sub'), isFalse);
      expect(ProfileParser.isAllowedInnerLink('file:///etc/passwd'), isFalse);
    });
  });

  // The lines of a profile come from whoever wrote it: they may not turn the device into a
  // client of its own network, and they may not make it fetch without a bound.
  test('more inner links than allowed are refused before anything is fetched', () async {
    final text = [
      for (var i = 0; i < ProfileParser.maxInnerSubscriptionLinks + 1; i++) 'https://panel.example.com/sub/$i',
    ].join('\n');

    await expectLater(parser.expandLinks(text, CancelToken()), throwsA(isA<ProfileFailure>()));
  });

  test('a link to a private address is kept as it is, not fetched', () async {
    const text = 'http://127.0.0.1:8080/sub\ntrojan://pass@example.com:443#name';

    expect(await parser.expandLinks(text, CancelToken()), text);
  });
}
