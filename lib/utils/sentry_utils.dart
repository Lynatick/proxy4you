// The Sentry policy lives in one place: sentryBeforeSend and the two filters are defined in
// `core/analytics/analytics_filter.dart`, next to the options that use them. This file is kept so
// the existing imports (`utils/utils.dart`) keep working.
export 'package:hiddify/core/analytics/analytics_filter.dart' show canLogEvent, canSendEvent, sentryBeforeSend;
