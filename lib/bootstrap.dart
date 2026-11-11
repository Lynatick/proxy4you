import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_displaymode/flutter_displaymode.dart';
import 'package:flutter_native_splash/flutter_native_splash.dart';
import 'package:hiddify/bootstrap_failure.dart';
import 'package:hiddify/core/analytics/analytics_controller.dart';
import 'package:hiddify/core/app_info/app_info_provider.dart';
import 'package:hiddify/core/db/db.dart';
import 'package:hiddify/core/db/db_error_prompt.dart';
import 'package:hiddify/core/db/db_newer_than_app.dart';
import 'package:hiddify/core/db/db_recovery.dart';
import 'package:hiddify/core/db/provider/db_providers.dart';
import 'package:hiddify/core/directories/directories_provider.dart';
import 'package:hiddify/core/localization/locale_preferences.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/core/logger/logger.dart';
import 'package:hiddify/core/logger/logger_setup.dart';
import 'package:hiddify/core/model/environment.dart';
import 'package:hiddify/core/preferences/general_preferences.dart';
import 'package:hiddify/core/preferences/preferences_migration.dart';
import 'package:hiddify/core/preferences/preferences_provider.dart';
import 'package:hiddify/core/utils/app_exit.dart';
import 'package:hiddify/features/app/widget/app.dart';
import 'package:hiddify/features/auto_start/notifier/auto_start_notifier.dart';
import 'package:hiddify/features/chain/model/chain_enum.dart';
import 'package:hiddify/features/chain/notifier/chain_profile_notifier.dart';

import 'package:hiddify/features/connection/notifier/connection_notifier.dart';
import 'package:hiddify/features/log/data/log_data_providers.dart';
import 'package:hiddify/features/profile/data/profile_data_providers.dart';
import 'package:hiddify/features/profile/notifier/active_profile_notifier.dart';
import 'package:hiddify/features/proxy/active/active_proxy_notifier.dart';
import 'package:hiddify/features/settings/data/config_option_repository.dart';
import 'package:hiddify/features/system_tray/notifier/system_tray_notifier.dart';
import 'package:hiddify/features/window/notifier/window_notifier.dart';
import 'package:hiddify/hiddifycore/hiddify_core_service_provider.dart';
import 'package:hiddify/riverpod_observer.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';
import 'package:sentry_flutter/sentry_flutter.dart';

