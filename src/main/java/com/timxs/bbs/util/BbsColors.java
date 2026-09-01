package com.timxs.bbs.util;

import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 进入内联样式的色值清洗：只放行 HEX（含透明通道），空或非法返回空串。
 *
 * <p>合法形态：{@code #RGB} / {@code #RGBA} / {@code #RRGGBB} / {@code #RRGGBBAA}。</p>
 *
 * @author Tim0x0
 */
public final class BbsColors {

    private static final Pattern HEX = Pattern.compile(
            "^#([a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})$");

    private BbsColors() {
    }

    /**
     * @return 合法 HEX；空白或非法返回空串
     */
    public static String sanitize(String color) {
        if (StringUtils.isBlank(color)) {
            return "";
        }
        var c = color.strip();
        return HEX.matcher(c).matches() ? c : "";
    }
}
