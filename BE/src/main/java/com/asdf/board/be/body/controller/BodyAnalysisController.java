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
import com.asdf.board.be.user.entity.User;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
        int limit = 5;
        LocalDateTime resetAt = LocalDateTime.now().withDayOfMonth(1).plusMonths(1).withHour(0).withMinute(0).withSecond(0);
        int used = analysisRepository.countByUserIdAndCreatedAtAfter(userId(jwt),
                LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0));
        return Map.of(
                "used", used,
                "limit", limit,
                "remaining", Math.max(0, limit - used),
                "reset_at", resetAt.toString()
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
                .balanceScore(70)
                .bodyType("분석 중")
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
            try {
                Map<String, Object> ai = aiClient.analyze(photo.getStorageKey(),
                        sidePhoto != null ? sidePhoto.getStorageKey() : null);
                applyAiResult(pending, ai);
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
                .balanceScore(75).bodyType("균형형")
                .textSummary("전반적으로 균형 잡힌 체형입니다.")
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
            a.setBodyType(shoulderHip >= 1.35 ? "역삼각형" : shoulderHip >= 0.95 ? "균형형" : "직사각형");
        }
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

        List<Exercise> exercises = exerciseRepository.findByIsActiveTrueOrderByNameAsc(PageRequest.of(0, 10))
                .getContent();
        if (exercises.isEmpty()) throw new ApiException(ErrorCode.INTERNAL_ERROR);

        TargetPart[] parts = TargetPart.values();
        List<Map<String, Object>> routines = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < Math.min(7, parts.length); i++) {
            LocalDate date = today.plusDays(i);
            if (routineRepository.findByUserIdAndScheduledDate(u.getId(), date).isPresent()) continue;

            TargetPart part = parts[i % parts.length];
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
                        .reason(partLabel(part) + " 강화에 효과적인 운동이에요.")
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
        m.put("created_at", a.getCreatedAt().toString());
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
        m.put("completed_at", a.getCompletedAt() != null ? a.getCompletedAt().toString() : null);

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
        m.put("goal_comparison", null);
        return m;
    }
}
