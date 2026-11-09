import 'dart:async';

import 'package:hiddify/core/model/constants.dart';
import 'package:hiddify/features/profile/data/subscription_groups.dart';
import 'package:hiddify/hiddifycore/generated/v2/hcore/hcore.pb.dart';

/// Null means at least one proxy is still waiting for a result from this test.
/// A fresh successful ping always keeps the current subscription selected.
bool? allProxiesUnavailable(OutboundGroup group, DateTime testStarted) {
  final proxies = group.items
      .where((item) => !item.isGroup && !item.tag.contains('§hide§') && item.type != 'direct' && item.type != 'block')
      .toList();
  if (proxies.isEmpty) return null;
  final startedMicros = testStarted.microsecondsSinceEpoch;
  for (final proxy in proxies) {
    // A proxy which the core could not initialize cannot produce a ping result.
    // It is already a terminal failure and must not keep the whole list waiting.
    if (proxy.status == Status.FAILED) continue;
    if (!proxy.hasUrlTestTime()) return null;
    final testedMicros = proxy.urlTestTime.seconds.toInt() * 1000000 + proxy.urlTestTime.nanos ~/ 1000;
    if (testedMicros < startedMicros) return null;
    if (ConnectionConst.isValidDelay(proxy.urlTestDelay)) return false;
  }
  return true;
}

/// Starts listening before the core test begins, so fast results cannot be lost.
/// Returns the next URL only when every configuration in this test failed.
Future<String?> nextSubscriptionAfterTest({
  required SubscriptionGroup group,
  required String currentUrl,
  required Set<String> existingUrls,
  required Stream<OutboundGroup?> results,
  required Future<void> Function() runTest,
  required DateTime started,
  Duration timeout = const Duration(seconds: 90),
}) async {
  final nextUrl = nextSubscriptionUrl([group], currentUrl, existingUrls);
  if (nextUrl == null) {
    await runTest();
    return null;
  }
  final outcome = Completer<bool>();
  final subscription = results.listen(
    (result) {
      if (result == null || result.tag != 'select' || outcome.isCompleted) return;
      final unavailable = allProxiesUnavailable(result, started);
      if (unavailable != null) outcome.complete(unavailable);
    },
    onError: (Object _, StackTrace _) {
      if (!outcome.isCompleted) outcome.complete(false);
    },
  );
  try {
    await runTest();
    final unavailable = await outcome.future.timeout(timeout, onTimeout: () => false);
    return unavailable ? nextUrl : null;
  } finally {
    await subscription.cancel();
  }
}
