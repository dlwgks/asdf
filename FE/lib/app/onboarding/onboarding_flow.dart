import '../start_screen.dart';
import '../../core/navigation/home_signal.dart';
import '../../features/auth/data/local_onboarding_repository.dart';
import '../../features/community/data/community_repository.dart';
import '../../features/community/data/local_community_repository.dart';
import '../../features/community/presentation/community_controller.dart';
import '../../features/photos/data/models/selected_photo.dart';
import '../../features/settings/data/account_repository.dart';
import '../../features/settings/data/account_data_cleaner.dart';
import '../../features/settings/data/consent_request_guard.dart';
import '../../features/settings/presentation/account_settings_dependencies.dart';
import '../../features/auth/data/models/onboarding_progress.dart';
import '../../core/network/api_contract.dart';
import '../../features/workout/data/workout_repository.dart';
import '../../features/workout/data/workout_draft_store.dart';
import '../../features/workout/presentation/workout_dependencies.dart';
import '../../features/home/data/home_server_repository.dart';
import '../../features/home/data/recommendation_choice_store.dart';
import '../../features/home/presentation/home_server_dependencies.dart';
import '../../features/routines/data/server_routine_repository.dart';
import '../../features/routines/data/local_routine_repository.dart';
import '../../features/exercises/data/server_exercise_repository.dart';
import '../../features/exercises/data/local_exercise_repository.dart';
import '../../features/goal_body/data/local_goal_repository.dart';
import '../../features/goal_body/data/server_goal_repository.dart';
import '../../features/goal_body/presentation/goal_dependencies.dart';
import '../../features/body_analysis/data/analysis_pending_store.dart';
import '../../features/body_analysis/data/local_analysis_repository.dart';
import '../../features/body_analysis/data/server_analysis_repository.dart';
import '../../features/body_analysis/presentation/analysis_dependencies.dart';
import '../../features/body_analysis/presentation/pages/analysis_entry_page.dart';
import 'package:flutter/material.dart';

import '../../core/media/image_picker_service.dart';
import '../../features/auth/data/onboarding_repository.dart';
import '../../features/auth/data/server_onboarding_repository.dart';
import '../../features/profile/data/local_profile_repository.dart';
import '../../features/profile/data/server_profile_repository.dart';
import '../../features/photos/data/local_photo_repository.dart';
import '../../features/photos/data/server_photo_repository.dart';
import '../../features/routines/data/routine_refresh_repository.dart';
import '../../features/routines/data/server_routine_refresh_repository.dart';
import '../../features/auth/presentation/pages/signup_page.dart';
import '../../features/auth/presentation/pages/login_page.dart';
import '../../features/auth/presentation/pages/consent_page.dart';
import '../../features/profile/presentation/pages/profile_form_page.dart';
import '../../ui/core/widgets/primary_button.dart';
import '../shell/main_shell.dart';
import 'onboarding_view_model.dart';

class OnboardingFlow extends StatefulWidget {
  const OnboardingFlow({
    super.key,
    required this.repository,
    required this.photoPicker,
    this.startWithLogin = false,
  });
  final OnboardingRepository repository;
  final PhotoPicker photoPicker;
  final bool startWithLogin;
  @override
  State<OnboardingFlow> createState() => _OnboardingFlowState();
}

class _OnboardingFlowState extends State<OnboardingFlow> {
  late final OnboardingViewModel _model;
  final _homeRequests = ValueNotifier<int>(0);
  final _settingsRequests = ValueNotifier<int>(0);
  final _accountSettings = <String, AccountSettingsDependencies>{};
  final _homeServers = <String, HomeServerDependencies>{};
  final _analysisDependencies = <String, AnalysisDependencies>{};
  final _communities = <String, CommunityDependencies>{};
  final _localPhotos = <String, LocalPhotoRepository>{};
  @override
  void initState() {
    super.initState();
    _model = OnboardingViewModel(
      repository: widget.repository,
      photoPicker: widget.photoPicker is ImagePickerService
          ? ImagePickerService(
              ownerProvider: () => _model.progress.account == null
                  ? 'onboarding'
                  : 'onboarding:${_model.progress.account!.storageOwner}',
            )
          : widget.photoPicker,
    );
    _model.initialize(startWithLogin: widget.startWithLogin);
    homeTabRequests.addListener(_goHomeFromSignal);
  }

  void _goHomeFromSignal() {
    if (_model.progress.completed && _model.progress.signedIn) return;
    if (!mounted || _model.busy) return;
    Navigator.of(context).popUntil((route) => route.isFirst);
    _model.finish(prepareAnalysis: false);
  }

  @override
  void dispose() {
    homeTabRequests.removeListener(_goHomeFromSignal);
    _settingsRequests.dispose();
    _homeRequests.dispose();
    _model.dispose();
    super.dispose();
  }

