import 'dart:convert';

/// A subscription whose content is other subscriptions, one URL a line.
///
/// Members are ordinary profiles: each is fetched, updated and connected on its own, and only
/// the member in use is handed to the core. The list itself keeps the order of its members and
/// the one that is active.
class SubscriptionList {
  const SubscriptionList({
    required this.id,
    required this.name,
    required this.url,
    required this.lastUpdate,
    required this.memberIds,
    this.activeMemberId,
  });

  final String id;
  final String name;
  final String url;
  final DateTime lastUpdate;
  final List<String> memberIds;
  final String? activeMemberId;

  int get activeIndex {
    final id = activeMemberId;
    return id == null ? -1 : memberIds.indexOf(id);
  }

  /// The member after [activeMemberId], wrapping; null for an empty list.
  String? get memberAfterActive {
    if (memberIds.isEmpty) return null;
    final index = activeIndex;
    return memberIds[(index + 1) % memberIds.length];
  }

  SubscriptionList copyWith({
    String? name,
    DateTime? lastUpdate,
    List<String>? memberIds,
    String? activeMemberId,
    bool clearActiveMember = false,
  }) => SubscriptionList(
    id: id,
    name: name ?? this.name,
    url: url,
    lastUpdate: lastUpdate ?? this.lastUpdate,
    memberIds: memberIds ?? this.memberIds,
    activeMemberId: clearActiveMember ? null : activeMemberId ?? this.activeMemberId,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'name': name,
    'url': url,
    'lastUpdate': lastUpdate.toUtc().toIso8601String(),
    'memberIds': memberIds,
    if (activeMemberId != null) 'activeMemberId': activeMemberId,
  };

  static SubscriptionList? fromJson(Object? json) {
    if (json is! Map<String, dynamic>) return null;
    final id = json['id'];
    final name = json['name'];
    final url = json['url'];
    final lastUpdate = json['lastUpdate'];
    final memberIds = json['memberIds'];
    if (id is! String || name is! String || url is! String || lastUpdate is! String || memberIds is! List) return null;
    final parsed = DateTime.tryParse(lastUpdate);
    if (parsed == null) return null;
    final members = memberIds.whereType<String>().toList(growable: false);
    final active = json['activeMemberId'];
    return SubscriptionList(
      id: id,
      name: name,
      url: url,
      lastUpdate: parsed,
      memberIds: members,
      activeMemberId: active is String && members.contains(active) ? active : null,
    );
  }

  static String encodeAll(List<SubscriptionList> lists) => jsonEncode([for (final list in lists) list.toJson()]);

  static List<SubscriptionList> decodeAll(String? raw) {
    if (raw == null || raw.trim().isEmpty) return const [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return const [];
      return [for (final item in decoded) ?SubscriptionList.fromJson(item)];
    } on FormatException {
      return const [];
    }
  }
}

/// The members to keep after an update: the ones already there stay in their order, new ones
/// are appended, and members the list no longer references are dropped — except the active one.
List<String> mergeMemberIds(List<String> previous, List<String> updated, {String? activeMemberId}) {
  final merged = <String>[];
  for (final id in previous) {
    if (updated.contains(id)) merged.add(id);
  }
  for (final id in updated) {
    if (!merged.contains(id)) merged.add(id);
  }
  if (activeMemberId != null && merged.isEmpty && updated.isEmpty && previous.contains(activeMemberId)) {
    merged.add(activeMemberId);
  }
  return merged;
}

/// The next member to try, in order after [currentId], skipping the ones already tried. Null
/// once every member was tried: one pass through the list, then the failure is reported.
String? nextUntriedMember(List<String> memberIds, String? currentId, Set<String> attempted) {
  if (memberIds.isEmpty) return null;
  final start = currentId == null ? 0 : memberIds.indexOf(currentId) + 1;
  for (var step = 0; step < memberIds.length; step++) {
    final id = memberIds[(start + step) % memberIds.length];
    if (!attempted.contains(id)) return id;
  }
  return null;
}
