package com.asdf.board.be.workout.controller;

import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.global.time.KstTime;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import com.asdf.board.be.workout.entity.Exercise;
import com.asdf.board.be.workout.entity.Routine;
import com.asdf.board.be.workout.entity.WorkoutLog;
import com.asdf.board.be.workout.entity.WorkoutSet;
import com.asdf.board.be.workout.repository.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Tag(name = "workout", description = "운동")
@RestController
@RequestMapping("/api/workout-logs")
@RequiredArgsConstructor
@Transactional
public class WorkoutLogController {

    private final WorkoutLogRepository logRepository;
    private final WorkoutSetRepository setRepository;
    private final ExerciseRepository exerciseRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;

    private Long userId(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    private User user(Jwt jwt) {
        return userRepository.findById(userId(jwt))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(name = "start_date") String startDate,
            @RequestParam(name = "end_date") String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal Jwt jwt
    ) {
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        WorkoutLog.Status st = status != null ? WorkoutLog.Status.valueOf(status) : null;
        var pageable = PageRequest.of(page - 1, pageSize);
        var pageData = logRepository.findByUserIdAndDateRange(userId(jwt), start, end, st, pageable);

        List<Map<String, Object>> items = pageData.getContent().stream()
                .map(log -> logToMap(log, false))
                .toList();

        int totalSets = items.stream().mapToInt(m -> (int) m.getOrDefault("set_count", 0)).sum();
        double totalVolume = items.stream().mapToDouble(m -> ((Number) m.getOrDefault("total_volume_kg", 0)).doubleValue()).sum();
        Set<String> dates = new HashSet<>();
        for (var m : items) {
            Object d = m.get("workout_date");
            if (d != null) dates.add(d.toString());
        }

        return Map.of(
                "items", items,
                "page", page,
                "total", pageData.getTotalElements(),
                "summary", Map.of(
                        "log_count", pageData.getTotalElements(),
                        "total_sets", totalSets,
                        "total_volume_kg", totalVolume,
                        "workout_dates", dates.stream().sorted().toList()
                )
        );
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> start(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Long exerciseId = Long.valueOf(body.get("exercise_id").toString());
        Long routineId = body.get("routine_id") != null ? Long.valueOf(body.get("routine_id").toString()) : null;

        Exercise exercise = exerciseRepository.findById(exerciseId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        User u = user(jwt);

        Optional<WorkoutLog> existing = logRepository.findByUserIdAndExerciseIdAndStatus(
                userId(jwt), exerciseId, WorkoutLog.Status.in_progress);
        if (existing.isPresent()) {
            return Map.of("log_id", existing.get().getId());
        }

        Routine routine = routineId != null ? routineRepository.findById(routineId).orElse(null) : null;
        LocalDate today = LocalDate.now();
        WorkoutLog log = WorkoutLog.builder()
                .user(u)
                .exercise(exercise)
                .routine(routine)
                .workoutDate(today)
                .startedAt(LocalDateTime.now())
                .build();
        log = logRepository.save(log);
        return Map.of("log_id", log.getId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        WorkoutLog log = logRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!log.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);
        return logToMap(log, true);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        WorkoutLog log = logRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!log.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);
        log.setStatus(WorkoutLog.Status.aborted);
        log.setCompletedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    @PatchMapping("/{id}/complete")
    public Map<String, Object> complete(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        WorkoutLog log = logRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!log.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);

        String memo = body != null ? (String) body.get("memo") : null;
        LocalDateTime now = LocalDateTime.now();
        long durationSec = java.time.Duration.between(log.getStartedAt(), now).getSeconds();

        log.setStatus(WorkoutLog.Status.completed);
        log.setCompletedAt(now);
        log.setDurationSec((int) durationSec);
        if (memo != null) log.setMemo(memo);
        logRepository.save(log);

        List<WorkoutSet> sets = setRepository.findByLogIdOrderBySetNoAsc(id);
        double volume = sets.stream().filter(WorkoutSet::getIsDone)
                .mapToDouble(s -> s.getWeightKg().doubleValue() * s.getReps()).sum();

        return Map.of(
                "log_id", log.getId(),
                "duration_sec", (int) durationSec,
                "total_sets", sets.size(),
                "total_volume_kg", volume
        );
    }

    @PostMapping("/{id}/sets")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addSet(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        WorkoutLog log = logRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!log.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);

        if (log.getStatus() != WorkoutLog.Status.in_progress) throw new ApiException(ErrorCode.WORKOUT_ALREADY_COMPLETED);

        String clientSetId = (String) body.get("client_set_id");
        int setNo = setRepository.countByLogId(id) + 1;
        BigDecimal weight = parseWeight(body.get("weight_kg"));
        int reps = parseReps(body.get("reps"));
        Integer rest = parseRest(body.get("rest_sec"));

        WorkoutSet set = WorkoutSet.builder()
                .log(log)
                .clientSetId(clientSetId != null ? clientSetId : UUID.randomUUID().toString())
                .setNo(setNo)
                .weightKg(weight)
                .reps(reps)
                .restSec(rest)
                .isDone(true)
                .version(1)
                .build();
        set = setRepository.save(set);
        return setToMap(set);
    }

    @PatchMapping("/{id}/sets/{setId}")
    public Map<String, Object> updateSet(@PathVariable Long id, @PathVariable Long setId, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        WorkoutLog log = logRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!log.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);

        WorkoutSet set = setRepository.findByLogIdAndId(id, setId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));

