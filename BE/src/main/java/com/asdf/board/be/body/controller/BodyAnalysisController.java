package com.asdf.board.be.body.controller;

import com.asdf.board.be.body.entity.BodyAnalysis;
import com.asdf.board.be.body.entity.GoalBody;
import com.asdf.board.be.body.entity.Photo;
import com.asdf.board.be.body.repository.BodyAnalysisRepository;
import com.asdf.board.be.body.repository.GoalBodyRepository;
import com.asdf.board.be.body.repository.PhotoRepository;
import com.asdf.board.be.body.service.AiAnalysisClient;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.global.enums.TargetPart;
import com.asdf.board.be.correction.entity.CorrectionExercise;
import com.asdf.board.be.correction.repository.CorrectionExerciseRepository;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserProfileRepository;
import com.asdf.board.be.user.repository.UserRepository;
import com.asdf.board.be.workout.entity.*;
import com.asdf.board.be.workout.repository.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.asdf.board.be.global.time.KstTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Tag(name = "body", description = "체형 분석")
@RestController
@RequestMapping("/api/body-analyses")
@RequiredArgsConstructor
@Transactional
public class BodyAnalysisController {

    private final BodyAnalysisRepository analysisRepository;
    private final GoalBodyRepository goalBodyRepository;
    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final RoutineItemRepository routineItemRepository;
    private final ExerciseRepository exerciseRepository;
    private final AiAnalysisClient aiClient;
    private final UserProfileRepository profileRepository;
    private final CorrectionExerciseRepository correctionRepository;
    private ObjectMapper objectMapper = new ObjectMapper();

