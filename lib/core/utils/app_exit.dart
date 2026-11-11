// Leaves the process on the platforms that have one, and does nothing where it cannot be done
// (the web): the screen it is called from stays up there instead of pretending to quit.
export 'app_exit_stub.dart' if (dart.library.io) 'app_exit_io.dart';
