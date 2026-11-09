import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/profile/data/subscription_failover.dart';
import 'package:hiddify/features/profile/data/subscription_groups.dart';
import 'package:hiddify/hiddifycore/generated/google/protobuf/timestamp.pb.dart';
import 'package:hiddify/hiddifycore/generated/v2/hcore/hcore.pb.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test('imports a list without duplicate subscriptions and rejects malformed lines', () {
    expect(parseSubscriptionUrls('https://one.example/sub\n# comment\nhttps://one.example/sub\nhttps://two.example'), [
      'https://one.example/sub',
      'https://two.example',
    ]);
    expect(() => parseSubscriptionUrls('https://one.example\nnot a URL'), throwsFormatException);
    expect(parseRemoteSubscriptionList('https://two.example\nhttps://one.example'), [
      'https://two.example',
      'https://one.example',
    ]);
    expect(parseRemoteSubscriptionList('vless://server'), isNull);
  });

  test('keeps group order and stops when there is no next subscription', () async {
    SharedPreferences.setMockInitialValues({});
    final store = SubscriptionGroupsStore(await SharedPreferences.getInstance());
    const group = SubscriptionGroup(
      id: 'work',
      name: 'Рабочие',
      urls: ['https://a.example', 'https://b.example', 'https://c.example'],
      sourceUrl: 'https://example.com/all.txt',
    );
    await store.write([group]);
    expect(store.read().single.name, 'Рабочие');
    expect(store.read().single.sourceUrl, 'https://example.com/all.txt');
    await store.selectGroup('work');
    expect(store.selectedGroup?.name, 'Рабочие');
    await store.selectGroup(null);
    expect(store.selectedGroup, isNull);
    expect(
      nextSubscriptionUrl(store.read(), 'https://a.example', {'https://a.example', 'https://c.example'}),
      'https://c.example',
    );
    expect(nextSubscriptionUrl(store.read(), 'https://c.example', {'https://a.example', 'https://c.example'}), isNull);
  });

  test('adding a remote list saves subscriptions in source order', () async {
    SharedPreferences.setMockInitialValues({});
    final store = SubscriptionGroupsStore(await SharedPreferences.getInstance());
    final fetched = <String>[];
    Future<bool> ensure(String url) async {
      fetched.add(url);
      return !url.contains('/missing');
    }

    final group = await importRemoteSubscriptionList(
      store: store,
      sourceUrl: 'https://example.com/all.txt',
      content: 'https://example.com/third\nhttps://example.com/missing\nhttps://example.com/first',
      ensureSubscription: ensure,
    );
    expect(fetched, ['https://example.com/third', 'https://example.com/missing', 'https://example.com/first']);
    expect(store.read().single.urls, ['https://example.com/third', 'https://example.com/first']);
    expect(store.read().single.sourceUrl, 'https://example.com/all.txt');

    final updated = await importRemoteSubscriptionList(
      store: store,
      sourceUrl: 'https://example.com/all.txt',
      content: 'https://example.com/first\nhttps://example.com/third',
      ensureSubscription: (_) async => true,
    );
    expect(updated.id, group.id);
    expect(store.read().single.urls, ['https://example.com/first', 'https://example.com/third']);
  });

  test('switches only after every fresh result is unavailable', () {
    final started = DateTime.utc(2026, 11, 8, 12);
    OutboundInfo proxy(String name, int delay, DateTime time) =>
        OutboundInfo(tag: name, urlTestDelay: delay, urlTestTime: Timestamp.fromDateTime(time));
    OutboundGroup group(List<OutboundInfo> items) => OutboundGroup(tag: 'select', items: items);
    final old = started.subtract(const Duration(seconds: 1));
    final fresh = started.add(const Duration(seconds: 1));
    expect(allProxiesUnavailable(group([proxy('a', 0, old), proxy('b', 0, fresh)]), started), isNull);
    expect(allProxiesUnavailable(group([proxy('a', 0, fresh), proxy('b', 40, fresh)]), started), isFalse);
    expect(allProxiesUnavailable(group([proxy('a', 0, fresh), proxy('b', -1, fresh)]), started), isTrue);
  });

  test('terminal core failures count as unavailable while connecting proxies keep waiting', () {
    final started = DateTime.utc(2026, 11, 8, 12);
    final freshFailure = OutboundInfo(
      tag: 'timed-out',
      urlTestDelay: -1,
      urlTestTime: Timestamp.fromDateTime(started.add(const Duration(seconds: 1))),
    );
    final failedToStart = OutboundInfo(tag: 'invalid', status: Status.FAILED);
    final stillConnecting = OutboundInfo(tag: 'starting', status: Status.CONNECTING);

    expect(allProxiesUnavailable(OutboundGroup(tag: 'select', items: [freshFailure, failedToStart]), started), isTrue);
    expect(
      allProxiesUnavailable(OutboundGroup(tag: 'select', items: [freshFailure, stillConnecting]), started),
      isNull,
    );
  });

  test('switches to the next list entry even when test results arrive immediately', () async {
    const group = SubscriptionGroup(
      id: 'ordered',
      name: 'ordered',
      urls: ['https://one.example', 'https://two.example', 'https://three.example'],
    );
    final started = DateTime.now();
    final results = StreamController<OutboundGroup?>.broadcast(sync: true);
    addTearDown(results.close);
    var calls = 0;
    Future<void> runTest(int delay) async {
      calls++;
      results.add(
        OutboundGroup(
          tag: 'select',
          items: [OutboundInfo(tag: 'proxy', urlTestDelay: delay, urlTestTime: Timestamp.fromDateTime(started))],
        ),
      );
    }

    final next = await nextSubscriptionAfterTest(
      group: group,
      currentUrl: group.urls.first,
      existingUrls: group.urls.toSet(),
      results: results.stream,
      runTest: () => runTest(-1),
      started: started,
    );
    expect(next, group.urls[1]);
    final reachable = await nextSubscriptionAfterTest(
      group: group,
      currentUrl: group.urls[1],
      existingUrls: group.urls.toSet(),
      results: results.stream,
      runTest: () => runTest(80),
      started: started,
    );
    expect(reachable, isNull);
    final end = await nextSubscriptionAfterTest(
      group: group,
      currentUrl: group.urls.last,
      existingUrls: group.urls.toSet(),
      results: results.stream,
      runTest: () => runTest(-1),
      started: started,
    );
    expect(end, isNull);
    expect(calls, 3);
  });

  test('checks each subscription in source order and ignores other or stale results', () async {
    const group = SubscriptionGroup(
      id: 'ordered',
      name: 'ordered',
      urls: ['https://one.example', 'https://two.example', 'https://three.example'],
    );
    final results = StreamController<OutboundGroup?>.broadcast(sync: true);
    addTearDown(results.close);
    var current = group.urls.first;
    final visited = <String>[];

    for (var index = 0; index < group.urls.length; index++) {
      final started = DateTime.now();
      final next = await nextSubscriptionAfterTest(
        group: group,
        currentUrl: current,
        existingUrls: group.urls.toSet(),
        results: results.stream,
        runTest: () async {
          visited.add(current);
          if (index == group.urls.length - 1) return;
          results
            ..add(
              OutboundGroup(
                tag: 'unrelated',
                items: [
                  OutboundInfo(
                    tag: 'proxy',
                    urlTestDelay: 50,
                    urlTestTime: Timestamp.fromDateTime(started.add(const Duration(seconds: 1))),
                  ),
                ],
              ),
            )
            ..add(
              OutboundGroup(
                tag: 'select',
                items: [
                  OutboundInfo(
                    tag: 'proxy',
                    urlTestDelay: -1,
                    urlTestTime: Timestamp.fromDateTime(started.subtract(const Duration(seconds: 1))),
                  ),
                ],
              ),
            )
            ..add(
              OutboundGroup(
                tag: 'select',
                items: [
                  OutboundInfo(
                    tag: 'proxy',
                    urlTestDelay: -1,
                    urlTestTime: Timestamp.fromDateTime(started.add(const Duration(seconds: 1))),
                  ),
                ],
              ),
            );
        },
        started: started,
      );
      if (next == null) break;
      expect(next, group.urls[index + 1]);
      current = next;
    }

    expect(visited, group.urls);
  });
}
