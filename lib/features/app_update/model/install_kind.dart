import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:hiddify/core/model/environment.dart';
import 'package:hiddify/features/app_update/model/remote_version_entity.dart';

/// How this copy of the app was installed, which says how it updates itself.
enum InstallKind {
  /// a store's (Google Play, the App Store, the Microsoft Store): the store updates it
  store,

  /// an APK from the releases: the update is installed by the system installer
  androidApk,

  /// Windows, by the setup: the new setup installs over it
  windowsSetup,

  /// Windows, the portable zip: it can not replace itself while it runs
  windowsPortable,

  /// Linux, an AppImage: the new AppImage takes the place of the running one
  linuxAppImage,

  /// Linux, the Debian package: the system's package installer installs the new one
  linuxDeb,

  /// Linux, some other way
  linuxOther,

  /// macOS, outside the App Store: the new disk image opens
  macOS;

  /// What [release] runs as here; [appImage] is the AppImage that runs this copy, if one does.
  static InstallKind detect(Release release, {String? appImage, bool? debianPackage}) {
    if (!release.allowCustomUpdateChecker || kIsWeb) return store;
    if (Platform.isAndroid) return androidApk;
    if (Platform.isWindows) return Environment.isPortable ? windowsPortable : windowsSetup;
    if (Platform.isMacOS) return macOS;
    if (Platform.isLinux) {
      if ((appImage ?? Platform.environment['APPIMAGE'] ?? '').isNotEmpty) return linuxAppImage;
      // the package's file list, which dpkg keeps for each package it installed
      if (debianPackage ?? File('/var/lib/dpkg/info/hiddify.list').existsSync()) return linuxDeb;
      return linuxOther;
    }
    return store;
  }

  /// Whether the app downloads the update and installs it, not only points to it.
  bool get installsItself => switch (this) {
    androidApk || windowsSetup || linuxAppImage || linuxDeb || macOS => true,
    store || windowsPortable || linuxOther => false,
  };

  /// The release file this kind installs from; for an APK, the one built for the device's first
  /// supported ABI ([abis]), else the universal one; on Linux, the one for the CPU ([arm64]), never
  /// the other CPU's.
  ReleaseAsset? assetIn(RemoteVersionEntity release, {List<String> abis = const [], bool arm64 = false}) =>
      switch (this) {
        androidApk =>
          release.assetEndingWith(switch (abis.firstOrNull) {
                'arm64-v8a' => '-arm64-v8a.apk',
                'armeabi-v7a' => '-armeabi-v7a.apk',
                'x86_64' => '-x86_64.apk',
                _ => '-universal.apk',
              }) ??
              release.asset(switch (abis.firstOrNull) {
                'arm64-v8a' => 'Hiddify-Android-arm64.apk',
                'armeabi-v7a' => 'Hiddify-Android-arm7.apk',
                'x86_64' => 'Hiddify-Android-x86_64.apk',
                _ => 'Hiddify-Android-universal.apk',
              }) ??
              release.assetEndingWith('-universal.apk') ??
              release.asset('Hiddify-Android-universal.apk'),
        windowsSetup => release.assetEndingWith('-Setup-x64.exe'),
        linuxAppImage =>
          release.assetEndingWith(arm64 ? '-arm64-AppImage.AppImage' : '-x64-AppImage.AppImage') ??
              release.asset('Hiddify-Linux-${arm64 ? 'arm64' : 'x64'}-AppImage.AppImage') ??
              release.asset('Hiddify-Linux-${arm64 ? 'arm64' : 'x64'}.AppImage'),
        linuxDeb => release.assetEndingWith(arm64 ? '-arm64.deb' : '-x64.deb'),
        macOS => release.assetEndingWith('-macos27-arm64.dmg') ?? release.assetEndingWith('.dmg'),
        store || windowsPortable || linuxOther => null,
      };
}
