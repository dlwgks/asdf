/// Team API baseline received 2026-10-06. Final requirements, API and ERD are in docs/final-specs.
abstract final class ApiContract {
  static const prefix = '/api';
  static const users = '$prefix/users';
  static const login = '$prefix/auth/login';
  static const passwordResetRequest = '$prefix/auth/password/reset-request';
  static const passwordReset = '$prefix/auth/password/reset';
  static const passwordResetVerification =
      '$prefix/auth/password/reset-verification';
  static const logout = '$prefix/auth/logout';
  static const refresh = '$prefix/auth/refresh';
  static const emailVerifications = '$prefix/auth/email-verifications';
  static const emailVerificationConfirm = '$emailVerifications/confirm';
  static const googleLogin = '$prefix/auth/oauth/google';
  static const kakaoLogin = '$prefix/auth/oauth/kakao';
  static const me = '$prefix/users/me';
  static const profile = '$me/profile';
  static const photos = '$prefix/photos';
  static String photo(int id) => '$photos/$id';
  static const bodyAnalyses = '$prefix/body-analyses';
  static const photoAnalysis = '$bodyAnalyses/photo';
  static const textAnalysis = '$bodyAnalyses/text';
  static const analysisUsage = '$bodyAnalyses/usage';
  static String bodyAnalysis(int id) => '$bodyAnalyses/$id';
  static const goalBodies = '$prefix/goal-bodies';
  static String goalBody(int id) => '$goalBodies/$id';
  static const exercises = '$prefix/exercises';
  static String exercise(int id) => '$exercises/$id';
  static const routines = '$prefix/routines';
  static const weeklyRoutines = '$routines/weekly';
  static const todayRoutine = '$routines/today';
  static String routine(int id) => '$routines/$id';
  static const workoutLogs = '$prefix/workout-logs';
  static String workoutLog(int id) => '$workoutLogs/$id';
  static String workoutSets(int id) => '${workoutLog(id)}/sets';
  static String workoutSet(int logId, int setId) =>
      '${workoutSets(logId)}/$setId';
  static String completeWorkout(int id) => '${workoutLog(id)}/complete';
  static const posts = '$prefix/posts';
  static String post(int id) => '$posts/$id';
  static String postComments(int id) => '${post(id)}/comments';
  static String postComment(int id, int commentId) =>
      '${postComments(id)}/$commentId';
  static String postLikes(int id) => '${post(id)}/likes';
  static const reports = '$prefix/reports';
  static const blocks = '$prefix/blocks';
  static String block(int id) => '$blocks/$id';
  static const aiCoachMessages = '$prefix/ai-coach/messages';
  static const accessTokenLifetimeSeconds = 604800;
  static const maxPhotoBytes = 10 * 1024 * 1024;
  static const photoMaxEdge = 1024;
  static const photoLimit = 20;
  static const signedUrlLifetimeSeconds = 3600;
  static Map<String, String> jsonHeaders(String? accessToken) => {
    'Content-Type': 'application/json; charset=utf-8',
    if (accessToken != null) 'Authorization': 'Bearer $accessToken',
  };
  // Multipart requests must let their transport set the boundary.
}

class ApiFieldError {
  const ApiFieldError({required this.field, required this.reason});
  final String field, reason;
  factory ApiFieldError.fromJson(Map<String, dynamic> json) => ApiFieldError(
    field: json['field'] as String,
    reason: json['reason'] as String,
  );
}

class ApiFailure implements Exception {
  const ApiFailure({
    required this.code,
    required this.message,
    required this.status,
    required this.fields,
  });
  final String code, message;
  final int status;
  final List<ApiFieldError>? fields;
  factory ApiFailure.fromJson(Map<String, dynamic> json) => ApiFailure(
    code: json['code'] as String,
    message: json['message'] as String,
    status: json['status'] as int,
    fields: (json['fields'] as List<dynamic>?)
        ?.map(
          (e) => ApiFieldError.fromJson(Map<String, dynamic>.from(e as Map)),
        )
        .toList(),
  );

  String get displayMessage =>
      (fields != null && fields!.isNotEmpty) ? fields!.first.reason : message;
}
