import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/profile/model/subscription_list.dart';

void main() {
  SubscriptionList list({List<String>? members, String? active}) => SubscriptionList(
    id: 'l1',
    name: 'goida groups',
    url: 'https://raw.example/groups/all.txt',
    lastUpdate: DateTime.utc(2026, 11, 11),
    memberIds: members ?? const ['a', 'b', 'c'],
    activeMemberId: active,
  );

  test('a list survives a JSON round trip', () {
    final before = list(active: 'b');
    final after = SubscriptionList.decodeAll(SubscriptionList.encodeAll([before]));

    expect(after, hasLength(1));
    expect(after.first.id, 'l1');
    expect(after.first.name, 'goida groups');
    expect(after.first.url, 'https://raw.example/groups/all.txt');
    expect(after.first.memberIds, ['a', 'b', 'c']);
    expect(after.first.activeMemberId, 'b');
    expect(after.first.lastUpdate, before.lastUpdate);
  });

  test('malformed content is ignored rather than thrown', () {
    expect(SubscriptionList.decodeAll('not json'), isEmpty);
    expect(SubscriptionList.decodeAll('[{"id": 1}, "x", null]'), isEmpty);
    expect(SubscriptionList.decodeAll(null), isEmpty);
    // an active member that is not in the list is dropped
    final decoded = SubscriptionList.decodeAll(
      '[{"id":"l1","name":"n","url":"https://x/y","lastUpdate":"2026-11-11T00:00:00Z","memberIds":["a"],"activeMemberId":"gone"}]',
    );
    expect(decoded.single.activeMemberId, isNull);
  });

  test('the member after the active one wraps to the start', () {
    expect(list(active: 'b').memberAfterActive, 'c');
    expect(list(active: 'c').memberAfterActive, 'a');
    expect(list().memberAfterActive, 'a');
    expect(list(members: const []).memberAfterActive, isNull);
  });

  test('an update keeps the order, appends new members and drops the rest', () {
    expect(mergeMemberIds(['a', 'b', 'c'], ['c', 'a', 'd']), ['a', 'c', 'd']);
    expect(mergeMemberIds(['a'], ['b', 'a']), ['a', 'b']);
    expect(mergeMemberIds([], ['b', 'a']), ['b', 'a']);
  });

  // One pass: the next member after the current one, then the others, and null once all were
  // tried — the list does not loop forever on a set of dead subscriptions.
  test('one pass through the members, in order, then null', () {
    final attempted = <String>{};
    expect(nextUntriedMember(['a', 'b', 'c'], null, attempted), 'a');
    attempted.add('a');
    expect(nextUntriedMember(['a', 'b', 'c'], 'a', attempted), 'b');
    attempted.add('b');
    expect(nextUntriedMember(['a', 'b', 'c'], 'b', attempted), 'c');
    attempted.add('c');
    expect(nextUntriedMember(['a', 'b', 'c'], 'c', attempted), isNull);
  });
}
