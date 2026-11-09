package com.asdf.board.be.coach.controller;

import com.asdf.board.be.body.entity.BodyAnalysis;
import com.asdf.board.be.body.repository.BodyAnalysisRepository;
import com.asdf.board.be.coach.entity.AiChatMessage;
import com.asdf.board.be.coach.repository.AiChatMessageRepository;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.global.time.KstTime;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserProfileRepository;
import com.asdf.board.be.user.repository.UserRepository;
import com.asdf.board.be.workout.entity.Routine;
import com.asdf.board.be.workout.entity.WorkoutLog;
import com.asdf.board.be.workout.entity.WorkoutSet;
import com.asdf.board.be.workout.repository.RoutineItemRepository;
import com.asdf.board.be.workout.repository.RoutineRepository;
import com.asdf.board.be.workout.repository.WorkoutLogRepository;
import com.asdf.board.be.workout.repository.WorkoutSetRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Tag(name = "coach", description = "AI 코치 채팅")
@RestController
@RequestMapping("/api/ai-coach")
@RequiredArgsConstructor
@Transactional
public class AiCoachController {

    private static final String SYSTEM_PROMPT =
        "당신은 체형 분석 전문 AI 피트니스 코치입니다. 아래 회원 정보를 참고해 맞춤형 운동과 건강 관리 조언을 한국어로 제공합니다. " +
        "응답은 따뜻하고 친근하게, 300자 이내로 간결하게 해주세요. 회원 정보에 없는 내용은 지어내지 말고, " +
        "의학적 진단은 하지 않으며 통증이나 질병이 언급되면 전문 의료인 상담을 권장합니다.";

    private final AiChatMessageRepository chatRepository;
    private final UserRepository userRepository;
    private final BodyAnalysisRepository analysisRepository;
    private final UserProfileRepository profileRepository;
    private final RoutineRepository routineRepository;
    private final RoutineItemRepository routineItemRepository;
    private final WorkoutLogRepository workoutLogRepository;
    private final WorkoutSetRepository workoutSetRepository;

    @Value("${vitality.coach.server-url:}")
    private String coachServerUrl;

    private final RestTemplate restTemplate = coachRestTemplate();

