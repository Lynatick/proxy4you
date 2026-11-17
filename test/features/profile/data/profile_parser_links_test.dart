import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/profile/data/profile_parser.dart';

void main() {
  group('isAllowedInnerLink', () {
    test('accepts public https subscriptions', () {
      expect(ProfileParser.isAllowedInnerLink('https://example.com/sub.txt'), isTrue);
      expect(ProfileParser.isAllowedInnerLink('http://sub.example.org/a/b'), isTrue);
    });

    test('rejects loopback, link-local and private ranges', () {
      for (final link in [
        'http://127.0.0.1:8080/x',
        'http://localhost/x',
        'https://sub.local/x',
        'http://10.0.0.1/x',
        'http://172.16.0.1/x',
        'http://192.168.1.1/x',
        'http://169.254.169.254/latest',
        'http://[::1]/x',
        'http://[fd00::1]/x',
        'http://[fe80::1]/x',
        'http://[::ffff:127.0.0.1]/x',
      ]) {
        expect(ProfileParser.isAllowedInnerLink(link), isFalse, reason: link);
      }
    });

    test('rejects non-http lines', () {
      expect(ProfileParser.isAllowedInnerLink('ss://base64'), isFalse);
      expect(ProfileParser.isAllowedInnerLink('vless://'), isFalse);
      expect(ProfileParser.isAllowedInnerLink(''), isFalse);
    });
  });

  group('isPublicHost', () {
    test('classifies hosts', () {
      expect(ProfileParser.isPublicHost('example.com'), isTrue);
      expect(ProfileParser.isPublicHost('localhost'), isFalse);
      expect(ProfileParser.isPublicHost('127.0.0.1'), isFalse);
      expect(ProfileParser.isPublicHost('8.8.8.8'), isTrue);
      expect(ProfileParser.isPublicHost('192.168.1.1'), isFalse);
    });
  });

  group('inner links of a profile', () {
    test('a private link stays a line instead of being followed', () {
      // expandLinks only fetches public hosts; the rest are left for the core to read as text.
      expect(ProfileParser.isAllowedInnerLink('http://10.1.2.3/sub'), isFalse);
      expect(ProfileParser.isAllowedInnerLink('https://example.com/sub'), isTrue);
    });
  });
}
