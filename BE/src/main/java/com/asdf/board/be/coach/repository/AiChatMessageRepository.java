package com.asdf.board.be.coach.repository;

import com.asdf.board.be.coach.entity.AiChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiChatMessageRepository extends JpaRepository<AiChatMessage, Long> {
    List<AiChatMessage> findTop20ByUser_IdOrderByCreatedAtDesc(Long userId);

    List<AiChatMessage> findTop10ByUser_IdOrderByCreatedAtDesc(Long userId);

    List<AiChatMessage> findByUser_Id(Long userId);
}
