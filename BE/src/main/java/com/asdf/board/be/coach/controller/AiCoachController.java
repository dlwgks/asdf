package com.asdf.board.be.coach.controller;

import com.asdf.board.be.body.entity.BodyAnalysis;
import com.asdf.board.be.body.repository.BodyAnalysisRepository;
import com.asdf.board.be.coach.entity.AiChatMessage;
import com.asdf.board.be.coach.repository.AiChatMessageRepository;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import tools.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Tag(name = "coach", description = "AI 코치 채팅")
@RestController
@RequestMapping("/api/ai-coach")
@RequiredArgsConstructor
@Transactional
public class AiCoachController {

    private final AiChatMessageRepository chatRepository;
    private final UserRepository userRepository;
    private final BodyAnalysisRepository analysisRepository;

    @Value("${vitality.anthropic.api-key:}")
    private String anthropicApiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
        "당신은 체형 분석 전문 AI 피트니스 코치입니다. 사용자의 체형 분석 데이터를 바탕으로 맞춤형 운동과 건강 관리 조언을 한국어로 제공합니다. " +
        "응답은 따뜻하고 친근하게, 300자 이내로 간결하게 해주세요. 의학적 진단은 하지 않으며, 전문 의료인 상담을 권장하는 경우 명시합니다.";

    @GetMapping("/messages")
    public Map<String, Object> getMessages(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        List<AiChatMessage> messages = chatRepository.findTop20ByUser_IdOrderByCreatedAtAsc(userId);
        return Map.of("messages", messages.stream().map(this::toMap).toList());
    }

    @PostMapping("/messages")
    public Map<String, Object> sendMessage(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        String content = (String) body.get("content");
        if (content == null || content.isBlank()) throw new ApiException(ErrorCode.VALIDATION_FAILED);
        if (content.length() > 500) throw new ApiException(ErrorCode.VALIDATION_FAILED);

        List<AiChatMessage> historyBeforeSave = chatRepository.findTop20ByUser_IdOrderByCreatedAtAsc(userId);

        AiChatMessage userMsg = chatRepository.save(AiChatMessage.builder()
                .user(user).role(AiChatMessage.Role.user).content(content).build());

        String reply = generateReply(historyBeforeSave, userId, content);

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
        List<AiChatMessage> msgs = chatRepository.findTop20ByUser_IdOrderByCreatedAtAsc(userId);
        chatRepository.deleteAll(msgs);
        return Map.of("deleted", msgs.size());
    }

    private String generateReply(List<AiChatMessage> history, Long userId, String userContent) {
        String analysisContext = buildAnalysisContext(userId);

        if (!anthropicApiKey.isBlank()) {
            try {
                return callClaude(history, userContent, analysisContext);
            } catch (Exception e) {
                log.warn("Anthropic API call failed: {}", e.getMessage());
            }
        }
        return buildFallbackReply(userContent, analysisContext);
    }

    private String buildAnalysisContext(Long userId) {
        var analyses = analysisRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1));
        if (analyses.isEmpty()) return "";
        BodyAnalysis a = analyses.getContent().get(0);
        if (a.getStatus() != BodyAnalysis.Status.done) return "";
        StringBuilder sb = new StringBuilder("[최근 체형 분석 결과] ");
        if (a.getBodyType() != null) sb.append("체형: ").append(a.getBodyType()).append(", ");
        if (a.getBalanceScore() != null) sb.append("균형 점수: ").append(a.getBalanceScore()).append("점, ");
        if (a.getShoulderTiltDeg() != null) sb.append("어깨 기울기: ").append(a.getShoulderTiltDeg()).append("도, ");
        if (a.getPelvisTiltDeg() != null) sb.append("골반 기울기: ").append(a.getPelvisTiltDeg()).append("도");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String callClaude(List<AiChatMessage> history, String userContent, String analysisContext) throws Exception {
        List<Map<String, String>> messages = new ArrayList<>();
        for (AiChatMessage m : history) {
            messages.add(Map.of("role", m.getRole().name(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", userContent));

        String systemWithContext = SYSTEM_PROMPT + (analysisContext.isEmpty() ? "" : "\n\n" + analysisContext);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "claude-haiku-4-5");
        request.put("max_tokens", 512);
        request.put("system", systemWithContext);
        request.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", anthropicApiKey);
        headers.set("anthropic-version", "2023-06-01");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.anthropic.com/v1/messages",
                new HttpEntity<>(objectMapper.writeValueAsString(request), headers),
                String.class);

        Map<String, Object> resp = objectMapper.readValue(response.getBody(), Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.get("content");
        return (String) content.get(0).get("text");
    }

    private String buildFallbackReply(String userContent, String analysisContext) {
        String lower = userContent.toLowerCase();
        if (lower.contains("운동") || lower.contains("루틴")) {
            return "꾸준한 운동이 체형 개선의 핵심이에요! " +
                (analysisContext.isEmpty() ? "체형 분석을 먼저 진행해보시면 맞춤 루틴을 추천드릴 수 있어요." :
                analysisContext + " 이 데이터를 바탕으로 어깨와 코어 강화 운동을 권장해요.");
        }
        if (lower.contains("어깨") || lower.contains("등")) {
            return "어깨와 등 건강을 위해 하루 10분 스트레칭을 추천해요. 랫풀다운, 시티드 로우, 페이스풀 운동이 효과적입니다.";
        }
        if (lower.contains("다이어트") || lower.contains("체중") || lower.contains("살")) {
            return "체중 관리는 운동과 식이요법의 균형이 중요해요. 체형 분석을 통해 현재 상태를 파악하고 단계적으로 접근해보세요!";
        }
        return "안녕하세요! 저는 AI 피트니스 코치예요 💪 체형, 운동, 건강에 관해 무엇이든 물어보세요!" +
            (analysisContext.isEmpty() ? " 먼저 체형 분석을 진행하면 더 정확한 조언을 드릴 수 있어요." : "");
    }

    private Map<String, Object> toMap(AiChatMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message_id", m.getId());
        map.put("role", m.getRole().name());
        map.put("content", m.getContent());
        map.put("created_at", m.getCreatedAt().toString());
        return map;
    }
}
