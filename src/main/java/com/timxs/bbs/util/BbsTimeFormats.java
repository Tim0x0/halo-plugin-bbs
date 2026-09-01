package com.timxs.bbs.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 前台时间呈现。由 {@code BbsRouter} 放入 model（{@code bbsTime}），
 * 模板用 {@code ${bbsTime.display(instant, dateFormat)}}——不要用 {@code @bbsTime}
 * （插件 bean 不在主题 ApplicationContext 里）。
 */
@Component
public class BbsTimeFormats {

    public static final String RELATIVE = "relative";
    public static final String ABS_TITLE_PATTERN = "yyyy-MM-dd HH:mm";

    private final Map<String, DateTimeFormatter> formatters = new ConcurrentHashMap<>();

    /** 主文案：relative → 相对；否则按 DateTimeFormatter pattern。 */
    public String display(Instant instant, String dateFormat) {
        if (instant == null) {
            return "";
        }
        if (!StringUtils.hasText(dateFormat) || RELATIVE.equals(dateFormat)) {
            return relative(instant);
        }
        return format(instant, dateFormat);
    }

    /** tooltip：固定到分钟。 */
    public String absTitle(Instant instant) {
        if (instant == null) {
            return "";
        }
        return format(instant, ABS_TITLE_PATTERN);
    }

    public String relative(Instant instant) {
        if (instant == null) {
            return "";
        }
        Instant now = Instant.now();
        if (instant.isAfter(now)) {
            return format(instant, ABS_TITLE_PATTERN);
        }
        long seconds = Duration.between(instant, now).getSeconds();
        if (seconds < 60) {
            return "刚刚";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + " 天前";
        }
        long months = days / 30;
        if (months < 12) {
            return months + " 个月前";
        }
        return (months / 12) + " 年前";
    }

    public String format(Instant instant, String pattern) {
        if (instant == null || !StringUtils.hasText(pattern)) {
            return "";
        }
        try {
            DateTimeFormatter fmt = formatters.computeIfAbsent(pattern,
                    p -> DateTimeFormatter.ofPattern(p, Locale.CHINA)
                            .withZone(ZoneId.systemDefault()));
            return fmt.format(instant);
        } catch (IllegalArgumentException ex) {
            return format(instant, ABS_TITLE_PATTERN);
        }
    }
}
