package com.timxs.bbs.util;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.service.HtmlSanitizer;

/**
 * 摘要解析：把 {@code spec.excerpt} 折算成一条展示文本。
 *
 * <p>所有对外出口（VO 装配 / 搜索索引 / RSS）都必须经此，否则「自动摘要」在某些出口
 * 会变成空——自动模式下 {@code raw} 是不存值的。调用方需传入已从 release/head
 * Snapshot 还原的正文。</p>
 *
 * @author Tim0x0
 */
public final class BbsExcerpts {

    /** 自动摘要截取长度。 */
    public static final int MAX_LENGTH = 160;

    private BbsExcerpts() {
    }

    /** 展示摘要：自动模式实时截取已还原正文，否则取手工原文。 */
    public static String resolve(BbsPost.Spec spec, String content) {
        if (spec == null) {
            return "";
        }
        return resolve(spec.getExcerpt(), content);
    }

    /** 工作稿 / 发布稿共用的摘要解析入口。 */
    public static String resolve(BbsPost.Excerpt excerpt, String content) {
        if (isAuto(excerpt)) {
            return HtmlSanitizer.plainExcerpt(content, MAX_LENGTH);
        }
        if (excerpt == null || excerpt.getRaw() == null) {
            return "";
        }
        // 手工摘要也走纯文本：PATCH 直写 HTML / 控制字符时 RSS / 搜索 description 不能原样带出
        return HtmlSanitizer.plainExcerpt(excerpt.getRaw(), MAX_LENGTH);
    }

    /** 是否自动摘要（缺省视为自动：新建帖子的默认形态）。 */
    public static boolean isAuto(BbsPost.Excerpt excerpt) {
        return excerpt == null || !Boolean.FALSE.equals(excerpt.getAutoGenerate());
    }
}
