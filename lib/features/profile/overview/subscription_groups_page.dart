import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/data/subscription_groups.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';
import 'package:hiddify/features/profile/overview/profiles_notifier.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:uuid/uuid.dart';

class SubscriptionGroupsPage extends ConsumerStatefulWidget {
  const SubscriptionGroupsPage({super.key});

  @override
  ConsumerState<SubscriptionGroupsPage> createState() => _SubscriptionGroupsPageState();
}

class _SubscriptionGroupsPageState extends ConsumerState<SubscriptionGroupsPage> {
  SubscriptionGroupsStore? _store;
  List<SubscriptionGroup> _groups = const [];
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final preferences = await ref.read(sharedPreferencesProvider.future);
    if (!mounted) return;
    final store = SubscriptionGroupsStore(preferences);
    setState(() {
      _store = store;
      _groups = store.read();
    });
  }

  Future<void> _edit(SubscriptionGroup? old, List<RemoteProfileEntity> profiles) async {
    final draft = await showDialog<SubscriptionGroup>(
      context: context,
      builder: (context) => _GroupEditor(old: old, profiles: profiles),
    );
    if (draft == null || _store == null) return;
    setState(() => _busy = true);
    try {
      final known = profiles.map((profile) => profile.url).toSet();
      var imported = 0;
      final failed = <String>{};
      for (final url in draft.urls) {
        if (known.contains(url)) continue;
        final result = await ref.read(profileRepositoryProvider).requireValue.upsertRemote(url, activate: false).run();
        result.match((_) => failed.add(url), (_) => imported++);
      }
      final available = {...known, ...draft.urls.where((url) => !failed.contains(url))};
      final saved = SubscriptionGroup(
        id: draft.id,
        name: draft.name,
        urls: draft.urls.where(available.contains).toList(),
        sourceUrl: old?.sourceUrl,
      );
      if (saved.urls.isEmpty) throw StateError('Не удалось загрузить ни одной подписки');
      final next = [..._groups];
      if (old == null) {
        next.add(saved);
      } else {
        next[next.indexOf(old)] = saved;
      }
      await _store!.write(next);
      ref.read(subscriptionGroupsRevisionProvider.notifier).state++;
      if (!mounted) return;
      setState(() => _groups = next);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            failed.isEmpty
                ? 'Список сохранён. Загружено подписок: $imported'
                : 'Список сохранён; не удалось загрузить ${failed.length} подписок',
          ),
        ),
      );
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$error')));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _delete(SubscriptionGroup group) async {
    final next = [..._groups]..remove(group);
    await _store!.write(next);
    ref.read(subscriptionGroupsRevisionProvider.notifier).state++;
    if (mounted) setState(() => _groups = next);
  }

  @override
  Widget build(BuildContext context) {
    final profiles = ref.watch(profilesNotifierProvider).valueOrNull?.whereType<RemoteProfileEntity>().toList() ?? [];
    return Scaffold(
      appBar: AppBar(title: const Text('Списки подписок')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _store == null || _busy ? null : () => _edit(null, profiles),
        icon: const Icon(Icons.add),
        label: const Text('Создать список'),
      ),
      body: _store == null
          ? const Center(child: CircularProgressIndicator())
          : _groups.isEmpty
          ? const Center(child: Text('Создайте список из подписок или загрузите адреса из файла.'))
          : ListView.builder(
              padding: const EdgeInsets.only(bottom: 88),
              itemCount: _groups.length,
              itemBuilder: (context, index) {
                final group = _groups[index];
                return Card(
                  child: ExpansionTile(
                    title: Text(group.name),
                    subtitle: Text('Подписок: ${group.urls.length}'),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          onPressed: _busy ? null : () => _edit(group, profiles),
                          icon: const Icon(Icons.edit),
                        ),
                        IconButton(
                          onPressed: _busy ? null : () => _delete(group),
                          icon: const Icon(Icons.delete_outline),
                        ),
                      ],
                    ),
                    children: [
                      for (final url in group.urls)
                        if (profiles.where((profile) => profile.url == url).firstOrNull case final profile?)
                          ListTile(
                            title: Text(profile.name),
                            leading: Icon(profile.active ? Icons.radio_button_checked : Icons.radio_button_unchecked),
                            selected: profile.active,
                            onTap: _busy
                                ? null
                                : () async {
                                    await ref.read(profilesNotifierProvider.notifier).selectActiveProfile(profile.id);
                                    if (context.mounted) context.goNamed('home');
                                  },
                          )
                        else
                          const ListTile(title: Text('Подписка больше не найдена')),
                    ],
                  ),
                );
              },
            ),
    );
  }
}

class _GroupEditor extends StatefulWidget {
  const _GroupEditor({required this.old, required this.profiles});
  final SubscriptionGroup? old;
  final List<RemoteProfileEntity> profiles;

  @override
  State<_GroupEditor> createState() => _GroupEditorState();
}

class _GroupEditorState extends State<_GroupEditor> {
  late final _name = TextEditingController(text: widget.old?.name ?? '');
  final _urls = TextEditingController();
  late final _selected = <String>{...?widget.old?.urls};
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    _urls.dispose();
    super.dispose();
  }

  Future<void> _fromFile() async {
    final result = await FilePicker.platform.pickFiles(type: FileType.custom, allowedExtensions: ['txt']);
    if (result == null) return;
    final picked = result.files.single;
    final bytes = picked.bytes ?? await File(picked.path!).readAsBytes();
    final text = decodeTextBytes(bytes);
    setState(() => _urls.text = [_urls.text, text].where((value) => value.isNotEmpty).join('\n'));
  }

  void _save() {
    try {
      final name = _name.text.trim();
      if (name.isEmpty) throw const FormatException('Укажите название списка');
      final urls = {..._selected, ...parseSubscriptionUrls(_urls.text)}.toList();
      if (urls.isEmpty) throw const FormatException('Добавьте хотя бы одну подписку');
      Navigator.pop(context, SubscriptionGroup(id: widget.old?.id ?? const Uuid().v4(), name: name, urls: urls));
    } on FormatException catch (error) {
      setState(() => _error = error.message);
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.old == null ? 'Новый список подписок' : 'Изменить список'),
    content: SizedBox(
      width: 520,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              controller: _name,
              decoration: const InputDecoration(labelText: 'Название'),
            ),
            const SizedBox(height: 12),
            const Text('Выберите подписки'),
            for (final profile in widget.profiles)
              CheckboxListTile(
                dense: true,
                title: Text(profile.name),
                value: _selected.contains(profile.url),
                onChanged: (value) => setState(() {
                  if (value ?? false) {
                    _selected.add(profile.url);
                  } else {
                    _selected.remove(profile.url);
                  }
                }),
              ),
            TextField(
              controller: _urls,
              maxLines: 5,
              decoration: const InputDecoration(labelText: 'Адреса подписок, по одному в строке'),
            ),
            TextButton.icon(
              onPressed: _fromFile,
              icon: const Icon(Icons.file_open),
              label: const Text('Загрузить .txt'),
            ),
            if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Отмена')),
      FilledButton(onPressed: _save, child: const Text('Сохранить')),
    ],
  );
}
