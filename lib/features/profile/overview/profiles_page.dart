import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/model/failures.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/core/router/bottom_sheets/bottom_sheets_notifier.dart';
import 'package:hiddify/core/router/dialog/dialog_notifier.dart';
import 'package:hiddify/features/profile/add/phone_import/phone_import_notifier.dart';
import 'package:hiddify/features/profile/data/subscription_groups.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';
import 'package:hiddify/features/profile/notifier/active_profile_notifier.dart';
import 'package:hiddify/features/profile/notifier/profiles_update_notifier.dart';
import 'package:hiddify/features/profile/overview/profiles_notifier.dart';
import 'package:hiddify/features/profile/overview/subscription_groups_page.dart';
import 'package:hiddify/features/profile/widget/profile_tile.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class ProfilesPage extends HookConsumerWidget {
  const ProfilesPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = ref.watch(translationsProvider).requireValue;
    final asyncProfiles = ref.watch(profilesNotifierProvider);

    ref.listen(hasAnyProfileProvider, (_, next) {
      if (next.value == false) {
        context.goNamed('home');
      }
    });

    return Scaffold(
      appBar: AppBar(
        title: Text(t.pages.profiles.title),
        actions: [
          IconButton(
            onPressed: () => ref.read(foregroundProfilesUpdateNotifierProvider.notifier).trigger(),
            icon: const Icon(Icons.sync_rounded),
            tooltip: t.pages.profiles.updateSubscriptions,
          ),
          IconButton(
            onPressed: () => ref.read(dialogNotifierProvider.notifier).showSortProfiles(),
            icon: const Icon(Icons.sort_rounded),
            tooltip: t.common.sort,
          ),
          const Gap(8),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () async => await ref.read(bottomSheetsNotifierProvider.notifier).showAddProfile(),
        label: Text(t.pages.profiles.add),
        icon: const Icon(Icons.add_rounded),
      ),
      body: asyncProfiles.when(
        data: (data) => _ProfilesContent(profiles: data),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stackTrace) => Text(t.presentShortError(error)),
      ),
    );
  }
}

class _ProfilesContent extends ConsumerStatefulWidget {
  const _ProfilesContent({required this.profiles});

  final List<ProfileEntity> profiles;

  @override
  ConsumerState<_ProfilesContent> createState() => _ProfilesContentState();
}

class _ProfilesContentState extends ConsumerState<_ProfilesContent> {
  SubscriptionGroupsStore? _store;
  List<SubscriptionGroup> _groups = const [];
  String? _selectedGroupId;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _reloadGroups();
  }

  Future<void> _reloadGroups() async {
    final preferences = await ref.read(sharedPreferencesProvider.future);
    if (!mounted) return;
    final store = SubscriptionGroupsStore(preferences);
    setState(() {
      _store = store;
      _groups = store.read();
      _selectedGroupId = store.selectedGroupId;
    });
  }

  Future<void> _editGroups() async {
    await Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const SubscriptionGroupsPage()));
    await _reloadGroups();
  }

  Future<void> _chooseGroup(String? id) async {
    final store = _store;
    if (store == null || _busy) return;
    setState(() => _busy = true);
    try {
      if (id != null) {
        final group = _groups.firstWhere((group) => group.id == id);
        final profiles = widget.profiles.whereType<RemoteProfileEntity>().toList();
        final active = await ref.read(activeProfileProvider.future);
        final current = active is RemoteProfileEntity && group.urls.contains(active.url) ? active : null;
        final selected =
            current ?? [for (final url in group.urls) ...profiles.where((profile) => profile.url == url)].firstOrNull;
        if (selected == null) throw StateError('В списке не осталось доступных подписок');
        await store.selectGroup(id);
        if (selected.id != active?.id) {
          await ref.read(profilesNotifierProvider.notifier).selectActiveProfile(selected.id);
        }
      } else {
        await store.selectGroup(null);
      }
      if (mounted) setState(() => _selectedGroupId = id);
      ref.read(subscriptionGroupsRevisionProvider.notifier).state++;
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$error')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chooseProfile(String id) async {
    final store = _store;
    if (store == null || _busy || _selectedGroupId != null) return;
    await ref.read(profilesNotifierProvider.notifier).selectActiveProfile(id);
  }

  @override
  Widget build(BuildContext context) {
    ref.listen(subscriptionGroupsRevisionProvider, (_, _) => _reloadGroups());
    final remote = widget.profiles.whereType<RemoteProfileEntity>().toList();
    final active = widget.profiles.where((profile) => profile.active).firstOrNull;
    final selectedProfileId = active is RemoteProfileEntity ? active.id : '';
    final isTv = ref.watch(isAndroidTvProvider).valueOrNull ?? false;
    return Center(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: isTv ? 900 : double.infinity),
        child: FocusTraversalGroup(
          child: ListView(
            padding: EdgeInsets.all(isTv ? 28 : 12).copyWith(bottom: 84),
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    children: [
                      Row(
                        children: [
                          const Expanded(child: Text('Список подписок')),
                          SizedBox(
                            width: 260,
                            child: DropdownButton<String>(
                              isExpanded: true,
                              value: _selectedGroupId ?? '',
                              items: [
                                const DropdownMenuItem(value: '', child: Text('Нет')),
                                for (final group in _groups)
                                  DropdownMenuItem(
                                    value: group.id,
                                    child: Text(group.name, overflow: TextOverflow.ellipsis),
                                  ),
                              ],
                              onChanged: _store == null || _busy
                                  ? null
                                  : (value) => _chooseGroup(value == '' ? null : value),
                            ),
                          ),
                          IconButton(
                            onPressed: _busy ? null : _editGroups,
                            icon: const Icon(Icons.edit_note),
                            tooltip: 'Добавить или изменить список',
                          ),
                        ],
                      ),
                      Row(
                        children: [
                          const Expanded(child: Text('Выбор подписки')),
                          SizedBox(
                            width: 260,
                            child: DropdownButton<String>(
                              isExpanded: true,
                              value: remote.any((profile) => profile.id == selectedProfileId) ? selectedProfileId : '',
                              items: [
                                const DropdownMenuItem(value: '', child: Text('Не выбрана')),
                                for (final profile in remote)
                                  DropdownMenuItem(
                                    value: profile.id,
                                    child: Text(profile.name, overflow: TextOverflow.ellipsis),
                                  ),
                              ],
                              onChanged: _store == null || _busy || _selectedGroupId != null
                                  ? null
                                  : (value) {
                                      if (value != null && value.isNotEmpty) _chooseProfile(value);
                                    },
                            ),
                          ),
                          const SizedBox(width: 48),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              const Gap(12),
              for (final group in _groups) ...[
                Card(
                  child: ListTile(
                    leading: Icon(_selectedGroupId == group.id ? Icons.radio_button_checked : Icons.playlist_play),
                    title: Text(group.name),
                    subtitle: Text('Список подписок · ${group.urls.length}'),
                    selected: _selectedGroupId == group.id,
                    onTap: _busy ? null : () => _chooseGroup(group.id),
                    trailing: IconButton(onPressed: _busy ? null : _editGroups, icon: const Icon(Icons.edit)),
                  ),
                ),
                const Gap(12),
              ],
              for (final profile in widget.profiles) ...[
                ProfileTile(
                  key: ValueKey(profile.id),
                  profile: profile,
                  beforeSelect: () async {
                    await _store?.selectGroup(null);
                    ref.read(subscriptionGroupsRevisionProvider.notifier).state++;
                    if (mounted) setState(() => _selectedGroupId = null);
                  },
                ),
                const Gap(12),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
