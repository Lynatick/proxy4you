import 'package:hiddify/features/profile/model/subscription_list.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Where the subscription lists are kept: a JSON array in the app's preferences. The members
/// themselves are profiles in the database, like every other subscription; only the list (its
/// URL, its order and the member in use) lives here.
class SubscriptionListStore {
  const SubscriptionListStore(this._preferences);

  static const key = 'subscription_lists';

  final SharedPreferences _preferences;

  List<SubscriptionList> read() => SubscriptionList.decodeAll(_preferences.getString(key));

  Future<void> write(List<SubscriptionList> lists) => _preferences.setString(key, SubscriptionList.encodeAll(lists));
}
