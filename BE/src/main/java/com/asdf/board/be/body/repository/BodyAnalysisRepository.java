package com.asdf.board.be.body.repository;

import com.asdf.board.be.body.entity.BodyAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;

public interface BodyAnalysisRepository extends JpaRepository<BodyAnalysis, Long> {
    Page<BodyAnalysis> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    int countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime after);

    int countByUserIdAndCreatedAtGreaterThanEqualAndStatusIn(Long userId, LocalDateTime start, Collection<BodyAnalysis.Status> statuses);
}
