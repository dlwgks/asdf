package com.asdf.board.be.correction.repository;

import com.asdf.board.be.correction.entity.CorrectionExercise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorrectionExerciseRepository extends JpaRepository<CorrectionExercise, Long> {

    List<CorrectionExercise> findByTargetIssueAndIsActiveTrue(CorrectionExercise.TargetIssue targetIssue);
}
