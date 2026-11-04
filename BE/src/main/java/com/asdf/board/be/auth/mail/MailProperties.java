package com.asdf.board.be.auth.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "vitality.mail")
public record MailProperties(
        boolean enabled,
        String from,
        @DefaultValue("MOVID") String serviceName,
        Duration codeTtl,
        Duration tokenTtl,
        Duration resendInterval,
        int hourlyLimit,
        int maxAttempts
) {
}
