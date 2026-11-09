package com.asdf.board.be.workout.repository;

import com.asdf.board.be.workout.entity.WorkoutLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkoutLogRepository extends JpaRepository<WorkoutLog, Long> {

    @Query(value = "SELECT w FROM WorkoutLog w JOIN FETCH w.exercise WHERE w.user.id = :userId AND w.workoutDate BETWEEN :start AND :end AND (:status IS NULL OR w.status = :status) ORDER BY w.startedAt DESC",
           countQuery = "SELECT COUNT(w) FROM WorkoutLog w WHERE w.user.id = :userId AND w.workoutDate BETWEEN :start AND :end AND (:status IS NULL OR w.status = :status)")
    Page<WorkoutLog> findByUserIdAndDateRange(
            @Param("userId") Long userId,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end,
            @Param("status") WorkoutLog.Status status,
            Pageable pageable
    );

    Optional<WorkoutLog> findByUserIdAndExerciseIdAndStatus(Long userId, Long exerciseId, WorkoutLog.Status status);

    List<WorkoutLog> findByRoutineIdAndStatus(Long routineId, WorkoutLog.Status status);

    List<WorkoutLog> findByUserIdAndStatus(Long userId, WorkoutLog.Status status);
}
