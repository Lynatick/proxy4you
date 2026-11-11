import 'dart:io';

/// Ends the process now. Used only for a start that cannot continue: the providers of a
/// half-started app are not in a state the UI can run on.
void exitApp() => exit(0);
