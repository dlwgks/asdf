package com.asdf.board.be.body.service;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AiAnalysisClient {

    public boolean isConfigured() {
        return false;
    }

    public Map<String, Object> analyze(String frontPhotoKey, String sidePhotoKey) {
        throw new AiAnalysisException("AI_FAILED", "AI service is not configured");
    }

    public static class AiAnalysisException extends RuntimeException {
        private final String code;

        public AiAnalysisException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