    private Long userId(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }
    private User user(Jwt jwt) {
        return userRepository.findById(userId(jwt)).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt
    ) {
        var pageable = PageRequest.of(page - 1, pageSize);
        var result = analysisRepository.findByUserIdOrderByCreatedAtDesc(userId(jwt), pageable);
        return Map.of(
                "items", result.getContent().stream().map(this::toSummary).toList(),
                "page", page,
                "total", result.getTotalElements()
        );
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@AuthenticationPrincipal Jwt jwt) {
        int limit = 3;
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.now(kst);
        int used = analysisRepository.countByUserIdAndCreatedAtGreaterThanEqualAndStatusIn(
                userId(jwt), today.atStartOfDay(), List.of(BodyAnalysis.Status.done, BodyAnalysis.Status.pending));
        String resetAt = today.plusDays(1).atStartOfDay(kst).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return Map.of(
                "used", used,
                "limit", limit,
                "remaining", Math.max(0, limit - used),
                "reset_at", resetAt
        );
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        BodyAnalysis a = analysisRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!a.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);
        return toDetail(a);
    }

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> startTextAnalysis(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        User u = user(jwt);
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED);
        Long goalId = body.get("goal_body_id") != null ? Long.valueOf(body.get("goal_body_id").toString()) : null;
        GoalBody goal = goalId != null ? goalBodyRepository.findById(goalId).orElse(null) : null;

        BodyAnalysis analysis = BodyAnalysis.builder()
                .user(u)
                .source(BodyAnalysis.Source.text)
                .status(BodyAnalysis.Status.done)
                .goalBody(goal)
                .textInput(text)
                .textSummary(text.length() > 100 ? text.substring(0, 100) + "..." : text)
                .completedAt(LocalDateTime.now())
                .build();
        analysis = analysisRepository.save(analysis);
        return Map.of("analysis_id", analysis.getId());
    }

    @PostMapping("/photo")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> startPhotoAnalysis(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        User u = user(jwt);
        Long photoId = Long.valueOf(body.get("photo_id").toString());
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!photo.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);

        Long sidePhotoId = body.get("side_photo_id") != null ? Long.valueOf(body.get("side_photo_id").toString()) : null;
        Photo sidePhoto = sidePhotoId != null ? photoRepository.findById(sidePhotoId).orElse(null) : null;

        Long goalId = body.get("goal_body_id") != null ? Long.valueOf(body.get("goal_body_id").toString()) : null;
        GoalBody goal = goalId != null ? goalBodyRepository.findById(goalId).orElse(null) : null;

        BodyAnalysis.BodyAnalysisBuilder builder = BodyAnalysis.builder()
                .user(u).source(BodyAnalysis.Source.photo).photo(photo).sidePhoto(sidePhoto).goalBody(goal);

        if (aiClient.isConfigured()) {
            BodyAnalysis pending = analysisRepository.save(builder.status(BodyAnalysis.Status.pending).build());
            long startedAt = System.nanoTime();
            try {
                Map<String, Object> ai = aiClient.analyze(photo.getStorageKey(),
                        sidePhoto != null ? sidePhoto.getStorageKey() : null);
                applyAiResult(pending, ai);
                applyGoalSimilarity(pending, photo, goal);
                waitForMinimumDuration(startedAt);
            } catch (AiAnalysisClient.AiAnalysisException e) {
                log.warn("AI analysis failed: {} - {}", e.getCode(), e.getMessage());
                pending.setStatus(BodyAnalysis.Status.failed);
                pending.setErrorCode(toEntityErrorCode(e.getCode()));
                pending.setErrorMessage(e.getMessage());
            }
            pending.setCompletedAt(LocalDateTime.now());
            return Map.of("analysis_id", pending.getId());
        }

        BodyAnalysis analysis = analysisRepository.save(builder
                .status(BodyAnalysis.Status.done)
                .completedAt(LocalDateTime.now()).build());
        return Map.of("analysis_id", analysis.getId());
    }

    @SuppressWarnings("unchecked")
    private void applyAiResult(BodyAnalysis a, Map<String, Object> ai) {
        Map<String, Object> measures = (Map<String, Object>) ai.get("measures");
        if (measures != null) {
            a.setShoulderTiltDeg(toBd(measures.get("shoulder_tilt_deg")));
            a.setPelvisTiltDeg(toBd(measures.get("pelvis_tilt_deg")));
            a.setNeckForwardDeg(toBd(measures.get("neck_forward_deg")));
            a.setShoulderHipRatio(toBd(measures.get("shoulder_hip_ratio")));
            a.setTorsoLegRatio(toBd(measures.get("torso_leg_ratio")));
            try {
                List<?> unavailable = (List<?>) measures.get("unavailable");
                if (unavailable != null) a.setUnavailableJson(objectMapper.writeValueAsString(unavailable));
            } catch (Exception ignored) {}
        }
        try {
            List<?> judgements = (List<?>) ai.get("judgements");
            if (judgements != null) a.setJudgementsJson(objectMapper.writeValueAsString(judgements));
        } catch (Exception ignored) {}

        a.setMeasurementVersion((String) ai.get("measurement_version"));
        a.setTextSummary((String) ai.getOrDefault("text_summary", ai.get("summary")));
        a.setStatus(BodyAnalysis.Status.done);

        Double shoulderTilt = measures != null ? toDouble(measures.get("shoulder_tilt_deg")) : null;
        Double pelvisTilt = measures != null ? toDouble(measures.get("pelvis_tilt_deg")) : null;
        if (shoulderTilt != null && pelvisTilt != null) {
            int score = (int) Math.max(0, Math.min(100, 100 - shoulderTilt * 5 - pelvisTilt * 5));
            a.setBalanceScore(score);
        }

        Double shoulderHip = measures != null ? toDouble(measures.get("shoulder_hip_ratio")) : null;
        if (shoulderHip != null) {
            a.setBodyType(shoulderHip >= 1.35 ? "역삼각형" : shoulderHip >= 0.95 ? "직사각형" : "사다리꼴");
        }
    }

    private void applyGoalSimilarity(BodyAnalysis a, Photo front, GoalBody goal) {
        if (goal == null || goal.getSourcePhoto() == null) return;
        Integer score = aiClient.similarity(front.getStorageKey(), goal.getSourcePhoto().getStorageKey());
        if (score == null) return;
        a.setComparisonSummary("목표 체형 사진과의 시각적 유사도 " + score + "%");
        a.setComparisonDetail("DINOv3 이미지 특징을 비교한 참고 값이에요. 자세·의복·배경의 영향을 받으며 근육량이나 체형 일치도를 뜻하지 않아요.");
    }

    private BigDecimal toBd(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }

    private BodyAnalysis.ErrorCode toEntityErrorCode(String code) {
        return switch (code) {
            case "IMAGE_BLURRY" -> BodyAnalysis.ErrorCode.IMAGE_BLURRY;
            case "NO_PERSON" -> BodyAnalysis.ErrorCode.NO_PERSON;
            case "MULTIPLE_PEOPLE" -> BodyAnalysis.ErrorCode.MULTIPLE_PEOPLE;
            case "NOT_FULL_BODY" -> BodyAnalysis.ErrorCode.NOT_FULL_BODY;
            case "NOT_FRONTAL" -> BodyAnalysis.ErrorCode.NOT_FRONTAL;
            case "AI_TIMEOUT" -> BodyAnalysis.ErrorCode.AI_TIMEOUT;
            default -> BodyAnalysis.ErrorCode.AI_FAILED;
        };
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> generateRoutines(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        User u = user(jwt);
        Long analysisId = Long.valueOf(body.get("analysis_id").toString());
        BodyAnalysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!analysis.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);

        List<Exercise> exercises = exerciseRepository.findByIsActiveTrueOrderByNameAsc(PageRequest.of(0, 100))
                .getContent();
        if (exercises.isEmpty()) throw new ApiException(ErrorCode.INTERNAL_ERROR);

        List<TargetPart> partOrder = priorityParts(analysis);
        List<Integer> dayOffsets = spreadDays(weeklyFrequency(u.getId()));
        List<Map<String, Object>> routines = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < dayOffsets.size(); i++) {
            LocalDate date = today.plusDays(dayOffsets.get(i));
            Routine existing = routineRepository.findByUserIdAndScheduledDate(u.getId(), date).orElse(null);
            if (existing != null) {
                if (existing.getStatus() != Routine.Status.planned) continue;
                routineItemRepository.deleteAll(routineItemRepository.findByRoutineIdOrderByOrderNoAsc(existing.getId()));
                routineRepository.delete(existing);
                routineRepository.flush();
            }

            TargetPart part = partOrder.get(i % partOrder.size());
            Routine r = Routine.builder()
                    .user(u)
                    .analysis(analysis)
                    .scheduledDate(date)
                    .title(partLabel(part) + " 강화 루틴")
                    .targetPart(part)
                    .estimatedMinutes(40)
                    .build();
            r = routineRepository.save(r);

            List<Exercise> partExercises = exercises.stream()
                    .filter(e -> e.getTargetPart() == part)
                    .limit(4)
                    .toList();
            if (partExercises.isEmpty()) partExercises = exercises.subList(0, Math.min(4, exercises.size()));

            for (int j = 0; j < partExercises.size(); j++) {
                routineItemRepository.save(RoutineItem.builder()
                        .routine(r)
                        .exercise(partExercises.get(j))
                        .orderNo(j + 1)
                        .targetSets(3)
                        .targetReps(10)
                        .reason(j == 0 ? itemReason(part, analysis) : partLabel(part) + " 강화에 효과적인 운동이에요.")
                        .build());
            }

            List<RoutineItem> items = routineItemRepository.findByRoutineIdOrderByOrderNoAsc(r.getId());
            Map<String, Object> routineMap = new LinkedHashMap<>();
            routineMap.put("routine_id", r.getId());
            routineMap.put("title", r.getTitle());
            routineMap.put("scheduled_date", r.getScheduledDate().toString());
            routineMap.put("items", items.stream().map(it -> Map.of("exercise", Map.of("name", it.getExercise().getName()))).toList());
            routines.add(routineMap);
        }

        return Map.of("routines", routines);
    }

    @org.springframework.beans.factory.annotation.Value("${vitality.analysis.min-seconds:0}")
    private int analysisMinSeconds;

    private void waitForMinimumDuration(long startedAtNanos) {
        long remainingMillis = analysisMinSeconds * 1000L - (System.nanoTime() - startedAtNanos) / 1_000_000L;
        if (remainingMillis <= 0) return;
        try {
            Thread.sleep(remainingMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final double TILT_CAUTION_DEG = 2.5;
    private static final int DEFAULT_WEEKLY_FREQUENCY = 3;

    private static double tilt(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private List<TargetPart> priorityParts(BodyAnalysis analysis) {
        double shoulder = tilt(analysis.getShoulderTiltDeg());
        double pelvis = tilt(analysis.getPelvisTiltDeg());
        List<TargetPart> shoulderParts = shoulder >= TILT_CAUTION_DEG
                ? List.of(TargetPart.shoulder, TargetPart.back) : List.of();
        List<TargetPart> pelvisParts = pelvis >= TILT_CAUTION_DEG
                ? List.of(TargetPart.core, TargetPart.leg) : List.of();
        List<TargetPart> order = new ArrayList<>();
        if (pelvis > shoulder) {
            order.addAll(pelvisParts);
            order.addAll(shoulderParts);
        } else {
            order.addAll(shoulderParts);
            order.addAll(pelvisParts);
        }
        for (TargetPart p : TargetPart.values()) {
            if (!order.contains(p)) order.add(p);
        }
        return order;
    }

    private int weeklyFrequency(Long userId) {
        Integer value = profileRepository.findFirstByUserIdOrderByIdDesc(userId)
                .map(p -> p.getWeeklyFrequency()).orElse(null);
        int days = value == null ? DEFAULT_WEEKLY_FREQUENCY : value;
        return Math.max(1, Math.min(7, days));
    }

    private List<Integer> spreadDays(int perWeek) {
        List<Integer> offsets = new ArrayList<>();
        for (int k = 0; k < perWeek; k++) offsets.add(Math.round(k * 7f / perWeek));
        return offsets;
    }

    private String itemReason(TargetPart part, BodyAnalysis analysis) {
        double shoulder = tilt(analysis.getShoulderTiltDeg());
        double pelvis = tilt(analysis.getPelvisTiltDeg());
        boolean shoulderPart = part == TargetPart.shoulder || part == TargetPart.back;
        boolean pelvisPart = part == TargetPart.core || part == TargetPart.leg;
        String reason = partLabel(part) + " 강화에 효과적인 운동이에요.";
        CorrectionExercise.TargetIssue issue = null;
        if (shoulderPart && shoulder >= TILT_CAUTION_DEG) {
            reason = String.format("어깨 기울기가 %.1f°로 확인되어 %s 강화를 우선했어요.", shoulder, partLabel(part));
            issue = CorrectionExercise.TargetIssue.shoulder_tilt;
        } else if (pelvisPart && pelvis >= TILT_CAUTION_DEG) {
            reason = String.format("골반 기울기가 %.1f°로 확인되어 %s 강화를 우선했어요.", pelvis, partLabel(part));
            issue = CorrectionExercise.TargetIssue.pelvis_tilt;
        }
        if (issue != null) {
            List<CorrectionExercise> corrections = correctionRepository.findByTargetIssueAndIsActiveTrue(issue);
            if (!corrections.isEmpty()) {
                CorrectionExercise c = corrections.get(0);
                reason = reason + " 교정: " + c.getName() + " " + c.getDurationOrReps();
            }
        }
        return reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    private String partLabel(TargetPart part) {
        return switch (part) {
            case chest -> "가슴";
            case back -> "등";
            case leg -> "하체";
            case shoulder -> "어깨";
            case arm -> "팔";
            case core -> "코어";
        };
    }

    private Map<String, Object> toSummary(BodyAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("analysis_id", a.getId());
        m.put("status", a.getStatus().name());
        m.put("created_at", KstTime.iso(a.getCreatedAt()));
        m.put("balance_score", a.getBalanceScore());
        m.put("body_type", a.getBodyType());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toDetail(BodyAnalysis a) {
        Map<String, Object> m = new LinkedHashMap<>(toSummary(a));
        m.put("error_code", a.getErrorCode() != null ? a.getErrorCode().name() : null);
        m.put("error_message", a.getErrorMessage());
        m.put("measurement_version", a.getMeasurementVersion());
        m.put("text_summary", a.getTextSummary());
        m.put("completed_at", KstTime.iso(a.getCompletedAt()));

        Map<String, Object> measures = new LinkedHashMap<>();
        measures.put("shoulder_tilt_deg", a.getShoulderTiltDeg());
        measures.put("pelvis_tilt_deg", a.getPelvisTiltDeg());
        measures.put("neck_forward_deg", a.getNeckForwardDeg());
        measures.put("shoulder_hip_ratio", a.getShoulderHipRatio());
        measures.put("torso_leg_ratio", a.getTorsoLegRatio());
        List<?> unavailable = List.of();
        if (a.getUnavailableJson() != null) {
            try { unavailable = objectMapper.readValue(a.getUnavailableJson(), List.class); } catch (Exception ignored) {}
        }
        measures.put("unavailable", unavailable);
        m.put("measures", measures);

        List<?> judgements = List.of();
        if (a.getJudgementsJson() != null) {
            try { judgements = objectMapper.readValue(a.getJudgementsJson(), List.class); } catch (Exception ignored) {}
        }
        m.put("judgements", judgements);
        if (a.getComparisonSummary() != null) {
            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("summary", a.getComparisonSummary());
            comparison.put("detail", a.getComparisonDetail() != null ? a.getComparisonDetail() : "");
            comparison.put("priority_parts", List.of());
            m.put("goal_comparison", comparison);
        } else {
            m.put("goal_comparison", null);
        }
        return m;
    }
}
