import 'package:flutter/material.dart';
import 'package:gap/gap.dart';
import 'package:go_router/go_router.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/model/constants.dart';
import 'package:hiddify/features/app_update/model/remote_version_entity.dart';
import 'package:hiddify/features/app_update/notifier/app_update_notifier.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

/// A new release is installed from the public download page. Keeping the
/// download in the browser makes the same verified packages available on TV,
/// Android and macOS without requesting an in-app installer permission.
class NewVersionDialog extends HookConsumerWidget with PresLogger {
  NewVersionDialog(this.currentVersion, this.newVersion, {super.key, this.canIgnore = true});

  final String currentVersion;
  final RemoteVersionEntity newVersion;
  final bool canIgnore;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = ref.watch(translationsProvider).requireValue;
    final text = t.dialogs.newVersion;
    final theme = Theme.of(context);
    Future<void> openPage() => UriUtils.tryLaunch(Uri.parse(Constants.downloadPageUrl));

    return AlertDialog(
      title: Text(text.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(text.msg),
          const Gap(8),
          Text.rich(
            TextSpan(
              children: [
                TextSpan(text: "${text.currentVersion} ", style: theme.textTheme.bodySmall),
                TextSpan(text: currentVersion, style: theme.textTheme.labelMedium),
              ],
            ),
          ),
          Text.rich(
            TextSpan(
              children: [
                TextSpan(text: "${text.newVersion} ", style: theme.textTheme.bodySmall),
                TextSpan(text: newVersion.presentVersion, style: theme.textTheme.labelMedium),
              ],
            ),
          ),
        ],
      ),
      actions: [
        if (canIgnore)
          TextButton(
            onPressed: () async {
              await ref.read(appUpdateNotifierProvider.notifier).ignoreRelease(newVersion);
              if (context.mounted) context.pop();
            },
            child: Text(t.common.ignore),
          ),
        TextButton(onPressed: context.pop, child: Text(t.common.later)),
        FilledButton(onPressed: openPage, child: Text(text.updateNow)),
      ],
    );
  }
}
