package com.asdf.board.be.notification.entity;

import jakarta.persistence.*;
import com.asdf.board.be.global.entity.BaseTimeEntity;
import com.asdf.board.be.user.entity.User;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.Builder;

@Entity
@Table(name = "device_tokens", uniqueConstraints = {@UniqueConstraint(name = "uq_device_platform", columnNames = {"user_id", "platform"})})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeviceToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    @Column(name = "token_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "fcm_token", nullable = false, unique = true, length = 4096)
    private String fcmToken;
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 10)
    private Platform platform;

    @Builder
    private DeviceToken(User user, String fcmToken, Platform platform) {
        this.user = user;
        this.fcmToken = fcmToken;
        this.tokenHash = sha256(fcmToken);
        this.platform = platform;
    }

    public void changeToken(String fcmToken) {
        this.fcmToken = fcmToken;
        this.tokenHash = sha256(fcmToken);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public enum Platform { android, ios }
}