  AccountSettingsDependencies? _settings() {
    final server = widget.repository;
    if (server is! ServerOnboardingRepository) return null;
    final owner = _model.progress.account!.storageOwner;
    return _accountSettings.putIfAbsent(
      owner,
      () => AccountSettingsDependencies(
        repository: ServerAccountRepository(server.api),
        onChanged: (me) {
          if (mounted &&
              _model.progress.signedIn &&
              _model.progress.account?.storageOwner == owner) {
            _model.applySavedAccount(
              OnboardingAccount(
                nickname: me.nickname,
                userId: me.userId,
                email: me.email ?? '',
                bodyPhotoAgreed: me.bodyPhotoAgreed,
                aiProcessingAgreed: me.aiProcessingAgreed,
              ),
            );
          }
        },
        cleanup: () async {
          if (_model.progress.account?.storageOwner == owner) {
            await server.api.tokens.clear();
          }
          await AccountDataCleaner(
            owner,
            discardPhoto: widget.photoPicker is ImagePickerService
                ? (path) => widget.photoPicker.discard(
                    SelectedPhoto(path: path, byteLength: 0),
                  )
                : null,
          ).clear();
          if (widget.photoPicker is ImagePickerService) {
            for (final photo in [
              _model.progress.analysis.front,
              _model.progress.analysis.side,
            ]) {
              if (photo != null &&
                  _model.progress.account?.storageOwner == owner) {
                await widget.photoPicker.discard(photo);
              }
            }
            await ImagePickerService.clearAccountCopies(owner);
          }
        },
        onDeleted: () async {
          _communities.remove(owner);
          _homeServers.remove(owner);
          _analysisDependencies.remove(owner);
          _localPhotos.remove(owner);
          _accountSettings.remove(owner);
          if (mounted && _model.progress.account?.storageOwner == owner) {
            Navigator.of(context).popUntil((r) => r.isFirst);
            server.clearSessionData();
            _model.completeAccountDeletion();
          }
        },
      ),
    );
  }

  AnalysisDependencies _analysis() {
    final owner = _model.progress.account!.storageOwner;
    return _analysisDependencies.putIfAbsent(owner, () {
      final server = widget.repository is ServerOnboardingRepository
          ? widget.repository as ServerOnboardingRepository
          : null;
      final policy = ConsentRequestGuard(() => _model.progress.account);
      if (server != null) server.api.beforeAuthorizedRequest = policy.check;
      final photos = server == null
          ? _localPhotos.putIfAbsent(owner, LocalPhotoRepository.new)
          : ServerPhotoRepository(server.api);
      void checkAccess() async {
        if (server != null) {
          try {
            final me = await _settings()!.repository.load();
            if (!mounted) return;
            _model.applySavedAccount(
              OnboardingAccount(
                nickname: me.nickname,
                userId: me.userId,
                email: me.email ?? '',
                bodyPhotoAgreed: me.bodyPhotoAgreed,
                aiProcessingAgreed: me.aiProcessingAgreed,
              ),
            );
            if (!me.bodyPhotoAgreed || !me.aiProcessingAgreed) {
              _settingsRequests.value++;
              return;
            }
          } catch (_) {
            if (!mounted) return;
          }
        }
        if (mounted) {
          Navigator.of(context).popUntil((route) => route.isFirst);
          _model.initialize();
        }
      }

      return AnalysisDependencies(
        localPreview: server == null,
        ensureCanAnalyze: server == null
            ? null
            : () => policy.check('POST', ApiContract.photoAnalysis),
        workouts: server == null
            ? null
            : WorkoutDependencies(
                repository: ServerWorkoutRepository(server.api),
                routines: ServerRoutineRepository(server.api),
                drafts: PreferencesWorkoutDraftStore(owner),
                onAccessRequired: checkAccess,
                onHome: () {
                  if (mounted) _homeRequests.value++;
                },
              ),
        routines: server == null
            ? LocalRoutineRepository()
            : ServerRoutineRepository(server.api),
        exercises: server == null
            ? LocalExerciseRepository(
                () => _model.progress.profile?.experienceLevel,
              )
            : ServerExerciseRepository(server.api),
        goals: GoalDependencies(
          ensureCanWrite: server == null
              ? null
              : () => policy.check('POST', ApiContract.goalBodies),
          repository: server == null
              ? LocalGoalRepository(photos: photos)
              : ServerGoalRepository(server.api),
          photos: photos,
          picker: widget.photoPicker is ImagePickerService
              ? ImagePickerService(owner: 'goal:$owner')
              : widget.photoPicker,
          localPreview: server == null,
          onAccessRequired: checkAccess,
        ),
        repository: server == null
            ? LocalAnalysisRepository()
            : ServerAnalysisRepository(server.api),
        photos: photos,
        picker: widget.photoPicker is ImagePickerService
            ? ImagePickerService(owner: 'analysis:$owner')
            : widget.photoPicker,
        pending: server == null
            ? MemoryAnalysisPendingStore()
            : PreferencesAnalysisPendingStore(owner),
        onAccessRequired: checkAccess,
      );
    });
  }

