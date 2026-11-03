package com.asdf.board.be.global.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class KstTime {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private KstTime() {}

    public static String iso(LocalDateTime time) {
        if (time == null) return null;
        return time.withNano(0).atZone(KST).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
