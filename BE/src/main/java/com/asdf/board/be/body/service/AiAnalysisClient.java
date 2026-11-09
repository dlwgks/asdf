package com.asdf.board.be.body.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
@Service
public class AiAnalysisClient {

    @Value("${vitality.ai.server-url:}")
    private String aiServerUrl;

    @Value("${vitality.upload.dir:uploads}")
    private String uploadDir;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isConfigured() {
        return aiServerUrl != null && !aiServerUrl.isBlank();
    }

    public Map<String, Object> analyze(String storageKey, String sideStorageKey) throws AiAnalysisException {
        if (!isConfigured()) return null;

        try {
            Path root = Paths.get(uploadDir).toAbsolutePath();
            byte[] frontBytes = Files.readAllBytes(root.resolve(storageKey));

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("front_images", namedResource(frontBytes, storageKey));

            if (sideStorageKey != null) {
                byte[] sideBytes = Files.readAllBytes(root.resolve(sideStorageKey));
                body.add("side_images", namedResource(sideBytes, sideStorageKey));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    aiServerUrl + "/v1/analyze",
                    new HttpEntity<>(body, headers),
                    String.class
            );

            return objectMapper.readValue(response.getBody(), new TypeReference<>() {});

        } catch (HttpClientErrorException e) {
            String body = e.getResponseBodyAsString();
            throw new AiAnalysisException(extractErrorCode(body), extractErrorMessage(body));
        } catch (ResourceAccessException e) {
            throw new AiAnalysisException("AI_TIMEOUT", "AI 서버 연결 시간이 초과됐습니다.");
        } catch (IOException e) {
            throw new AiAnalysisException("AI_FAILED", "AI 서버 호출 중 오류가 발생했습니다.");
        }
    }

    public Integer similarity(String frontStorageKey, String goalStorageKey) {
        if (!isConfigured() || frontStorageKey == null || goalStorageKey == null) return null;
        try {
            Path root = Paths.get(uploadDir).toAbsolutePath();
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("front_image", namedResource(Files.readAllBytes(root.resolve(frontStorageKey)), frontStorageKey));
            body.add("goal_image", namedResource(Files.readAllBytes(root.resolve(goalStorageKey)), goalStorageKey));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    aiServerUrl + "/v1/similarity", new HttpEntity<>(body, headers), String.class);
            Map<String, Object> parsed = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
            return parsed.get("score") instanceof Number n ? n.intValue() : null;
        } catch (Exception e) {
            log.warn("Goal similarity failed: {}", e.getMessage());
            return null;
        }
    }

    private ByteArrayResource namedResource(byte[] bytes, String key) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return key;
            }
        };
    }

    private String extractErrorCode(String body) {
        try {
            Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
            Object detail = parsed.get("detail");
            if (detail instanceof Map<?, ?> m) return (String) m.get("code");
        } catch (Exception ignored) {}
        return "AI_FAILED";
    }

    private String extractErrorMessage(String body) {
        try {
            Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
            if (parsed.get("detail") instanceof Map<?, ?> m && m.get("message") instanceof String text && !text.isBlank()) {
                return text.length() > 100 ? text.substring(0, 100) : text;
            }
        } catch (Exception ignored) {}
        return "분석을 완료하지 못했어요. 잠시 후 다시 시도해 주세요.";
    }

    public static class AiAnalysisException extends Exception {
        private final String code;
        public AiAnalysisException(String code, String message) {
            super(message);
            this.code = code;
        }
        public String getCode() { return code; }
    }
}
