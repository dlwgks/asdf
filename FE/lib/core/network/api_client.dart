import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../features/auth/data/token_store.dart';
import 'api_contract.dart';

DateTime? _jwtExpiry(String token) {
  try {
    final parts = token.split('.');
    if (parts.length != 3) return null;
    final payload = parts[1];
    final padded = payload + '=' * ((4 - payload.length % 4) % 4);
    final decoded = jsonDecode(utf8.decode(base64Url.decode(padded)));
    final exp = decoded['exp'];
    if (exp is int) return DateTime.fromMillisecondsSinceEpoch(exp * 1000);
  } catch (_) {}
  return null;
}

class ApiRequestError extends StateError {
  ApiRequestError(this.failure) : super(failure.displayMessage);
  final ApiFailure failure;
  String get code => failure.code;
  int get status => failure.status;
}

class ApiClient {
  ApiClient({
    required String baseUrl,
    http.Client? client,
    TokenStore? tokenStore,
    this.onSessionExpired,
    this.timeout = const Duration(seconds: 10),
  }) : _baseUrl = baseUrl.replaceFirst(RegExp(r'/$'), ''),
       _client = client ?? http.Client(),
       _ownsClient = client == null,
       _tokens = tokenStore;

  final String _baseUrl;
  final http.Client _client;
  final bool _ownsClient;
  TokenStore? _tokens;
  TokenStore get tokens => _tokens ??= SecureTokenStore();
  final Duration timeout;
  void Function(String method, String path)? beforeAuthorizedRequest;
  final Future<void> Function()? onSessionExpired;
  String? _expiredToken;
  Future<void>? _expiration;

  void sessionStarted() {
    _expiredToken = null;
  }

  Future<void> _tryRefresh(String token) async {
    final expiry = _jwtExpiry(token);
    if (expiry == null) return;
    final remaining = expiry.difference(DateTime.now());
    if (remaining.inDays > 3) return;
    try {
      final request = http.Request('POST', Uri.parse('$_baseUrl${ApiContract.refresh}'))
        ..headers.addAll(ApiContract.jsonHeaders(token));
      final result = await (() async => http.Response.fromStream(await _client.send(request)))()
          .timeout(timeout);
      if (result.statusCode == 200) {
        final body = jsonDecode(utf8.decode(result.bodyBytes));
        final newToken = body['access_token'] as String?;
        if (newToken != null && newToken != token) await tokens.write(newToken);
      }
    } catch (_) {}
  }

  Future<Map<String, dynamic>?> send(
    String method,
    String path, {
    Map<String, dynamic>? body,
    bool authorized = true,
    Duration? timeout,
  }) async {
    if (authorized) beforeAuthorizedRequest?.call(method, path);
    var token = authorized ? await tokens.read() : null;
    if (token != null) await _tryRefresh(token);
    token = authorized ? await tokens.read() : null;
    final request = http.Request(method, Uri.parse('$_baseUrl$path'))
      ..headers.addAll(ApiContract.jsonHeaders(token));
    if (body != null) request.body = jsonEncode(body);
    return _send(request, token, timeout);
  }

  Future<Map<String, dynamic>?> upload(
    String path, {
    required List<http.MultipartFile> files,
    Map<String, String> fields = const {},
    String method = 'POST',
    Map<String, List<String>> repeatedFields = const {},
  }) async {
    beforeAuthorizedRequest?.call(method, path);
    final token = await tokens.read();
    final request = http.MultipartRequest(method, Uri.parse('$_baseUrl$path'))
      ..fields.addAll(fields)
      ..files.addAll(files)
      ..files.addAll([
        for (final entry in repeatedFields.entries)
          for (final value in entry.value)
            http.MultipartFile.fromString(entry.key, value),
      ]);
    if (token != null) request.headers['Authorization'] = 'Bearer $token';
    return _send(request, token);
  }

  Future<Map<String, dynamic>?> _send(
    http.BaseRequest request,
    String? token, [
    Duration? limit,
  ]) async {
    final http.Response response;
    try {
      response = await (() async => http.Response.fromStream(
        await _client.send(request),
      ))().timeout(limit ?? timeout);
    } on TimeoutException {
      throw StateError('서버 응답이 늦어요. 잠시 후 다시 시도해 주세요.');
    } on http.ClientException {
      throw StateError('서버에 연결하지 못했어요. 네트워크를 확인해 주세요.');
    }
    if (response.statusCode == 401 && token != null) await _expire(token);
    dynamic decoded;
    try {
      final text = utf8.decode(response.bodyBytes);
      if (text.isNotEmpty) decoded = jsonDecode(text);
    } on FormatException {
      throw StateError('서버 응답을 처리하지 못했어요. 다시 시도해 주세요.');
    }
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (decoded == null) return null;
      if (decoded is Map<String, dynamic>) return decoded;
      throw StateError('서버 응답을 처리하지 못했어요. 다시 시도해 주세요.');
    }
    if (decoded is Map<String, dynamic> &&
        decoded['code'] is String &&
        decoded['message'] is String) {
      final fields = decoded['fields'];
      throw ApiRequestError(
        ApiFailure(
          code: decoded['code'] as String,
          message: decoded['message'] as String,
          status: response.statusCode,
          fields: fields is List
              ? [
                  for (final field in fields)
                    if (field is Map &&
                        field['field'] is String &&
                        field['reason'] is String)
                      ApiFieldError(
                        field: field['field'] as String,
                        reason: field['reason'] as String,
                      ),
                ]
              : null,
        ),
      );
    }
    throw StateError('서버 오류가 발생했어요. 잠시 후 다시 시도해 주세요.');
  }

  Future<void> _expire(String token) async {
    if (_expiredToken == token) {
      await _expiration;
      return;
    }
    if (await tokens.read() != token) return;
    if (_expiredToken == token) {
      await _expiration;
      return;
    }
    _expiredToken = token;
    final pending = () async {
      await tokens.clear();
      await onSessionExpired?.call();
    }();
    _expiration = pending;
    await pending;
  }

  void dispose() {
    if (_ownsClient) _client.close();
  }
}
