import '../../core/navigation/home_signal.dart';
import '../../features/community/presentation/community_controller.dart';
import '../../features/community/presentation/community_page.dart';
import '../../features/settings/presentation/account_settings_dependencies.dart';
import '../../features/settings/presentation/pages/account_settings_pages.dart';
import '../../features/home/presentation/home_server_dependencies.dart';
import '../../features/routines/presentation/pages/routine_week_page.dart';
import '../../features/exercises/data/exercise_repository.dart';
import '../../features/exercises/presentation/pages/exercise_list_page.dart';
import '../../features/goal_body/presentation/pages/goal_body_page.dart';
import 'package:flutter/material.dart';

import '../../features/auth/data/models/onboarding_progress.dart';
import '../../core/media/image_picker_service.dart';
import '../../features/profile/data/models/body_profile.dart';
import '../../features/profile/data/profile_repository.dart';
import '../../features/routines/data/routine_refresh_repository.dart';
import '../../features/photos/data/photo_repository.dart';
import '../../features/profile/presentation/pages/my_page.dart';
import '../../features/body_analysis/presentation/pages/analysis_entry_page.dart';
import '../../features/body_analysis/presentation/analysis_dependencies.dart';
import '../../features/body_analysis/presentation/pages/analysis_history_page.dart';
import '../../features/home/presentation/pages/home_page.dart';
import '../../features/workout/presentation/pages/workout_history_page.dart';
import 'app_bottom_nav.dart';
import '../../ui/core/widgets/app_scaffold.dart';
import '../../ui/core/widgets/empty_view.dart';
import '../../core/network/api_client.dart';
import '../../features/ai_coach/presentation/ai_coach_page.dart';

class MainShell extends StatefulWidget {
  const MainShell({
    super.key,
    this.initialIndex = 0,
    this.onboarding,
    this.onLogout,
    this.loggingOut = false,
    this.logoutError,
    this.profileRepository,
    this.routineRefreshRepository,
    this.photoRepository,
    this.photoPicker,
    this.onProfileSaved,
    this.onNicknameSave,
    this.analysisDependencies,
    this.exerciseRepository,
    this.exercisePreview = false,
    this.homeServer,
    this.homeRequests,
    this.accountSettings,
    this.settingsRequests,
    this.community,
    this.apiClient,
  }) : assert(initialIndex >= 0 && initialIndex < 5);

  final CommunityDependencies? community;
  final ApiClient? apiClient;
  final HomeServerDependencies? homeServer;
  final Listenable? homeRequests;
  final Listenable? settingsRequests;
  final AccountSettingsDependencies? accountSettings;
  final ExerciseRepository? exerciseRepository;
  final bool exercisePreview;
  final AnalysisDependencies? analysisDependencies;
  final ProfileRepository? profileRepository;
  final RoutineRefreshRepository? routineRefreshRepository;
  final PhotoRepository? photoRepository;
  final PhotoPicker? photoPicker;
  final Future<String> Function(String)? onNicknameSave;
  final ValueChanged<BodyProfile>? onProfileSaved;
  final int initialIndex;
  final OnboardingProgress? onboarding;
  final VoidCallback? onLogout;
  final bool loggingOut;
  final String? logoutError;

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  late int _selectedIndex;
  int _homeRevision = 0;

  @override
  void initState() {
    super.initState();
    _selectedIndex = widget.initialIndex;
    widget.homeRequests?.addListener(_returnHome);
    widget.settingsRequests?.addListener(_openPrivacy);
    homeTabRequests.addListener(_returnHome);
  }

