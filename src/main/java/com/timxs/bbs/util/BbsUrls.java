package com.timxs.bbs.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;

/**
 * 站内地址的唯一拼点：前台路由形态（{@code /bbs/post/{slug}}、{@code /bbs?category=}）
 * 与 {@code url(...)}/{@code src} 链接清洗。
 *
 * <p>路由的权威事实源是 {@code BbsRouter.bbsRouterFunction()}；这里的构造器保证
 * VO / 调和器 / 搜索索引 / 评论主题各方拼出的链接与路由一致。</p>
 *
 * @author Tim0x0
 */
public final class BbsUrls {

    private BbsUrls() {
    }

    /**
     * @return 合法 URL；空白或非法返回 {@code null}
     */
    public static String sanitize(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        var u = url.strip();
        if (!u.matches("[^'\"()\\s]+")) {
            return null;
        }
        return (u.startsWith("/") || u.startsWith("http://") || u.startsWith("https://"))
                ? u : null;
    }

    /** 帖子前台地址；调用方保证 slug 非空。 */
    public static String postPermalink(String slug) {
        return "/bbs/post/" + slug;
    }

    /** 分类前台地址；slug 空白返回 {@code null}。 */
    public static String categoryPermalink(String slug) {
        if (StringUtils.isBlank(slug)) {
            return null;
        }
        return "/bbs?category=" + URLEncoder.encode(slug, StandardCharsets.UTF_8);
    }
}
