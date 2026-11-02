package com.asdf.board.be.auth.service;

import com.asdf.board.be.auth.dto.TokenResponse;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.global.security.TokenService;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserProfileRepository;
import com.asdf.board.be.user.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final TokenService tokenService;

    @Value("${vitality.oauth.google.client-id:}")
    private String googleClientId;

    @Value("${vitality.oauth.kakao.rest-api-key:}")
    private String kakaoRestApiKey;

    @Transactional
    public TokenResponse loginWithGoogle(String idToken) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
        GoogleIdToken.Payload payload;
        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();
            GoogleIdToken token = verifier.verify(idToken);
            if (token == null) throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
            payload = token.getPayload();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        String sub = payload.getSubject();
        String email = payload.getEmail();
        String name = (String) payload.get("name");

        User user = userRepository.findByGoogleSub(sub).orElse(null);
        if (user == null && email != null) {
            user = userRepository.findByEmail(email).orElse(null);
            if (user != null) {
                user.setGoogleSub(sub);
                user.setAuthProvider(User.AuthProvider.google);
                user = userRepository.save(user);
            }
        }
        if (user == null) {
            user = userRepository.save(User.builder()
                    .email(email)
                    .nickname(generateNickname(name))
                    .authProvider(User.AuthProvider.google)
                    .googleSub(sub)
                    .build());
        }
        if (user.getStatus() != User.Status.active) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return issue(user);
    }

    @Transactional
    public TokenResponse loginWithKakao(String accessToken) {
        JsonNode info = fetchKakaoUserInfo(accessToken);
        long kakaoId = info.get("id").asLong();
        JsonNode kakaoAccount = info.path("kakao_account");
        String email = kakaoAccount.path("email").asText(null);
        String nickname = kakaoAccount.path("profile").path("nickname").asText(null);

        User user = userRepository.findByKakaoId(kakaoId).orElse(null);
        if (user == null && email != null) {
            user = userRepository.findByEmail(email).orElse(null);
            if (user != null) {
                user.setKakaoId(kakaoId);
                user.setAuthProvider(User.AuthProvider.kakao);
                user = userRepository.save(user);
            }
        }
        if (user == null) {
            user = userRepository.save(User.builder()
                    .email(email)
                    .nickname(generateNickname(nickname))
                    .authProvider(User.AuthProvider.kakao)
                    .kakaoId(kakaoId)
                    .build());
        }
        if (user.getStatus() != User.Status.active) throw new ApiException(ErrorCode.UNAUTHORIZED);
        return issue(user);
    }

    private TokenResponse issue(User user) {
        TokenService.IssuedToken token = tokenService.issue(user);
        boolean onboardingCompleted = userProfileRepository.existsByUserId(user.getId());
        return TokenResponse.bearer(token.value(), token.expiresInSeconds(), onboardingCompleted);
    }

    private JsonNode fetchKakaoUserInfo(String accessToken) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://kapi.kakao.com/v2/user/me"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
            return new ObjectMapper().readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    private String generateNickname(String base) {
        String prefix = (base != null && !base.isBlank())
                ? base.replaceAll("[^a-zA-Z0-9가-힣]", "").substring(0, Math.min(base.length(), 8))
                : "user";
        if (prefix.isBlank()) prefix = "user";
        String candidate = prefix + new Random().nextInt(10000);
        int tries = 0;
        while (userRepository.existsByNickname(candidate) && tries++ < 10) {
            candidate = prefix + new Random().nextInt(100000);
        }
        return candidate;
    }
}
