package com.asdf.board.be.notification.controller;

import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.notification.entity.DeviceToken;
import com.asdf.board.be.notification.entity.NotificationSetting;
import com.asdf.board.be.notification.repository.DeviceTokenRepository;
import com.asdf.board.be.notification.repository.NotificationSettingRepository;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.*;

@Tag(name = "notification", description = "알림 설정")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Transactional
public class NotificationController {

    private final NotificationSettingRepository settingRepository;
    private final DeviceTokenRepository tokenRepository;
    private final UserRepository userRepository;

    private Long userId(Jwt jwt) { return Long.valueOf(jwt.getSubject()); }
    private User user(Jwt jwt) {
        return userRepository.findById(userId(jwt)).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @GetMapping("/settings")
    public List<Map<String, Object>> getSettings(@AuthenticationPrincipal Jwt jwt) {
        List<NotificationSetting> settings = settingRepository.findByIdUserId(userId(jwt));
        if (settings.isEmpty()) {
            return Arrays.stream(NotificationSetting.Type.values())
                    .map(t -> defaultSetting(t))
                    .toList();
        }
        return settings.stream().map(this::toMap).toList();
    }

    @PutMapping("/settings/{type}")
    public Map<String, Object> updateSetting(
            @PathVariable String type,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt
    ) {
        NotificationSetting.Type notifType;
        try {
            notifType = NotificationSetting.Type.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }

        User u = user(jwt);
        NotificationSetting.Key key = new NotificationSetting.Key();
        key.setUserId(u.getId());
        key.setType(notifType);

        NotificationSetting setting = settingRepository.findById(key).orElse(null);
        if (setting == null) {
            setting = NotificationSetting.builder()
                    .user(u)
                    .type(notifType)
                    .enabled(true)
                    .build();
        }

        if (body.containsKey("enabled")) {
            setting.setEnabled((Boolean) body.get("enabled"));
        }
        if (body.containsKey("notify_time") && body.get("notify_time") != null) {
            setting.setNotifyTime(LocalTime.parse((String) body.get("notify_time")));
        } else if (body.containsKey("notify_time")) {
            setting.setNotifyTime(null);
        }

        setting = settingRepository.save(setting);
        return toMap(setting);
    }

    @PostMapping("/device-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerToken(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt
    ) {
        User u = user(jwt);
        String fcmToken = (String) body.get("fcm_token");
        String platformStr = (String) body.get("platform");
        if (fcmToken == null || fcmToken.isBlank() || fcmToken.length() > 4096 || platformStr == null) throw new ApiException(ErrorCode.VALIDATION_FAILED);

        DeviceToken.Platform platform;
        try {
            platform = DeviceToken.Platform.valueOf(platformStr);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }

        DeviceToken token = tokenRepository.findByUserIdAndPlatform(u.getId(), platform).orElse(null);
        if (token == null) {
            token = DeviceToken.builder().user(u).fcmToken(fcmToken).platform(platform).build();
        } else {
            token.changeToken(fcmToken);
        }
        token = tokenRepository.save(token);
        return Map.of("token_id", token.getId(), "platform", token.getPlatform().name());
    }

    @DeleteMapping("/device-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeToken(
            @RequestParam String platform,
            @AuthenticationPrincipal Jwt jwt
    ) {
        DeviceToken.Platform p;
        try {
            p = DeviceToken.Platform.valueOf(platform);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        tokenRepository.deleteByUserIdAndPlatform(userId(jwt), p);
    }

    private Map<String, Object> toMap(NotificationSetting s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", s.getId().getType().name());
        m.put("enabled", s.getEnabled());
        m.put("notify_time", s.getNotifyTime() != null ? s.getNotifyTime().toString() : null);
        return m;
    }

    private Map<String, Object> defaultSetting(NotificationSetting.Type type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type.name());
        m.put("enabled", true);
        m.put("notify_time", null);
        return m;
    }
}
