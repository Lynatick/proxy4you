import 'package:hiddify/features/profile/data/profile_data_mapper.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/model/profile_entity.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'active_profile_notifier.g.dart';

@Riverpod(keepAlive: true)
class ActiveProfile extends _$ActiveProfile with AppLogger {
  @override
  Stream<ProfileEntity?> build() {
    loggy.debug("watching active profile");
    return ref.watch(profileDataSourceProvider).watchActiveProfile().map((event) => event?.toEntity());
  }

  /// Shows a profile immediately after its active flag was persisted. The database
  /// stream will confirm the same value shortly afterwards.
  void show(ProfileEntity profile) => state = AsyncData(profile);
}

// TODO: move to specific feature
@Riverpod(keepAlive: true)
Stream<bool> hasAnyProfile(Ref ref) {
  return ref.watch(profileDataSourceProvider).watchProfilesCount().map((event) => event != 0).distinct();
}
