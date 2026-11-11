import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/profile/data/profile_list_parser.dart';

void main() {
  test('reads one URL a line, in order', () {
    const content = '''
https://raw.githubusercontent.com/Lynatick/goida/main/hiddify/groups/anytls1.json
https://raw.githubusercontent.com/Lynatick/goida/main/hiddify/groups/vless1.json

https://panel.example.com/sub/abc
''';

    expect(ProfileListParser.parse(content), [
      'https://raw.githubusercontent.com/Lynatick/goida/main/hiddify/groups/anytls1.json',
      'https://raw.githubusercontent.com/Lynatick/goida/main/hiddify/groups/vless1.json',
      'https://panel.example.com/sub/abc',
    ]);
  });

  test('drops comments, duplicates, blanks and anything but an absolute http(s) URL', () {
    const content = '''
# a comment
// another
https://panel.example.com/sub/abc
https://panel.example.com/sub/abc
ftp://panel.example.com/sub
/sub/relative
not a link
   
https://panel.example.com/sub/def
''';

    expect(ProfileListParser.parse(content), [
      'https://panel.example.com/sub/abc',
      'https://panel.example.com/sub/def',
    ]);
  });

  test('the number of members is bounded', () {
    final content = [for (var i = 0; i < ProfileListParser.maxMembers + 10; i++) 'https://panel.example.com/sub/$i'].join('\n');

    expect(ProfileListParser.parse(content), hasLength(ProfileListParser.maxMembers));
  });
}