  CommunityDependencies _community() {
    final owner = _model.progress.account!.storageOwner;
    return _communities.putIfAbsent(owner, () {
      final server = widget.repository;
      return CommunityDependencies(
        profileOwner: owner,
        currentNickname: () => _model.progress.account?.nickname ?? '나',
        repository: server is ServerOnboardingRepository
            ? ServerCommunityRepository(server.api)
            : LocalCommunityRepository(
                () => _model.progress.account?.nickname ?? '나',
              ),
        picker: NativeCommunityImagePicker(),
        preview: server is! ServerOnboardingRepository,
        onAccessRequired: _analysis().onAccessRequired,
      );
    });
  }

  HomeServerDependencies? _homeServer() {
    final repository = widget.repository;
    if (repository is! ServerOnboardingRepository) return null;
    final owner = _model.progress.account!.storageOwner;
    return _homeServers.putIfAbsent(
      owner,
      () => HomeServerDependencies(
        repository: ApiHomeServerRepository(repository.api),
        routines: _analysis().routines!,
        choices: PreferencesRecommendationChoiceStore(owner),
        workouts: _analysis().workouts,
        exercises: _analysis().exercises,
        onAccessRequired: _analysis().onAccessRequired,
      ),
    );
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _model,
    builder: (context, child) {
      if (_model.loading) {
        return const AppStartScreen();
      }
      if (_model.showLogin) return LoginPage(model: _model);
      if (_model.progress.signedIn &&
          _model.progress.account != null &&
          !_model.progress.consentCompleted &&
          !_model.progress.canBrowseAfterWithdrawal) {
        return ConsentPage(model: _model);
      }
      if (_model.progress.completed && _model.progress.signedIn) {
        return MainShell(
          apiClient: widget.repository is ServerOnboardingRepository
              ? (widget.repository as ServerOnboardingRepository).api
              : null,
          community: _community(),
          homeRequests: _homeRequests,
          settingsRequests: _settingsRequests,
          accountSettings: _settings(),
          onboarding: _model.progress,
          analysisDependencies: _analysis(),
          homeServer: _homeServer(),
          exerciseRepository: _analysis().exercises,
          exercisePreview: widget.repository is! ServerOnboardingRepository,
          onLogout: _model.signOut,
          profileRepository: widget.repository is ServerOnboardingRepository
              ? ServerProfileRepository(
                  (widget.repository as ServerOnboardingRepository).api,
                )
              : LocalProfileRepository(widget.repository),
          routineRefreshRepository:
              widget.repository is ServerOnboardingRepository
              ? ServerRoutineRefreshRepository(
                  (widget.repository as ServerOnboardingRepository).api,
                )
              : LocalRoutineRefreshRepository(),
          photoRepository: widget.repository is ServerOnboardingRepository
              ? ServerPhotoRepository(
                  (widget.repository as ServerOnboardingRepository).api,
                )
              : _localPhotos.putIfAbsent(
                  _model.progress.account!.storageOwner,
                  LocalPhotoRepository.new,
                ),
          photoPicker: widget.photoPicker is ImagePickerService
              ? ImagePickerService(
                  owner: 'photos:${_model.progress.account!.storageOwner}',
                )
              : widget.photoPicker,
          onNicknameSave: widget.repository is LocalOnboardingRepository
              ? (name) async {
                  final next =
                      await (widget.repository as LocalOnboardingRepository)
                          .renameNickname(name);
                  if (mounted) _model.applySavedAccount(next.account!);
                  return next.account!.nickname;
                }
              : null,
          onProfileSaved: _model.applySavedProfile,
          loggingOut: _model.busy,
          logoutError: _model.error,
        );
      }
      if (_model.error != null &&
          _model.progress.account == null &&
          _model.step == 0 &&
          !_model.busy &&
          _model.nickname.isEmpty) {
        // 저장소 장애는 새 가입으로 덮어쓰지 않고 복원 재시도를 제공한다.
        if (_model.error!.startsWith('저장된')) {
          return Scaffold(
            body: SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(_model.error!, textAlign: TextAlign.center),
                    const SizedBox(height: 16),
                    PrimaryButton(
                      label: '다시 시도',
                      onPressed: () => _model.initialize(
                        startWithLogin: widget.startWithLogin,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        }
      }
      return PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, result) {
          if (!didPop && !_model.busy) {
            if (_model.step == 0) {
              _model.openLogin();
            } else {
              _model.back();
            }
          }
        },
        child: switch (_model.step) {
          0 => SignupPage(key: const ValueKey('signup'), model: _model),
          1 => ProfileFormPage(key: const ValueKey('profile'), model: _model),
          _ => AnalysisEntryPage(
            key: const ValueKey('onboarding-analysis'),
            dependencies: _analysis(),
            onBack: _model.back,
            onCompleted: () => _model.finish(prepareAnalysis: false),
          ),
        },
      );
    },
  );
}
