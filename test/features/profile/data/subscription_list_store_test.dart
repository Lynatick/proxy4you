import 'package:flutter_test/flutter_test.dart';
import 'package:hiddify/features/profile/data/subscription_list_store.dart';
import 'package:hiddify/features/profile/model/subscription_list.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('lists are written to and read from the preferences', () async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    final store = SubscriptionListStore(preferences);

    expect(store.read(), isEmpty);

    final list = SubscriptionList(
      id: 'l1',
      name: 'groups',
      url: 'https://raw.example/groups/all.txt',
      lastUpdate: DateTime.utc(2026, 11, 11),
      memberIds: const ['a', 'b'],
      activeMemberId: 'a',
    );
    await store.write([list]);

    final read = store.read();
    expect(read, hasLength(1));
    expect(read.single.id, 'l1');
    expect(read.single.memberIds, ['a', 'b']);
  });
}
