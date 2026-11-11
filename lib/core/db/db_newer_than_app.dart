import 'package:drift/remote.dart';
import 'package:hiddify/core/db/db.dart';

/// A database from a newer version is refused in the migration, but by the time the error
/// reaches the startup code it may have crossed a drift isolate: [DriftRemoteException] (or
/// its text) is what the caller catches, never [DbNewerThanAppException] itself. This finds
/// the real cause either way, so the app can offer "install the newer release" instead of
/// treating the file as broken and offering to wipe it.
DbNewerThanAppException? dbNewerThanAppError(Object error) {
  final cause = error is DriftRemoteException ? error.remoteCause : error;
  if (cause is DbNewerThanAppException) return cause;
  final match = _newerThanApp.firstMatch('$cause');
  if (match == null) return null;
  return DbNewerThanAppException(int.parse(match.group(1)!), int.parse(match.group(2)!));
}

final _newerThanApp = RegExp(r'DbNewerThanAppException\(db schema v(\d+), app schema v(\d+)\)');