Future<void> lazyBootstrap(WidgetsBinding widgetsBinding, Environment env) async {
  if (!kIsWeb) {
    FlutterNativeSplash.preserve(widgetsBinding: widgetsBinding);
  }
  // phase 1 — console and the in-memory history, before the folders are known
  logStart();
  FlutterError.onError = Logger.logFlutterError;
  WidgetsBinding.instance.platformDispatcher.onError = Logger.logPlatformDispatcherError;

  final stopWatch = Stopwatch()..start();

  final container = ProviderContainer(
    overrides: [environmentProvider.overrideWithValue(env)],
    // every provider change with its value: far too much to log by default
    observers: [if (const bool.fromEnvironment('riverpod_log')) RiverpodObserver()],
  );

  try {
    await _init("directories", () => container.read(appDirectoriesProvider.future));
    // phase 2 — the folders exist, so app.log can be opened
    logAddFile(container.read(logPathResolverProvider).appFile().path);

    final appInfo = await _safeInit("app info", () => container.read(appInfoProvider.future));
    await _init("preferences", () => container.read(sharedPreferencesProvider.future));
    // The ring starts at a safe default and only now learns the size the user
    // picked, since nothing could be read from disk before this point.
    logRing.capacity = container.read(Preferences.logBufferSize);

    final enableAnalytics =
        await _safeInit("analytics controller", () => container.read(analyticsControllerProvider.future)) ?? false;
    if (enableAnalytics) {
      await _safeInit("analytics", () => container.read(analyticsControllerProvider.notifier).enableAnalytics());
    }

    await _init(
      "preferences migration",
      () => migratePreferences(container.read(sharedPreferencesProvider).requireValue, env: env),
    );

    // The log level decides this now. There is no second debug switch to keep in
    // sync, and the core reaches the same conclusion from the same value.
    final debug = kDebugMode || container.read(ConfigOptions.logLevel).isVerbose;

    if (PlatformUtils.isDesktop) {
      await _init("window controller", () => container.read(windowNotifierProvider.future));

      final silentStart = container.read(Preferences.silentStart);
      Logger.bootstrap.debug("silent start [${silentStart ? "Enabled" : "Disabled"}]");
      if (!silentStart) {
        await container.read(windowNotifierProvider.notifier).show(focus: false);
      } else {
        Logger.bootstrap.debug("silent start, remain hidden accessible via tray");
      }
      await _safeInit("auto start service", () => container.read(autoStartNotifierProvider.future));
    }
    // phase 3 — a log file is worth its cost on desktop, or while debugging.
    // On a phone in release it only burns storage nobody reads.
    await _safeInit("logging", () async => logFinish(keepFile: debug || PlatformUtils.isDesktop));

    // marks where this run begins, since the file now spans several
    logMarkRun(appInfo?.format() ?? "version unknown");
    Logger.bootstrap.info(appInfo?.format() ?? "version unknown");

    await _init("translations", () => container.read(translationsProvider.future));

    await _init("database", () => _openDatabase(container));

    await _init("profile repository", () => container.read(profileRepositoryProvider.future));

    await _safeInit("active profile", () => container.read(activeProfileProvider.future), timeout: 1000);
    await _safeInit(
      "chain profile extra security",
      () => container.read(chainProfileNotifierProvider(ChainType.extraSecurity).future),
    );
    await _safeInit(
      "chain profile unblocker",
      () => container.read(chainProfileNotifierProvider(ChainType.unblocker).future),
    );
    await _safeInit("hiddify-core", () => container.read(hiddifyCoreServiceProvider).init());

    // Eagerly listen to activeProxyNotifierProvider to force synchronous evaluation in microtasks,
    // avoiding lazy build-phase flushes and sibling dependency collisions on the Home page.
    container.listen(activeProxyNotifierProvider, (previous, next) {});

    if (!kIsWeb) {
      if (PlatformUtils.isDesktop) {
        await _safeInit(
          "restore previous connection",
          () => container.read(connectionNotifierProvider.notifier).restoreConnectionOnStartup(),
          timeout: 5000,
        );
        await _safeInit("system tray", () => container.read(systemTrayNotifierProvider.future), timeout: 1000);
      }

      if (PlatformUtils.isAndroid) {
        await _safeInit("android display mode", () async {
          await FlutterDisplayMode.setHighRefreshRate();
        });
      }
    }

    Logger.bootstrap.info("bootstrap took [${stopWatch.elapsedMilliseconds}ms]");
    stopWatch.stop();
  } catch (error, stackTrace) {
    await _failedToStart(container, error, stackTrace);
    return;
  }

  runApp(
    UncontrolledProviderScope(
      container: container,
      child: SentryUserInteractionWidget(child: const App()),
    ),
  );

  if (!kIsWeb) {
    FlutterNativeSplash.remove();
  }
  // SentryFlutter.s(DateTime.now().toUtc());
}