    private static RestTemplate coachRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(90));
        return new RestTemplate(factory);
    }

    @GetMapping("/messages")
    public Map<String, Object> getMessages(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return Map.of("messages", recent(chatRepository.findTop20ByUser_IdOrderByCreatedAtDesc(userId)).stream().map(this::toMap).toList());
    }

    @PostMapping("/messages")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        String content = (String) body.get("content");
        if (content == null || content.isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED);
        if (content.length() > 500) throw new ApiException(ErrorCode.VALIDATION_FAILED);

        List<AiChatMessage> history = recent(chatRepository.findTop10ByUser_IdOrderByCreatedAtDesc(userId));
        String reply = askCoach(history, userId, content);

        AiChatMessage userMsg = chatRepository.save(AiChatMessage.builder()
                .user(user).role(AiChatMessage.Role.user).content(content).build());
        AiChatMessage assistantMsg = chatRepository.save(AiChatMessage.builder()
                .user(user).role(AiChatMessage.Role.assistant).content(reply).build());

        return Map.of(
                "user_message", toMap(userMsg),
                "assistant_message", toMap(assistantMsg)
        );
    }

    @DeleteMapping("/messages")
    public Map<String, Object> clearMessages(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        List<AiChatMessage> msgs = chatRepository.findByUser_Id(userId);
        chatRepository.deleteAll(msgs);
        return Map.of("deleted", msgs.size());
    }

    private List<AiChatMessage> recent(List<AiChatMessage> newestFirst) {
        List<AiChatMessage> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);
        return ordered;
    }

    @SuppressWarnings("unchecked")
    private String askCoach(List<AiChatMessage> history, Long userId, String userContent) {
        if (coachServerUrl == null || coachServerUrl.isBlank()) {
            log.warn("Coach server is not configured (vitality.coach.server-url)");
            throw new ApiException(ErrorCode.AI_UNAVAILABLE);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiChatMessage m : history) {
            messages.add(Map.of("role", m.getRole().name(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userContent));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("system", SYSTEM_PROMPT + "\n\n[회원 정보]\n" + buildMemberContext(userId));
        request.put("messages", messages);

        try {
            Map<String, Object> response = restTemplate.postForObject(coachServerUrl + "/v1/chat", request, Map.class);
            Object reply = response != null ? response.get("reply") : null;
            if (!(reply instanceof String text) || text.isBlank()) throw new IllegalStateException("empty reply");
            return text;
        } catch (Exception e) {
            log.warn("Coach server call failed: {}", e.getMessage());
            throw new ApiException(ErrorCode.AI_UNAVAILABLE);
        }
    }

    private String buildMemberContext(Long userId) {
        StringBuilder sb = new StringBuilder();
        profileRepository.findFirstByUserIdOrderByIdDesc(userId).ifPresent(p -> {
            sb.append("- 신체정보: 키 ").append(p.getHeightCm()).append("cm, 몸무게 ").append(p.getWeightKg())
              .append("kg, 나이 ").append(p.getAge()).append("세, 성별 ").append(p.getGender())
              .append(", 목표 ").append(p.getGoalType()).append(", 주 ").append(p.getWeeklyFrequency())
              .append("회 운동, 경험 ").append(p.getExperienceLevel()).append("\n");
        });
        var analyses = analysisRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1));
        if (!analyses.isEmpty()) {
            BodyAnalysis a = analyses.getContent().get(0);
            if (a.getStatus() == BodyAnalysis.Status.done) {
                sb.append("- 최근 체형 분석:");
                if (a.getBodyType() != null) sb.append(" 체형 ").append(a.getBodyType()).append(",");
                if (a.getBalanceScore() != null) sb.append(" 균형 점수 ").append(a.getBalanceScore()).append("점,");
                if (a.getShoulderTiltDeg() != null) sb.append(" 어깨 기울기 ").append(a.getShoulderTiltDeg()).append("도,");
                if (a.getPelvisTiltDeg() != null) sb.append(" 골반 기울기 ").append(a.getPelvisTiltDeg()).append("도,");
                if (a.getTextSummary() != null) sb.append(" 요약: ").append(a.getTextSummary());
                sb.append("\n");
            }
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Optional<Routine> routine = routineRepository.findByUserIdAndScheduledDate(userId, today);
        routine.ifPresent(r -> {
            List<String> names = routineItemRepository.findByRoutineIdOrderByOrderNoAsc(r.getId()).stream()
                    .map(it -> it.getExercise().getName() + " " + it.getTargetSets() + "세트 " + it.getTargetReps() + "회").toList();
            sb.append("- 오늘 루틴: ").append(r.getTitle()).append(" (").append(String.join(", ", names)).append(")\n");
        });
        sb.append(completedTodaySummary(userId, today));
        return sb.length() == 0 ? "- 등록된 정보가 없어요.\n" : sb.toString();
    }

    private String completedTodaySummary(Long userId, LocalDate today) {
        List<WorkoutLog> logs = workoutLogRepository
                .findByUserIdAndDateRange(userId, today, today, WorkoutLog.Status.completed, PageRequest.of(0, 20))
                .getContent();
        if (logs.isEmpty()) return "- 오늘 완료한 운동: 없음\n";
        List<String> parts = new ArrayList<>();
        for (WorkoutLog log : logs) {
            List<WorkoutSet> doneSets = workoutSetRepository.findByLogIdOrderBySetNoAsc(log.getId()).stream()
                    .filter(WorkoutSet::getIsDone).toList();
            double volume = doneSets.stream().mapToDouble(s -> s.getWeightKg().doubleValue() * s.getReps()).sum();
            parts.add(log.getExercise().getName() + " " + doneSets.size() + "세트(총 볼륨 " + Math.round(volume) + "kg)");
        }
        return "- 오늘 완료한 운동: " + String.join(", ", parts) + "\n";
    }

    private Map<String, Object> toMap(AiChatMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message_id", m.getId());
        map.put("role", m.getRole().name());
        map.put("content", m.getContent());
        map.put("created_at", KstTime.iso(m.getCreatedAt()));
        return map;
    }
}
