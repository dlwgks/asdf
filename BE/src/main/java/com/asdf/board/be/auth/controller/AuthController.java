package com.asdf.board.be.auth.controller;

import com.asdf.board.be.auth.dto.EmailCodeConfirmRequest;
import com.asdf.board.be.auth.dto.EmailCodeRequest;
import com.asdf.board.be.auth.dto.EmailVerificationTokenResponse;
import com.asdf.board.be.auth.dto.LoginRequest;
import com.asdf.board.be.auth.dto.TokenResponse;
import com.asdf.board.be.auth.service.AuthService;
import com.asdf.board.be.auth.service.EmailVerificationService;
import com.asdf.board.be.auth.service.OAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "auth", description = "회원 · 인증")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final OAuthService oAuthService;

    @Operation(summary = "이메일 로그인", description = "onboarding_completed가 false면 앱이 신체정보(설문) 화면으로 이동합니다.")
    @SecurityRequirements
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "이메일 인증번호 보내기", description = "입력한 이메일로 6자리 인증번호를 보냅니다. 5분 동안 유효하고, 같은 이메일은 60초에 한 번·1시간에 5번까지 요청할 수 있습니다. 이미 가입된 이메일이면 EMAIL_DUPLICATED.")
    @SecurityRequirements
    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendEmailCode(@Valid @RequestBody EmailCodeRequest request) {
        emailVerificationService.sendCode(request.email());
    }

    @Operation(summary = "이메일 인증번호 확인", description = "맞으면 verification_token을 돌려줍니다. 이 값을 회원가입 요청의 email_verification_token에 넣습니다. 5회 틀리면 인증번호를 다시 받아야 합니다.")
    @SecurityRequirements
    @PostMapping("/email-verifications/confirm")
    public EmailVerificationTokenResponse confirmEmailCode(@Valid @RequestBody EmailCodeConfirmRequest request) {
        return emailVerificationService.confirm(request.email(), request.code());
    }

    @PostMapping("/logout")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void logout() {}

    @Operation(summary = "토큰 갱신", description = "유효한 JWT로 새 JWT를 발급합니다. 만료 7일 전부터 호출을 권장합니다.")
    @PostMapping("/refresh")
    public TokenResponse refresh(@AuthenticationPrincipal Jwt jwt) {
        return authService.refresh(Long.valueOf(jwt.getSubject()));
    }

    @Operation(summary = "Google 간편 로그인", description = "Google ID Token을 검증하고 앱 JWT를 발급합니다. 최초 로그인 시 자동 회원가입됩니다.")
    @SecurityRequirements
    @PostMapping("/oauth/google")
    public TokenResponse googleLogin(@RequestBody Map<String, String> body) {
        return oAuthService.loginWithGoogle(body.get("id_token"));
    }

    @Operation(summary = "Kakao 간편 로그인", description = "Kakao Access Token을 검증하고 앱 JWT를 발급합니다. 최초 로그인 시 자동 회원가입됩니다.")
    @SecurityRequirements
    @PostMapping("/oauth/kakao")
    public TokenResponse kakaoLogin(@RequestBody Map<String, String> body) {
        return oAuthService.loginWithKakao(body.get("access_token"));
    }
}
