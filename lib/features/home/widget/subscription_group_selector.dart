import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:hiddify/core/http_client/http_client_provider.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/data/subscription_groups.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';
import 'package:hiddify/features/profile/notifier/active_profile_notifier.dart';
import 'package:hiddify/features/profile/notifier/profile_notifier.dart';
import 'package:hiddify/features/profile/overview/profiles_notifier.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class HomeSubscriptionGroupSelector extends ConsumerStatefulWidget {
  const HomeSubscriptionGroupSelector({super.key});

  @override
  ConsumerState<HomeSubscriptionGroupSelector> createState() => _HomeSubscriptionGroupSelectorState();
}

class _HomeSubscriptionGroupSelectorState extends ConsumerState<HomeSubscriptionGroupSelector> {
  bool _busy = false;

  Future<void> _choose(String? id) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final preferences = await ref.read(sharedPreferencesProvider.future);
      final store = SubscriptionGroupsStore(preferences);
      if (id == null) {
        await store.selectGroup(null);
      } else {
        final group = store.read().firstWhere((group) => group.id == id);
        final profiles = (await ref.read(profilesNotifierProvider.future)).whereType<RemoteProfileEntity>().toList();
        final active = await ref.read(activeProfileProvider.future);
        final current = active is RemoteProfileEntity && group.urls.contains(active.url) ? active : null;
        final selected =
            current ?? [for (final url in group.urls) ...profiles.where((profile) => profile.url == url)].firstOrNull;
        if (selected == null) throw StateError('В списке не осталось доступных подписок');
        await store.selectGroup(id);
        if (selected.id != active?.id) {
          await ref.read(profilesNotifierProvider.notifier).selectActiveProfile(selected.id);
        }
      }
      ref.read(subscriptionGroupsRevisionProvider.notifier).state++;
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$error')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refreshSelectedGroup(SubscriptionGroupsStore store, SubscriptionGroup group) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      var urls = group.urls;
      if (group.sourceUrl case final sourceUrl?) {
        final response = await ref.read(httpClientProvider).get<String>(sourceUrl, responseType: ResponseType.plain);
        final parsed = response.data == null ? null : parseRemoteSubscriptionList(response.data!);
        if (parsed == null) throw const FormatException('Файл больше не содержит список подписок');
        urls = parsed;
      }

      final profiles = (await ref.read(profilesNotifierProvider.future)).whereType<RemoteProfileEntity>().toList();
      final profilesByUrl = {for (final profile in profiles) profile.url: profile};
      final retainedUrls = <String>[];
      var updated = 0;
      var failed = 0;

      // Keep source order and update one subscription at a time: the native
      // parser and active-profile reconnect use the same core instance.
      for (final url in urls) {
        final profile = profilesByUrl[url];
        if (profile != null) {
          await ref.read(updateProfileNotifierProvider(profile.id).notifier).updateProfile(profile, isBulk: true);
          retainedUrls.add(url);
          if (ref.read(updateProfileNotifierProvider(profile.id)).hasError) {
            failed++;
          } else {
            updated++;
          }
          continue;
        }

        final result = await ref.read(profileRepositoryProvider).requireValue.upsertRemote(url, activate: false).run();
        result.match((_) => failed++, (_) {
          retainedUrls.add(url);
          updated++;
        });
      }
      if (retainedUrls.isEmpty) throw StateError('Не удалось обновить ни одной подписки из списка');

      final refreshed = SubscriptionGroup(
        id: group.id,
        name: group.name,
        urls: retainedUrls,
        sourceUrl: group.sourceUrl,
      );
      final groups = store.read();
      final index = groups.indexWhere((candidate) => candidate.id == group.id);
      if (index < 0) throw StateError('Список подписок больше не существует');
      final next = [...groups]..[index] = refreshed;
      await store.write(next);

      final active = await ref.read(activeProfileProvider.future);
      if (active is! RemoteProfileEntity || !retainedUrls.contains(active.url)) {
        final refreshedProfiles = (await ref.read(
          profilesNotifierProvider.future,
        )).whereType<RemoteProfileEntity>().toList();
        final first = [
          for (final url in retainedUrls) ...refreshedProfiles.where((profile) => profile.url == url),
        ].firstOrNull;
        if (first != null) await ref.read(profilesNotifierProvider.notifier).selectActiveProfile(first.id);
      }

      ref.read(subscriptionGroupsRevisionProvider.notifier).state++;
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              failed == 0 ? 'Список и подписки обновлены: $updated' : 'Обновлено: $updated; с ошибкой: $failed',
            ),
          ),
        );
      }
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$error')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.watch(subscriptionGroupsRevisionProvider);
    final preferences = ref.watch(sharedPreferencesProvider).valueOrNull;
    final store = preferences == null ? null : SubscriptionGroupsStore(preferences);
    final groups = store?.read() ?? const <SubscriptionGroup>[];
    final selected = store?.selectedGroupId ?? '';
    SubscriptionGroup? selectedGroup;
    for (final group in groups) {
      if (group.id == selected) selectedGroup = group;
    }
    final theme = Theme.of(context);

    Widget selectedValue(SubscriptionGroup? group) => Align(
      alignment: AlignmentDirectional.centerStart,
      child: Text(
        group == null ? 'Список подписок: Нет' : 'Список подписок: ${group.name} · ${group.urls.length}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: theme.textTheme.titleMedium,
      ),
    );

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Card(
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          side: selectedGroup == null ? BorderSide.none : BorderSide(color: theme.colorScheme.outline),
          borderRadius: BorderRadius.circular(12),
        ),
        elevation: selectedGroup == null ? 1 : 0,
        child: IntrinsicHeight(
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 52),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                SizedBox(
                  width: 48,
                  child: selectedGroup == null
                      ? const Center(child: Icon(Icons.playlist_play_rounded))
                      : Semantics(
                          button: true,
                          label: 'Обновить список подписок',
                          child: Tooltip(
                            message: 'Обновить список подписок',
                            child: InkWell(
                              borderRadius: const BorderRadius.horizontal(left: Radius.circular(12)),
                              onTap: _busy || store == null ? null : () => _refreshSelectedGroup(store, selectedGroup!),
                              child: Center(
                                child: _busy
                                    ? const SizedBox.square(
                                        dimension: 20,
                                        child: CircularProgressIndicator(strokeWidth: 2),
                                      )
                                    : const Icon(Icons.sync_rounded),
                              ),
                            ),
                          ),
                        ),
                ),
                if (selectedGroup != null)
                  VerticalDivider(width: 1, color: theme.colorScheme.outline)
                else
                  const SizedBox(width: 1),
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    child: Semantics(
                      label: 'Выбор списка подписок',
                      child: DropdownButtonHideUnderline(
                        child: DropdownButton<String>(
                          isExpanded: true,
                          borderRadius: BorderRadius.circular(12),
                          focusColor: theme.colorScheme.primary.withValues(alpha: .20),
                          value: selectedGroup?.id ?? '',
                          selectedItemBuilder: (context) => [
                            selectedValue(null),
                            for (final group in groups) selectedValue(group),
                          ],
                          items: [
                            const DropdownMenuItem(value: '', child: Text('Нет')),
                            for (final group in groups)
                              DropdownMenuItem(
                                value: group.id,
                                child: Text(group.name, overflow: TextOverflow.ellipsis),
                              ),
                          ],
                          onChanged: store == null || _busy ? null : (value) => _choose(value == '' ? null : value),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