  void _openPrivacy() {
    if (widget.accountSettings == null ||
        widget.photoRepository == null ||
        widget.photoPicker == null) {
      return;
    }
    Navigator.of(context).popUntil((r) => r.isFirst);
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => PrivacySettingsPage(
          dependencies: widget.accountSettings!,
          photos: widget.photoRepository!,
          picker: widget.photoPicker!,
        ),
      ),
    );
  }

  void _returnHome() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    if (mounted) {
      setState(() {
        _selectedIndex = 0;
        _homeRevision++;
      });
    }
  }

  @override
  void didUpdateWidget(covariant MainShell oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.settingsRequests != widget.settingsRequests) {
      oldWidget.settingsRequests?.removeListener(_openPrivacy);
      widget.settingsRequests?.addListener(_openPrivacy);
    }
    if (oldWidget.homeRequests != widget.homeRequests) {
      oldWidget.homeRequests?.removeListener(_returnHome);
      widget.homeRequests?.addListener(_returnHome);
    }
  }

  @override
  void dispose() {
    widget.homeRequests?.removeListener(_returnHome);
    widget.settingsRequests?.removeListener(_openPrivacy);
    homeTabRequests.removeListener(_returnHome);
    super.dispose();
  }

  void _openAnalysis() {
    Navigator.of(context).popUntil((route) => route.isFirst);
    if (mounted) setState(() => _selectedIndex = 1);
  }

  void _openRoutines() {
    final repository = widget.analysisDependencies?.routines;
    if (repository == null) return;
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => RoutineWeekPage(
          repository: repository,
          exercises: widget.exerciseRepository,
          localPreview: widget.exercisePreview,
          workouts: widget.analysisDependencies?.workouts,
          onAccessRequired: widget.analysisDependencies?.onAccessRequired,
        ),
      ),
    );
  }

  void _openExercises() {
    final repository = widget.exerciseRepository;
    if (repository == null) return;
    FocusScope.of(context).unfocus();
    Navigator.push(
      context,
      MaterialPageRoute<void>(
        builder: (_) => ExerciseListPage(
          repository: repository,
          localPreview: widget.exercisePreview,
          workouts: widget.analysisDependencies?.workouts,
          onAccessRequired: widget.analysisDependencies?.onAccessRequired,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _selectedIndex,
        children: [
          HomePage(
            key: ValueKey('home:${widget.onboarding?.account?.storageOwner}'),
            server: widget.homeServer,
            isActive: _selectedIndex == 0,
            refreshToken: _homeRevision,
            onOpenAnalysis: _openAnalysis,
            onboarding: widget.onboarding,
            onOpenExercises: widget.exerciseRepository == null
                ? null
                : _openExercises,
            onOpenMyPage:
                widget.onboarding == null ||
                    widget.profileRepository == null ||
                    widget.photoRepository == null ||
                    widget.photoPicker == null
                ? null
                : () async {
                    await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (_) => MyPage(
                          onboarding: widget.onboarding!,
                          accountSettings: widget.accountSettings,
                          onConsentRequired: _openPrivacy,
                          profileRepository: widget.profileRepository!,
                          routineRefreshRepository:
                              widget.routineRefreshRepository,
                          photoRepository: widget.photoRepository!,
                          photoPicker: widget.photoPicker!,
                          onOpenAnalysisHistory:
                              widget.analysisDependencies == null
                              ? null
                              : () => Navigator.push(
                                  context,
                                  MaterialPageRoute<void>(
                                    builder: (_) => AnalysisHistoryPage(
                                      dependencies:
                                          widget.analysisDependencies!,
                                    ),
                                  ),
                                ),
                          onOpenGoal: widget.analysisDependencies?.goals == null
                              ? null
                              : () => Navigator.push(
                                  context,
                                  MaterialPageRoute<void>(
                                    builder: (_) => GoalBodyPage(
                                      dependencies:
                                          widget.analysisDependencies!.goals!,
                                    ),
                                  ),
                                ),
                          onOpenExercises: widget.exerciseRepository == null
                              ? null
                              : _openExercises,
                          onOpenRoutines:
                              widget.analysisDependencies?.routines == null
                              ? null
                              : _openRoutines,
                          onProfileSaved: widget.onProfileSaved,
                          onNicknameSave: widget.onNicknameSave,
                          onLogout: widget.onLogout,
                        ),
                      ),
                    );
                    if (mounted) setState(() => _homeRevision++);
                  },
            loggingOut: widget.loggingOut,
            logoutError: widget.logoutError,
          ),
          AnalysisEntryPage(
            key: ValueKey(widget.onboarding?.account?.storageOwner),
            dependencies: widget.analysisDependencies,
          ),
          WorkoutHistoryPage(
            server: widget.homeServer,
            isActive: _selectedIndex == 2,
            refreshToken: _homeRevision,
          ),
          widget.apiClient != null
              ? AiCoachPage(api: widget.apiClient!)
              : const AppScaffold(
                  title: '인공지능 코치',
                  body: EmptyView(title: '인공지능 코치 준비 중', description: '곧 만나요.'),
                ),
          CommunityPage(
            key: ValueKey(
              'community:${widget.onboarding?.account?.storageOwner}',
            ),
            dependencies: widget.community,
            isActive: _selectedIndex == 4,
          ),
        ],
      ),
      bottomNavigationBar: AppBottomNav(
        selectedIndex: _selectedIndex,
        onDestinationSelected: (index) {
          setState(() => _selectedIndex = index);
        },
      ),
    );
  }
}
