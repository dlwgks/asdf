package com.asdf.board.be.body.controller;

import com.asdf.board.be.body.entity.GoalBody;
import com.asdf.board.be.body.entity.Photo;
import com.asdf.board.be.body.repository.GoalBodyRepository;
import com.asdf.board.be.body.repository.PhotoRepository;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import com.asdf.board.be.global.time.KstTime;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "body", description = "체형 분석")
@RestController
@RequestMapping("/api/goal-bodies")
@RequiredArgsConstructor
@Transactional
public class GoalBodyController {

    private final GoalBodyRepository goalBodyRepository;
    private final UserRepository userRepository;
    private final PhotoRepository photoRepository;

    @GetMapping
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return goalBodyRepository.findTopByUserIdOrderByCreatedAtDesc(userId)
                .map(this::toMap)
                .orElseThrow(() -> new ApiException(ErrorCode.GOAL_NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        String description = (String) body.get("description");
        Photo sourcePhoto = resolvePhoto(body, userId);
        String summary = buildSummary(description);

        GoalBody goal = goalBodyRepository.findTopByUserIdOrderByCreatedAtDesc(userId).orElse(null);
        if (goal != null) {
            if (description != null) goal.setDescription(description);
            if (sourcePhoto != null) goal.setSourcePhoto(sourcePhoto);
            goal.setSummary(summary);
            goal.setStatus(GoalBody.Status.done);
        } else {
            User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
            goal = GoalBody.builder()
                    .user(user)
                    .status(GoalBody.Status.done)
                    .description(description)
                    .summary(summary)
                    .sourcePhoto(sourcePhoto)
                    .build();
        }
        return toMap(goalBodyRepository.save(goal));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        GoalBody goal = goalBodyRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!goal.getUser().getId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN);
        if (body.containsKey("description")) goal.setDescription((String) body.get("description"));
        Photo sourcePhoto = resolvePhoto(body, userId);
        if (sourcePhoto != null) goal.setSourcePhoto(sourcePhoto);
        goal.setSummary(buildSummary(goal.getDescription()));
        return toMap(goalBodyRepository.save(goal));
    }

    private Photo resolvePhoto(Map<String, Object> body, Long userId) {
        Object raw = body.get("photo_id");
        if (raw == null) return null;
        long photoId = raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(raw.toString());
        Photo photo = photoRepository.findById(photoId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!photo.getUser().getId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN);
        return photo;
    }

    private String buildSummary(String description) {
        return "목표 체형: " + (description != null ? description : "");
    }

    private Map<String, Object> toMap(GoalBody g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("goal_body_id", g.getId());
        m.put("status", g.getStatus().name());
        m.put("description", g.getDescription());
        m.put("summary", g.getSummary());
        m.put("error_message", g.getErrorMessage());
        m.put("created_at", KstTime.iso(g.getCreatedAt()));
        return m;
    }
}
