/// Parses the content of a subscription list: one subscription URL a line.
///
/// A list is fetched like a subscription, and what it carries is not a config but other
/// subscriptions (the groups of one panel, for instance). Blank lines and comments (`#`, `//`)
/// are ignored, only absolute http(s) URLs are kept, duplicates and order are preserved, and the
/// number of members is bounded: the content comes from whoever wrote the list.
abstract final class ProfileListParser {
  /// The most members one list may have. A panel with more is a list to split, and every member
  /// costs a fetch and a profile row.
  static const maxMembers = 256;

  /// The subscription URLs of [content], in the order they appear.
  static List<String> parse(String content) {
    final seen = <String>{};
    final urls = <String>[];
    for (final raw in content.split('\n')) {
      final line = raw.trim();
      if (line.isEmpty || line.startsWith('#') || line.startsWith('//')) continue;
      final uri = Uri.tryParse(line);
      if (uri == null || !uri.hasAuthority || (uri.scheme != 'http' && uri.scheme != 'https')) continue;
      if (!seen.add(line)) continue;
      urls.add(line);
      if (urls.length >= maxMembers) break;
    }
    return urls;
  }
}
