import '../../../core/network/api_client.dart';
import '../../../core/network/api_contract.dart';
import 'goal_repository.dart';
import 'models/goal_body.dart';

class ServerGoalRepository implements GoalRepository {
  ServerGoalRepository(this.api);
  final ApiClient api;
  @override
  Future<GoalBody?> load() async {
    try {
      return GoalBody.fromJson(
        (await api.send('GET', ApiContract.goalBodies))!,
      );
    } on ApiRequestError catch (e) {
      if (e.code == 'GOAL_NOT_FOUND' || e.code == 'NOT_FOUND' || e.status == 404) return null;
      rethrow;
    }
  }

  Map<String, dynamic> _body(int? photoId, String? description) => {
    'photo_id': ?photoId,
    'description': ?description,
  };
  @override
  Future<GoalBody> create({int? photoId, String? description}) async =>
      GoalBody.fromJson(
        (await api.send(
          'POST',
          ApiContract.goalBodies,
          body: _body(photoId, description),
        ))!,
      );
  @override
  Future<GoalBody> update(int id, {int? photoId, String? description}) async =>
      GoalBody.fromJson(
        (await api.send(
          'PATCH',
          ApiContract.goalBody(id),
          body: _body(photoId, description),
        ))!,
      );
  @override
  Future<void> delete(int id) async {
    await api.send('DELETE', ApiContract.goalBody(id));
  }
}