        if (body.containsKey("weight_kg")) set.setWeightKg(parseWeight(body.get("weight_kg")));
        if (body.containsKey("reps")) set.setReps(parseReps(body.get("reps")));
        if (body.containsKey("rest_sec")) set.setRestSec(parseRest(body.get("rest_sec")));
        if (body.containsKey("is_done")) {
            if (!(body.get("is_done") instanceof Boolean done)) throw new ApiException(ErrorCode.VALIDATION_FAILED);
            set.setIsDone(done);
        }
        set.setVersion(set.getVersion() + 1);
        return setToMap(setRepository.save(set));
    }

    @DeleteMapping("/{id}/sets/{setId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSet(@PathVariable Long id, @PathVariable Long setId, @AuthenticationPrincipal Jwt jwt) {
        WorkoutLog log = logRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!log.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);
        WorkoutSet set = setRepository.findByLogIdAndId(id, setId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        setRepository.delete(set);
    }

    private Map<String, Object> logToMap(WorkoutLog log, boolean includeSets) {
        List<WorkoutSet> sets = includeSets ? setRepository.findByLogIdOrderBySetNoAsc(log.getId()) : List.of();
        double volume = sets.stream().filter(WorkoutSet::getIsDone)
                .mapToDouble(s -> s.getWeightKg().doubleValue() * s.getReps()).sum();

        Map<String, Object> exerciseMap = Map.of(
                "exercise_id", log.getExercise().getId(),
                "name", log.getExercise().getName(),
                "target_part", log.getExercise().getTargetPart().name(),
                "equipment", log.getExercise().getEquipment().name()
        );

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("log_id", log.getId());
        m.put("exercise", exerciseMap);
        m.put("routine_id", log.getRoutine() != null ? log.getRoutine().getId() : null);
        m.put("status", log.getStatus().name());
        m.put("workout_date", log.getWorkoutDate().toString());
        m.put("started_at", KstTime.iso(log.getStartedAt()));
        m.put("completed_at", KstTime.iso(log.getCompletedAt()));
        m.put("duration_sec", log.getDurationSec());
        m.put("memo", log.getMemo());
        m.put("set_count", includeSets ? sets.size() : 0);
        m.put("total_volume_kg", volume);
        if (includeSets) m.put("sets", sets.stream().map(this::setToMap).toList());
        return m;
    }

    private Map<String, Object> setToMap(WorkoutSet s) {
        return Map.of(
                "set_id", s.getId(),
                "client_set_id", s.getClientSetId(),
                "set_no", s.getSetNo(),
                "weight_kg", s.getWeightKg(),
                "reps", s.getReps(),
                "rest_sec", s.getRestSec() != null ? s.getRestSec() : 0,
                "is_done", s.getIsDone(),
                "version", s.getVersion()
        );
    }

    private BigDecimal parseWeight(Object value) {
        try {
            BigDecimal weight = new BigDecimal(String.valueOf(value));
            if (weight.signum() < 0 || weight.compareTo(new BigDecimal("1000")) > 0) throw new ApiException(ErrorCode.VALIDATION_FAILED);
            return weight;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private int parseReps(Object value) {
        try {
            int reps = Integer.parseInt(String.valueOf(value));
            if (reps < 1 || reps > 1000) throw new ApiException(ErrorCode.VALIDATION_FAILED);
            return reps;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private Integer parseRest(Object value) {
        if (value == null) return null;
        try {
            int rest = Integer.parseInt(String.valueOf(value));
            if (rest < 0 || rest > 3600) throw new ApiException(ErrorCode.VALIDATION_FAILED);
            return rest;
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
