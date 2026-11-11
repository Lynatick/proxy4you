import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/bootstrap_failure.dart';

void main() {
  testWidgets('a start that failed shows its error and a way out', (tester) async {
    var quit = false;

    await tester.pumpWidget(
      MaterialApp(
        home: BootstrapFailureScreen(
          error: StateError('database is locked'),
          stackTrace: StackTrace.current,
          quitLabel: 'Quit',
          copyLabel: 'Copy error',
          onQuit: () => quit = true,
        ),
      ),
    );

    expect(find.text('Proxy4You could not start'), findsOneWidget);
    expect(find.textContaining('database is locked'), findsWidgets);
    expect(find.text('Copy error'), findsOneWidget);

    await tester.tap(find.text('Quit'));
    expect(quit, isTrue);
  });
}
