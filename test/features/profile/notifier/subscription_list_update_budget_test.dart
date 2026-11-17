import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/profile/notifier/subscription_list_notifier.dart';

void main() {
  test('the update budget and concurrency stay bounded', () {
    // A list names up to ProfileListParser.maxMembers subscriptions, each a download of its own:
    // without a budget the update button would spin for tens of minutes.
    expect(SubscriptionLists.updateBudget.inSeconds, lessThanOrEqualTo(300));
    expect(SubscriptionLists.updateConcurrency, greaterThan(1));
    expect(SubscriptionLists.updateConcurrency, lessThanOrEqualTo(8));
  });
}
