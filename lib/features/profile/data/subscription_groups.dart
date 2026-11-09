import 'dart:convert';

import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

final subscriptionGroupsRevisionProvider = StateProvider<int>((ref) => 0);

class SubscriptionGroup {
  const SubscriptionGroup({required this.id, required this.name, required this.urls, this.sourceUrl});

  final String id;
  final String name;
  final List<String> urls;
  final String? sourceUrl;

  Map<String, Object> toJson() => {
    'id': id,
    'name': name,
    'urls': urls,
    if (sourceUrl != null) 'sourceUrl': sourceUrl!,
  };

  static SubscriptionGroup? fromJson(Object? value) {
    if (value is! Map<String, dynamic>) return null;
    final name = value['name'];
    final urls = value['urls'];
    if (name is! String || name.trim().isEmpty || urls is! List) return null;
    final parsed = _orderedUniqueUrls(urls.whereType<String>().where(isSubscriptionUrl));
    if (parsed.isEmpty) return null;
    final id = value['id'];
    return SubscriptionGroup(
      id: id is String && id.isNotEmpty ? id : 'legacy:${name.trim()}',
      name: name.trim(),
      urls: parsed,
      sourceUrl: value['sourceUrl'] is String && isSubscriptionUrl(value['sourceUrl'] as String)
          ? value['sourceUrl'] as String
          : null,
    );
  }
}

bool isSubscriptionUrl(String value) {
  final uri = Uri.tryParse(value.trim());
  return uri != null && (uri.scheme == 'https' || uri.scheme == 'http') && uri.host.isNotEmpty;
}

List<String> parseSubscriptionUrls(String text) {
  final urls = <String>[];
  final seen = <String>{};
  for (final line in const LineSplitter().convert(text)) {
    final value = line.trim();
    if (value.isEmpty || value.startsWith('#')) continue;
    if (!isSubscriptionUrl(value)) throw FormatException('Некорректный URL подписки', value);
    if (seen.add(value)) urls.add(value);
  }
  return urls;
}

/// Removes duplicates without changing the order in which URLs appeared.
List<String> _orderedUniqueUrls(Iterable<String> values) {
  final seen = <String>{};
  return [
    for (final value in values)
      if (seen.add(value)) value,
  ];
}

/// A remote subscription list contains two or more HTTP(S) subscription URLs,
/// one per line. A regular proxy configuration is left to the core parser.
List<String>? parseRemoteSubscriptionList(String text) {
  if (text.length > 262144) return null;
  try {
    final urls = parseSubscriptionUrls(text);
    return urls.length >= 2 ? urls : null;
  } on FormatException {
    return null;
  }
}

class SubscriptionGroupsStore {
  SubscriptionGroupsStore(this._preferences);

  static const key = 'proxy4you.subscription_groups.v1';
  static const selectedKey = 'proxy4you.selected_subscription_group.v1';
  final SharedPreferences _preferences;

  List<SubscriptionGroup> read() {
    final raw = _preferences.getString(key);
    if (raw == null) return const [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return const [];
      return [
        for (final entry in decoded)
          if (SubscriptionGroup.fromJson(entry) case final group?) group,
      ];
    } on FormatException {
      return const [];
    }
  }

  Future<void> write(List<SubscriptionGroup> groups) async {
    final saved = await _preferences.setString(key, jsonEncode(groups.map((group) => group.toJson()).toList()));
    if (!saved) throw StateError('Не удалось сохранить список подписок');
    if (selectedGroupId case final selected? when !groups.any((group) => group.id == selected)) {
      await selectGroup(null);
    }
  }

  String? get selectedGroupId {
    final selected = _preferences.getString(selectedKey);
    if (selected == null || selected.isEmpty) return null;
    return read().any((group) => group.id == selected) ? selected : null;
  }

  SubscriptionGroup? get selectedGroup {
    final selected = selectedGroupId;
    if (selected == null) return null;
    for (final group in read()) {
      if (group.id == selected) return group;
    }
    return null;
  }

  Future<void> selectGroup(String? id) async {
    if (id != null && !read().any((group) => group.id == id)) {
      throw ArgumentError.value(id, 'id', 'Список подписок не найден');
    }
    if (!await _preferences.setString(selectedKey, id ?? '')) {
      throw StateError('Не удалось сохранить выбор списка подписок');
    }
  }
}

Future<SubscriptionGroup> importRemoteSubscriptionList({
  required SubscriptionGroupsStore store,
  required String sourceUrl,
  required String content,
  required Future<bool> Function(String url) ensureSubscription,
  String? name,
}) async {
  final urls = parseRemoteSubscriptionList(content);
  if (urls == null) throw const FormatException('Файл не содержит список подписок');
  final imported = <String>[];
  for (final url in urls) {
    if (await ensureSubscription(url)) imported.add(url);
  }
  if (imported.isEmpty) throw StateError('Не удалось загрузить ни одной подписки из списка');

  final groups = store.read();
  final old = groups.where((group) => group.sourceUrl == sourceUrl).firstOrNull;
  final uri = Uri.parse(sourceUrl);
  final fallbackName = uri.pathSegments.isEmpty ? uri.host : uri.pathSegments.last;
  final group = SubscriptionGroup(
    id: old?.id ?? const Uuid().v4(),
    name: name?.trim().isNotEmpty == true ? name!.trim() : old?.name ?? fallbackName,
    urls: imported,
    sourceUrl: sourceUrl,
  );
  final next = [...groups];
  if (old == null) {
    next.add(group);
  } else {
    next[next.indexOf(old)] = group;
  }
  await store.write(next);
  return group;
}

/// Returns the next existing subscription in the current group, without wrapping.
/// A failed list stops at its end instead of testing the same subscriptions forever.
String? nextSubscriptionUrl(List<SubscriptionGroup> groups, String currentUrl, Set<String> existingUrls) {
  for (final group in groups) {
    final index = group.urls.indexOf(currentUrl);
    if (index < 0) continue;
    for (final url in group.urls.skip(index + 1)) {
      if (existingUrls.contains(url)) return url;
    }
  }
  return null;
}