/// Opens the database now, so a database the app cannot read is caught here and
/// not by the first screen that lists profiles. One written by a newer version
/// (after a downgrade) or broken (a failed migration, a damaged file) is left
/// alone unless the user wipes it; the profiles that can still be read are then
/// added back to the fresh database.
Future<void> _openDatabase(ProviderContainer container) async {
  Future<void> open() => container.read(dbProvider).customSelect('SELECT 1').get();
  try {
    await open();
    return;
  } catch (e, stackTrace) {
    // The database is opened through a drift isolate: the migration's error reaches this
    // code as a remote error, so `on DbNewerThanAppException` alone never matched and a
    // database of a newer release was offered as a broken one, to be wiped.
    final newer = dbNewerThanAppError(e);
    if (newer != null) {
      Logger.bootstrap.warning("database is newer than this app: $newer");
      await _askWipeDb(container, newer);
    } else {
      if (kIsWeb) rethrow;
      Logger.bootstrap.error("database can not be opened", e, stackTrace);
      await _askWipeDb(container, e);
    }
  }

  Logger.bootstrap.info("wiping the database, as the user chose");
  try {
    await container.read(dbProvider).close().timeout(const Duration(seconds: 3));
  } catch (e, stackTrace) {
    // Deleting the file while a handle still writes to it corrupts it on every OS (and two
    // files with one name on POSIX). Stop here and let the user start again instead.
    Logger.bootstrap.error("closing the database failed; not deleting it", e, stackTrace);
    rethrow;
  }
  container.invalidate(dbProvider);
  var salvage = DbSalvage.empty;
  try {
    salvage = await salvageDb(await Db.file(), configsDir: container.read(profilePathResolverProvider).directory);
    Logger.bootstrap.info(
      "salvaged ${salvage.profiles.length} profiles and ${salvage.appProxies.length} app proxy entries",
    );
  } catch (e, stackTrace) {
    Logger.bootstrap.warning("salvaging the database failed", e, stackTrace);
  }
  await Db.deleteFiles();
  await open();
  try {
    await restoreSalvage(container.read(dbProvider), salvage);
  } catch (e, stackTrace) {
    Logger.bootstrap.error("adding back the salvaged profiles failed", e, stackTrace);
  }
}

Future<void> _askWipeDb(ProviderContainer container, Object error) async {
  if (!kIsWeb) FlutterNativeSplash.remove();
  if (PlatformUtils.isDesktop) await container.read(windowNotifierProvider.notifier).show();
  await askWipeDb(
    container.read(localePreferencesProvider),
    container.read(translationsProvider).requireValue,
    error,
    onQuit: exitApp,
  );
}

Future<T> _init<T>(String name, Future<T> Function() initializer, {int? timeout}) async {
  final stopWatch = Stopwatch()..start();
  Logger.bootstrap.info("initializing [$name]");
  Future<T> func() => timeout != null ? initializer().timeout(Duration(milliseconds: timeout)) : initializer();
  try {
    final result = await func();
    Logger.bootstrap.debug("[$name] initialized in ${stopWatch.elapsedMilliseconds}ms");
    return result;
  } catch (e, stackTrace) {
    Logger.bootstrap.error("[$name] error initializing", e, stackTrace);
    rethrow;
  } finally {
    stopWatch.stop();
  }
}

Future<T?> _safeInit<T>(String name, Future<T> Function() initializer, {int? timeout}) async {
  try {
    return await _init(name, initializer, timeout: timeout);
  } catch (e) {
    return null;
  }
}

/// The app could not finish starting. Show why, with a way out, instead of leaving the native
/// splash up: that was the only thing a failed step produced before.
Future<void> _failedToStart(ProviderContainer container, Object error, StackTrace stackTrace) async {
  Logger.bootstrap.error("bootstrap failed", error, stackTrace);
  if (!kIsWeb) FlutterNativeSplash.remove();
  if (PlatformUtils.isDesktop) {
    try {
      await container.read(windowNotifierProvider.notifier).show();
    } catch (e) {
      Logger.bootstrap.debug("could not show the window for the failure", e);
    }
  }
  Translations? translations;
  try {
    translations = container.read(translationsProvider).requireValue;
  } catch (_) {
    // the translations were what failed: the screen falls back to its own labels
  }
  await showBootstrapFailure(
    error,
    stackTrace,
    quitLabel: translations?.common.quit,
    copyLabel: translations?.dialogs.dbBroken.copyError,
  );
}
