import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:dio/io.dart';
import 'package:dio_smart_retry/dio_smart_retry.dart';

import 'package:hiddify/utils/custom_loggers.dart';

class DioHttpClient with InfraLogger {
  final Map<String, Dio> _dio = {};
  DioHttpClient({required Duration timeout, required this.userAgent, required bool debug}) {
    for (final mode in ["proxy", "direct", "both"]) {
      _dio[mode] = Dio(
        BaseOptions(
          connectTimeout: timeout,
          sendTimeout: timeout,
          receiveTimeout: timeout,
          headers: {"User-Agent": userAgent},
        ),
      );
      // First, so the retries of a request carry it too: no request leaves with the user agent of
      // dart:io ("Dart/3.x (dart:io)"). Servers tell a sing-box client from a browser by it and
      // answer with what that client can parse; a Dart one gets a config meant for something else.
      _dio[mode]!.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            final current = options.headers.entries
                .where((header) => header.key.toLowerCase() == 'user-agent')
                .map((header) => header.value?.toString() ?? '')
                .firstOrNull;
            if (current == null || current.trim().isEmpty || current.startsWith('Dart')) {
              options.headers.removeWhere((key, _) => key.toLowerCase() == 'user-agent');
              options.headers['User-Agent'] = userAgent;
            }
            handler.next(options);
          },
        ),
      );
      _dio[mode]!.interceptors.add(
        RetryInterceptor(
          dio: _dio[mode]!,
          retryDelays: [
            const Duration(seconds: 1),
            if (mode != "proxy") ...[const Duration(seconds: 2), const Duration(seconds: 3)],
          ],
        ),
      );

      _dio[mode]!.httpClientAdapter = IOHttpClientAdapter(
        createHttpClient: () {
          // what dart:io sends when a request has no user agent of its own, a redirect for one
          final client = HttpClient()..userAgent = userAgent;
          client.findProxy = (url) {
            // the secret, when the local port asks for it; one that does not ignores it
            final proxy = secret.isEmpty ? "localhost:$port" : "hiddify:$secret@localhost:$port";
            if (mode == "proxy") {
              return "PROXY $proxy";
            } else if (mode == "direct") {
              return "DIRECT";
            } else {
              return "PROXY $proxy; DIRECT";
            }
          };
          return client;
        },
      );
    }

    if (debug) {
      // _dio.interceptors.add(LoggyDioInterceptor(requestHeader: true));
    }
  }

  int port = 0;

  /// The secret of the local port, empty when it asks for none.
  String secret = "";

  String userAgent;
  // bool isPortOpen(String host, int port, {Duration timeout = const Duration(milliseconds: 200)}) async{
  //   try {
  //     Socket.connect(host, port, timeout: timeout).then((socket) {
  //       socket.destroy();
  //     });
  //     return true;
  //   } on SocketException catch (_) {
  //     return false;
  //   } catch (_) {
  //     return false;
  //   }
  // }
  Future<bool> isPortOpen(String host, int port, {Duration timeout = const Duration(seconds: 5)}) async {
    try {
      final socket = await Socket.connect(host, port, timeout: timeout);
      await socket.close();
      return true;
    } on SocketException catch (_) {
      return false;
    } catch (_) {
      return false;
    }
  }

  void setProxyPort(int port) {
    this.port = port;
    loggy.debug("setting proxy port: [$port]");
  }

  Future<Response<T>> get<T>(
    String url, {
    CancelToken? cancelToken,
    String? userAgent,
    ({String username, String password})? credentials,
    bool proxyOnly = false,
    ResponseType? responseType,
  }) async {
    // in VPN mode the local port is closed, and the VPN carries the request instead
    final portOpen = await isPortOpen("127.0.0.1", port);
    final mode = proxyOnly && portOpen
        ? "proxy"
        : portOpen
        ? "both"
        : "direct";
    final dio = _dio[mode]!;

    return dio.get<T>(
      url,
      cancelToken: cancelToken,
      options: _options(url, userAgent: userAgent, credentials: credentials, responseType: responseType),
    );
  }

  /// The body as text, at most [maxBytes]: a server that streams more is cut off instead of
  /// filling the memory of the device. Used where the response is a subscription the app did not
  /// ask for itself.
  Future<String> getText(String url, {CancelToken? cancelToken, String? userAgent, int maxBytes = 4 << 20}) async {
    final portOpen = await isPortOpen("127.0.0.1", port);
    final mode = portOpen ? "both" : "direct";
    final dio = _dio[mode]!;
    final response = await dio.get<ResponseBody>(
      url,
      cancelToken: cancelToken,
      options: _options(url, userAgent: userAgent, responseType: ResponseType.stream),
    );
    final body = response.data;
    if (body == null) return '';
    final builder = BytesBuilder(copy: false);
    await for (final chunk in body.stream) {
      builder.add(chunk);
      if (builder.length > maxBytes) {
        cancelToken?.cancel("the response is larger than $maxBytes bytes");
        throw ResponseTooLargeException(maxBytes);
      }
    }
    return utf8.decode(builder.takeBytes(), allowMalformed: true);
  }

  Future<Response> download(
    String url,
    String path, {
    CancelToken? cancelToken,
    String? userAgent,
    ({String username, String password})? credentials,
    bool proxyOnly = false,
    ProgressCallback? onReceiveProgress,
  }) async {
    final mode = proxyOnly
        ? "proxy"
        : await isPortOpen("127.0.0.1", port)
        ? "both"
        : "direct";
    final dio = _dio[mode]!;
    return dio.download(
      url,
      path,
      cancelToken: cancelToken,
      onReceiveProgress: onReceiveProgress,
      options: _options(url, userAgent: userAgent, credentials: credentials),
    );
  }

  Options _options(
    String url, {
    String? userAgent,
    ({String username, String password})? credentials,
    ResponseType? responseType,
  }) {
    final uri = Uri.parse(url);

    String? userInfo;
    if (credentials != null) {
      userInfo = "${credentials.username}:${credentials.password}";
    } else if (uri.userInfo.isNotEmpty) {
      userInfo = uri.userInfo;
    }

    String? basicAuth;
    if (userInfo != null) {
      basicAuth = "Basic ${base64.encode(utf8.encode(userInfo))}";
    }

    return Options(
      responseType: responseType,
      headers: {
        "User-Agent": userAgent ?? this.userAgent,
        if (basicAuth != null) "authorization": basicAuth,
        // "Accept": "application/json",
        // "Content-Type": "application/json",
      },
    );
  }
}

/// A response larger than the caller allows. Thrown before the body is kept.
class ResponseTooLargeException implements Exception {
  const ResponseTooLargeException(this.maxBytes);

  final int maxBytes;

  @override
  String toString() => 'the response is larger than $maxBytes bytes';
}
