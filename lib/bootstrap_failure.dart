import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:hiddify/core/utils/app_exit.dart';

/// Shows why the app could not start, and offers a way out.
///
/// Before this screen an error in any startup step left the native splash up forever: the
/// exception went to the log, but no window, no message and no button followed.
Future<void> showBootstrapFailure(Object error, StackTrace stackTrace, {String? quitLabel, String? copyLabel}) async {
  final quit = Completer<void>();
  runApp(
    MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(),
      darkTheme: ThemeData(brightness: Brightness.dark),
      home: BootstrapFailureScreen(
        error: error,
        stackTrace: stackTrace,
        quitLabel: quitLabel,
        copyLabel: copyLabel,
        onQuit: quit.complete,
      ),
    ),
  );
  await quit.future;
  exitApp();
}

class BootstrapFailureScreen extends StatelessWidget {
  const BootstrapFailureScreen({
    super.key,
    required this.error,
    required this.stackTrace,
    required this.onQuit,
    this.quitLabel,
    this.copyLabel,
  });

  final Object error;
  final StackTrace stackTrace;
  final VoidCallback onQuit;
  final String? quitLabel;
  final String? copyLabel;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final details = '$error\n\n$stackTrace';
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Icon(Icons.error_outline_rounded, size: 48, color: theme.colorScheme.error),
                const SizedBox(height: 16),
                Text('Proxy4You could not start', style: theme.textTheme.headlineSmall, textAlign: TextAlign.center),
                const SizedBox(height: 8),
                Text(
                  'The app stopped while starting. The error below is the reason; the logs hold the rest.',
                  style: theme.textTheme.bodyMedium,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 16),
                Container(
                  constraints: const BoxConstraints(maxHeight: 240),
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: SingleChildScrollView(
                    child: SelectableText(
                      details,
                      textDirection: TextDirection.ltr,
                      style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    TextButton.icon(
                      onPressed: () async {
                        await Clipboard.setData(ClipboardData(text: details));
                      },
                      icon: const Icon(Icons.copy_rounded),
                      label: Text(copyLabel ?? 'Copy error'),
                    ),
                    const Spacer(),
                    FilledButton(onPressed: onQuit, child: Text(quitLabel ?? 'Quit')),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
