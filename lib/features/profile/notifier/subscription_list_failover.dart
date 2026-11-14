import 'package:hiddify/core/notification/in_app_notification_controller.dart';
import 'package:hiddify/features/connection/model/connection_status.dart';
import 'package:hiddify/features/connection/notifier/connection_notifier.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/model/subscription_list.dart';
import 'package:hiddify/features/profile/notifier/subscription_list_notifier.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'subscription_list_failover.g.dart';

/// Moves a list to its next subscription when the one in use has no working proxy.
///
/// One pass: every member is tried once, in the list's order, and when all of them failed the
/// error is shown and the list stays where it is. The member that is tried is the only one
/// loaded into the core; the rest are ordinary profiles kept for the next update.
@Riverpod(keepAlive: true)
class SubscriptionListFailover extends _$SubscriptionListFailover with AppLogger {
  final Set<String> _attempted = {};
  String? _listId;
  bool _advancing = false;

  @override
  void build() {
    ref.listen(connectionNotifierProvider, (_, next) {
      final status = next.valueOrNull;
      if (status is Connected) {
        _attempted.clear();
        return;
      }
      // all proxies of the member in use timed out: try the next subscription
      if (status is NoAvailableProxies) _advance();
    });
    ref.listen(activeSubscriptionListProvider, (_, next) {
      if (next?.id == _listId) return;
      _listId = next?.id;
      _attempted.clear();
    });
  }

  Future<void> _advance() async {
    if (_advancing) return;
    final list = ref.read(activeSubscriptionListProvider);
    if (list == null || list.memberIds.isEmpty) return;
    final memberId = nextUntriedMember(list.memberIds, list.activeMemberId, _attempted);
    if (memberId == null) {
      _attempted.clear();
      ref.read(inAppNotificationControllerProvider).showErrorToast('Every subscription in the list failed');
      return;
    }
    _attempted.add(memberId);
    _advancing = true;
    try {
      final repository = ref.read(profileRepositoryProvider).requireValue;
      final profile = (await repository.getById(memberId).run()).getOrElse((error) {
        loggy.warning('the next subscription of the list can not be read', error);
        return null;
      });
      if (profile == null) {
        _advancing = false;
        return _advance();
      }
      final activated = await repository.setAsActive(memberId).run();
      if (activated.isLeft()) {
        loggy.warning('the next subscription of the list can not be activated', activated);
      }
      await ref.read(subscriptionListsProvider.notifier).setActiveMember(list.id, memberId);
      await ref.read(connectionNotifierProvider.notifier).reconnect(profile);
    } finally {
      _advancing = false;
    }
  }
}
