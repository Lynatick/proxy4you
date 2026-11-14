import 'package:flutter/material.dart';
import 'package:hiddify/core/notification/in_app_notification_controller.dart';
import 'package:hiddify/core/preferences/general_preferences.dart';
import 'package:hiddify/features/profile/notifier/subscription_list_notifier.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

/// The subscription list in use, next to the active subscription on the home screen.
///
/// "No list" lets the active subscription connect, as before. A list makes its current member
/// the connection and its next members the fallback; while one is selected, the subscription
/// selector is disabled (a subscription is picked by clearing the list first).
class SubscriptionListSelector extends ConsumerWidget {
  const SubscriptionListSelector({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final lists = ref.watch(subscriptionListsProvider);
    final activeId = ref.watch(Preferences.activeSubscriptionList);
    final active = ref.watch(activeSubscriptionListProvider);
    final theme = Theme.of(context);
    final value = lists.any((list) => list.id == activeId) ? activeId : '';

    // The same card the active subscription sits in: a 48px leading slot, a divider, the name,
    // and the actions on the right.
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Card(
        margin: EdgeInsets.zero,
        elevation: active == null ? 1 : 0,
        shape: RoundedRectangleBorder(
          side: active == null ? BorderSide.none : BorderSide(color: theme.colorScheme.outline),
          borderRadius: BorderRadius.circular(12),
        ),
        child: IntrinsicHeight(
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 48),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  width: 48,
                  child: Center(child: Icon(Icons.playlist_play_rounded, color: theme.colorScheme.primary)),
                ),
                VerticalDivider(width: 1, color: theme.colorScheme.outlineVariant),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<String>(
                        isExpanded: true,
                        isDense: true,
                        value: value,
                        style: theme.textTheme.titleMedium?.copyWith(
                          color: theme.colorScheme.onSurface,
                          fontWeight: FontWeight.w600,
                        ),
                        items: [
                          const DropdownMenuItem(value: '', child: Text('No subscription list')),
                          for (final list in lists)
                            DropdownMenuItem(
                              value: list.id,
                              child: Text('${list.name} (${list.memberIds.length})', overflow: TextOverflow.ellipsis),
                            ),
                        ],
                        onChanged: (next) async {
                          await ref.read(Preferences.activeSubscriptionList.notifier).update(next ?? '');
                          final id = next ?? '';
                          if (id.isNotEmpty) await ref.read(subscriptionListsProvider.notifier).activate(id);
                        },
                      ),
                    ),
                  ),
                ),
                IconButton(
                  tooltip: 'Add a subscription list',
                  icon: const Icon(Icons.add_rounded),
                  onPressed: () => _add(context, ref),
                ),
                if (active != null)
                  IconButton(
                    tooltip: 'Update the list and its subscriptions',
                    icon: const Icon(Icons.refresh_rounded),
                    onPressed: () async {
                      final ok = await ref.read(subscriptionListsProvider.notifier).update(active.id);
                      if (context.mounted) {
                        _toast(
                          ref,
                          ok ? 'The list and its subscriptions were updated' : 'The list could not be updated',
                        );
                      }
                    },
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final controller = TextEditingController();
    final url = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Add a subscription list'),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(labelText: 'URL of the list (one subscription a line)'),
          onSubmitted: (value) => Navigator.of(context).pop(value),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(context).pop(controller.text), child: const Text('Add')),
        ],
      ),
    );
    controller.dispose();
    final trimmed = url?.trim() ?? '';
    if (trimmed.isEmpty) return;
    final id = await ref.read(subscriptionListsProvider.notifier).add(trimmed);
    if (id == null) {
      _toast(ref, 'The list has no usable subscription');
      return;
    }
    await ref.read(Preferences.activeSubscriptionList.notifier).update(id);
    await ref.read(subscriptionListsProvider.notifier).activate(id);
    _toast(ref, 'The subscription list was added');
  }

  void _toast(WidgetRef ref, String message) => ref.read(inAppNotificationControllerProvider).showInfoToast(message);
}
