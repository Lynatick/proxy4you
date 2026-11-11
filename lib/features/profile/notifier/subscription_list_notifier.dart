import 'package:dio/dio.dart';
import 'package:hiddify/core/http_client/http_client_provider.dart';
import 'package:hiddify/core/preferences/general_preferences.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/data/profile_list_parser.dart';
import 'package:hiddify/features/profile/data/subscription_list_store.dart';
import 'package:hiddify/features/profile/model/subscription_list.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:uuid/uuid.dart';

part 'subscription_list_notifier.g.dart';

/// The subscription lists the user added, and the members they carry.
///
/// Adding a list fetches it, turns every subscription it names into an ordinary profile (fetched,
/// updated and connected the same way) and keeps their order. Updating the list re-reads it and
/// refreshes every member: that is the "update the subscriptions" of the list.
@Riverpod(keepAlive: true)
class SubscriptionLists extends _$SubscriptionLists with AppLogger {
  @override
  List<SubscriptionList> build() {
    return SubscriptionListStore(ref.watch(sharedPreferencesProvider).requireValue).read();
  }

  SubscriptionListStore get _store => SubscriptionListStore(ref.read(sharedPreferencesProvider).requireValue);

  Future<void> _save(List<SubscriptionList> lists) async {
    state = lists;
    await _store.write(lists);
  }

  List<SubscriptionList> get _lists => state;

  SubscriptionList? _byId(String id) {
    for (final list in _lists) {
      if (list.id == id) return list;
    }
    return null;
  }

  /// Fetches [url], adds the subscriptions it names as profiles, and stores the list. Returns its
  /// id, or null when the list has no usable subscription.
  Future<String?> add(String url) async {
    final links = await _fetch(url);
    if (links.isEmpty) {
      loggy.warning('the subscription list has no subscription links');
      return null;
    }
    final memberIds = <String>[];
    for (final link in links) {
      final id = await _ensureProfile(link);
      if (id != null) memberIds.add(id);
    }
    if (memberIds.isEmpty) return null;
    final id = const Uuid().v4();
    final list = SubscriptionList(
      id: id,
      name: _nameFor(url),
      url: url,
      lastUpdate: DateTime.now(),
      memberIds: memberIds,
      activeMemberId: memberIds.first,
    );
    await _save([..._lists, list]);
    return id;
  }

  /// Re-reads the list and refreshes every subscription it carries. Members that are gone from
  /// the list are dropped; new ones are added.
  Future<bool> update(String id) async {
    final list = _byId(id);
    if (list == null) return false;
    final links = await _fetch(list.url);
    if (links.isEmpty) return false;
    final updated = <String>[];
    for (final link in links) {
      final memberId = await _ensureProfile(link);
      if (memberId != null) updated.add(memberId);
    }
    if (updated.isEmpty) return false;
    final memberIds = mergeMemberIds(list.memberIds, updated, activeMemberId: list.activeMemberId);
    final active = memberIds.contains(list.activeMemberId)
        ? list.activeMemberId
        : (memberIds.isEmpty ? null : memberIds.first);
    await _save([
      for (final candidate in _lists)
        if (candidate.id == id)
          candidate.copyWith(
            lastUpdate: DateTime.now(),
            memberIds: memberIds,
            activeMemberId: active,
            clearActiveMember: active == null,
          )
        else
          candidate,
    ]);
    return true;
  }

  Future<void> remove(String id) async {
    await _save([
      for (final list in _lists)
        if (list.id != id) list,
    ]);
    if (ref.read(Preferences.activeSubscriptionList) == id) {
      await ref.read(Preferences.activeSubscriptionList.notifier).update('');
    }
  }

  /// Makes [listId] the list in use and its current member the active profile, so a connect
  /// starts from the member rather than from whatever subscription was active before.
  Future<void> activate(String listId) async {
    final list = _byId(listId);
    if (list == null || list.memberIds.isEmpty) return;
    final memberId = list.activeMemberId ?? list.memberIds.first;
    await ref.read(profileRepositoryProvider).requireValue.setAsActive(memberId).run();
    await setActiveMember(listId, memberId);
  }

  /// The member of [id] that is in use, and so the profile that connects.
  Future<void> setActiveMember(String id, String? memberId) async {
    await _save([
      for (final list in _lists)
        if (list.id == id) list.copyWith(activeMemberId: memberId, clearActiveMember: memberId == null) else list,
    ]);
  }

  Future<List<String>> _fetch(String url) async {
    final content = await ref.read(httpClientProvider).getText(url);
    return ProfileListParser.parse(content);
  }

  /// Adds or refreshes the profile of [link] and returns its id.
  Future<String?> _ensureProfile(String link) async {
    final repository = ref.read(profileRepositoryProvider).requireValue;
    await repository.upsertRemote(link, cancelToken: CancelToken()).run();
    final entry = await ref.read(profileDataSourceProvider).getByUrl(link);
    return entry?.id;
  }

  static String _nameFor(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null) return url;
    final segments = uri.pathSegments.where((segment) => segment.isNotEmpty).toList();
    final last = segments.isEmpty ? null : segments.last;
    if (last == null || last.isEmpty) return uri.host;
    return last.replaceFirst(RegExp(r'\.(txt|json|ya?ml)$', caseSensitive: false), '');
  }
}

/// The list in use, or null when a single subscription (or nothing) is selected.
@Riverpod(keepAlive: true)
SubscriptionList? activeSubscriptionList(Ref ref) {
  final id = ref.watch(Preferences.activeSubscriptionList);
  if (id.isEmpty) return null;
  for (final list in ref.watch(subscriptionListsProvider)) {
    if (list.id == id) return list;
  }
  return null;
}
