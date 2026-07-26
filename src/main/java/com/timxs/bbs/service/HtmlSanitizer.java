package com.timxs.bbs.service;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/**
 * HTML 净化工具，防止用户投稿内容中的 XSS。
 *
 * <p>Safelist 与 Halo 评论系统保持一致：在 relaxed 基础上额外放行 {@code <s>}、
 * {@code code[class]}、{@code a[target]} 并保留相对链接。Jsoup 由
 * {@code run.halo.app:api} 传递提供，插件无需额外声明依赖。</p>
 *
 * <p>采用「主动清洗」（{@code Jsoup.clean}）而非校验拒绝：脏 HTML 会被清理而非报错，
 * 对用户更友好。</p>
 *
 * @author Tim0x0
 */
public final class HtmlSanitizer {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("s")
            .addAttributes("code", "class")
            .addAttributes("a", "target")
            .preserveRelativeLinks(true);

    private HtmlSanitizer() {
    }

    /**
     * 清洗 HTML，移除不安全的标签/属性/脚本。
     *
     * @param html 原始 HTML，可为 null
     * @return 净化后的 HTML；入参为 null 时返回 null
     */
    public static String clean(String html) {
        return html == null ? null : Jsoup.clean(html, SAFELIST);
    }

    /**
     * 提取全文纯文本（搜索索引用，不截断）。
     *
     * @param html 原始 HTML
     * @return 纯文本（可为空串）
     */
    public static String plainText(String html) {
        return (html == null || html.isBlank()) ? "" : Jsoup.parse(html).text().strip();
    }

    /**
     * 提取纯文本并截断，用于自动摘要。
     *
     * @param html 原始 HTML
     * @param maxLength 最大长度
     * @return 纯文本摘要（可为空串）
     */
    public static String plainExcerpt(String html, int maxLength) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var text = Jsoup.parse(html).text().strip();
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
