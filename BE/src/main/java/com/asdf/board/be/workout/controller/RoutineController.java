package com.asdf.board.be.workout.controller;

import com.asdf.board.be.body.controller.BodyAnalysisController;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.workout.entity.Routine;
import com.asdf.board.be.workout.entity.RoutineItem;
import com.asdf.board.be.workout.entity.WorkoutLog;
import com.asdf.board.be.workout.repository.RoutineItemRepository;
import com.asdf.board.be.workout.repository.WorkoutLogRepository;
import com.asdf.board.be.workout.repository.RoutineRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Tag(name = "workout", description = "운동")
@RestController
@RequestMapping("/api/routines")
@RequiredArgsConstructor
@Transactional
public class RoutineController {

    private final RoutineRepository routineRepository;
    private final RoutineItemRepository itemRepository;
    private final WorkoutLogRepository workoutLogRepository;
    private final BodyAnalysisController bodyAnalysisController;

    private Long userId(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        return bodyAnalysisController.generateRoutines(body, jwt);
    }

    @GetMapping("/today")
    public Map<String, Object> today(@AuthenticationPrincipal Jwt jwt) {
        LocalDate today = LocalDate.now();
        Optional<Routine> opt = routineRepository.findByUserIdAndScheduledDate(userId(jwt), today);
        if (opt.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("routine", null);
            empty.put("is_rest_day", true);
            return empty;
        }
        return Map.of("routine", routineToMap(opt.get(), true), "is_rest_day", false);
    }

    @GetMapping("/weekly")
    public Map<String, Object> weekly(
            @RequestParam(name = "week_start") String weekStart,
            @AuthenticationPrincipal Jwt jwt
    ) {
        LocalDate monday = LocalDate.parse(weekStart);
        LocalDate sunday = monday.plusDays(6);
        List<Routine> routines = routineRepository.findByUserIdAndDateRange(userId(jwt), monday, sunday);

        Map<LocalDate, Routine> byDate = new LinkedHashMap<>();
        for (Routine r : routines) byDate.put(r.getScheduledDate(), r);

        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            Routine r = byDate.get(date);
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("is_rest_day", r == null);
            if (r != null) {
                List<RoutineItem> items = itemRepository.findByRoutineIdOrderByOrderNoAsc(r.getId());
                Set<Long> doneExercises = doneExerciseIds(r.getId());
                long doneCount = items.stream().filter(it -> doneExercises.contains(it.getExercise().getId())).count();
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("routine_id", r.getId());
                summary.put("title", r.getTitle());
                summary.put("target_part", r.getTargetPart().name());
                summary.put("estimated_minutes", r.getEstimatedMinutes());
                summary.put("status", r.getStatus().name());
                summary.put("item_count", items.size());
                summary.put("done_count", (int) doneCount);
                day.put("routine", summary);
            } else {
                day.put("routine", null);
            }
            days.add(day);
        }

        return Map.of("week_start", monday.toString(), "days", days);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        Routine routine = routineRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!routine.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);
        return routineToMap(routine, true);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> setStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Routine routine = routineRepository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!routine.getUser().getId().equals(userId(jwt))) throw new ApiException(ErrorCode.FORBIDDEN);
        String status = (String) body.get("status");
        routine.setStatus(Routine.Status.valueOf(status));
        routineRepository.save(routine);
        return routineToMap(routine, true);
    }

    private Map<String, Object> routineToMap(Routine r, boolean includeItems) {
        List<RoutineItem> items = includeItems ? itemRepository.findByRoutineIdOrderByOrderNoAsc(r.getId()) : List.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("routine_id", r.getId());
        m.put("analysis_id", r.getAnalysis().getId());
        m.put("scheduled_date", r.getScheduledDate().toString());
        m.put("title", r.getTitle());
        m.put("target_part", r.getTargetPart().name());
        m.put("estimated_minutes", r.getEstimatedMinutes());
        m.put("status", r.getStatus().name());
        if (includeItems) {
            Set<Long> doneExercises = doneExerciseIds(r.getId());
            m.put("items", items.stream().map(it -> itemToMap(it, doneExercises.contains(it.getExercise().getId()))).toList());
        }
        return m;
    }

    private Set<Long> doneExerciseIds(Long routineId) {
        Set<Long> ids = new HashSet<>();
        for (WorkoutLog l : workoutLogRepository.findByRoutineIdAndStatus(routineId, WorkoutLog.Status.completed)) {
            ids.add(l.getExercise().getId());
        }
        return ids;
    }

    private Map<String, Object> itemToMap(RoutineItem item, boolean done) {
        Map<String, Object> exerciseMap = new LinkedHashMap<>();
        exerciseMap.put("exercise_id", item.getExercise().getId());
        exerciseMap.put("name", item.getExercise().getName());
        exerciseMap.put("target_part", item.getExercise().getTargetPart().name());
        exerciseMap.put("equipment", item.getExercise().getEquipment().name());
        exerciseMap.put("difficulty", item.getExercise().getDifficulty());
        exerciseMap.put("description", item.getExercise().getDescription());
        exerciseMap.put("media_url", item.getExercise().getMediaUrl());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_id", item.getId());
        m.put("order_no", item.getOrderNo());
        m.put("exercise", exerciseMap);
        m.put("target_sets", item.getTargetSets());
        m.put("target_reps", item.getTargetReps());
        m.put("rest_sec", 90);
        m.put("target_weight_kg", item.getTargetWeightKg());
        m.put("reason", item.getReason());
        m.put("done", done);
        return m;
    }
}
