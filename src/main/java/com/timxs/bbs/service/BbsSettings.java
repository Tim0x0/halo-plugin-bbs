package com.timxs.bbs.service;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import tools.jackson.databind.JsonNode;

/**
 * BBS 设置读取：按当前 formSchema 的嵌套 group 结构解析为类型安全的配置 record。
 *
 * <p>业务侧只接触解析后的 record；ConfigMap 缺失或字段缺省时由 {@code empty()}
 * 与各调用方的默认值兜底。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsSettings {

    private static final int DEFAULT_TITLE_MAX = 100;

    private final ReactiveSettingFetcher settingFetcher;

    public BbsSettings(ReactiveSettingFetcher settingFetcher) {
        this.settingFetcher = settingFetcher;
    }

    public Mono<Appearance> appearance() {
        return settingFetcher.getSettingValue("appearance")
                .map(BbsSettings::parseAppearance)
                .defaultIfEmpty(Appearance.empty());
    }

    public Mono<Browsing> browsing() {
        return settingFetcher.getSettingValue("browsing")
                .map(BbsSettings::parseBrowsing)
                .defaultIfEmpty(Browsing.empty());
    }

    public Mono<Integration> integration() {
        return settingFetcher.getSettingValue("integration")
                .map(BbsSettings::parseIntegration)
                .defaultIfEmpty(Integration.empty());
    }

    public Mono<Content> content() {
        return settingFetcher.getSettingValue("content")
                .map(BbsSettings::parseContent)
                .defaultIfEmpty(Content.empty());
    }

    static Appearance parseAppearance(JsonNode root) {
        var brand = object(root, "brand");
        var hero = object(root, "hero");
        var seo = object(root, "seo");
        return new Appearance(
                new Brand(
                        string(value(brand, "pageTitle")),
                        string(value(brand, "logo")),
                        string(value(brand, "slogan")),
                        string(value(brand, "accentColor")),
                        string(value(brand, "titleSeparator"))),
                new Hero(
                        bool(value(hero, "showHero")),
                        string(value(hero, "heroStyle")),
                        string(value(hero, "bannerImage"))),
                new Seo(string(value(seo, "footerNotice"))));
    }

    static Browsing parseBrowsing(JsonNode root) {
        var list = object(root, "list");
        var detail = object(root, "detail");
        var rss = object(root, "rss");
        return new Browsing(
                new ListOpts(
                        integer(value(list, "pageSize")),
                        bool(value(list, "listShowExcerpt")),
                        string(value(list, "dateFormat"))),
                new Detail(
                        integer(value(detail, "relatedPostCount")),
                        string(value(detail, "relatedPostStrategy")),
                        bool(value(detail, "enableToc"))),
                new Rss(integer(value(rss, "rssSize"))));
    }

    static Integration parseIntegration(JsonNode root) {
        var interaction = object(root, "interaction");
        // authorLinkTemplate 与 interaction group 平级，归属 integration 顶层。
        return new Integration(
                new Interaction(
                        bool(value(interaction, "enableInteractionPlus")),
                        bool(value(interaction, "listDecoration"))),
                string(value(root, "authorLinkTemplate")));
    }

    static Content parseContent(JsonNode root) {
        var posting = object(root, "posting");
        var review = object(root, "review");
        return new Content(
                new Posting(integer(value(posting, "titleMaxLength"))),
                new Review(
                        bool(value(review, "requireReview")),
                        bool(value(review, "reviewOnEdit"))));
    }

    private static JsonNode object(JsonNode root, String field) {
        if (root == null || !root.has(field)) {
            return null;
        }
        var node = root.get(field);
        return node != null && node.isObject() ? node : null;
    }

    private static JsonNode value(JsonNode current, String field) {
        return current != null && current.has(field) ? current.get(field) : null;
    }

    private static String string(JsonNode node) {
        return absent(node) ? null : node.asString();
    }

    private static Boolean bool(JsonNode node) {
        return absent(node) ? null : node.asBoolean();
    }

    private static Integer integer(JsonNode node) {
        return absent(node) ? null : node.asInt();
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    public record Appearance(Brand brand, Hero hero, Seo seo) {
        static Appearance empty() {
            return new Appearance(new Brand(null, null, null, null, null),
                    new Hero(null, null, null), new Seo(null));
        }
    }

    public record Brand(String pageTitle, String logo, String slogan, String accentColor,
            String titleSeparator) {
    }

    public record Hero(Boolean showHero, String heroStyle, String bannerImage) {
    }

    public record Seo(String footerNotice) {
    }

    public record Browsing(ListOpts list, Detail detail, Rss rss) {
        static Browsing empty() {
            return new Browsing(new ListOpts(null, null, null),
                    new Detail(null, null, null), new Rss(null));
        }
    }

    public record ListOpts(Integer pageSize, Boolean listShowExcerpt, String dateFormat) {
    }

    public record Detail(Integer relatedPostCount, String relatedPostStrategy,
            Boolean enableToc) {
    }

    public record Rss(Integer rssSize) {
    }

    public record Integration(Interaction interaction, String authorLinkTemplate) {
        static Integration empty() {
            return new Integration(new Interaction(null, null), null);
        }
    }

    public record Interaction(Boolean enableInteractionPlus, Boolean listDecoration) {
    }

    public record Content(Posting posting, Review review) {
        static Content empty() {
            return new Content(new Posting(null), new Review(null, null));
        }

        public int titleMaxOrDefault() {
            var max = posting.titleMaxLength();
            return max == null ? DEFAULT_TITLE_MAX : Math.min(200, Math.max(10, max));
        }

        /** 是否开启发帖审核。 */
        public boolean required() {
            return Boolean.TRUE.equals(review.requireReview());
        }

        /** 编辑已发布内容是否需重审（缺省开启，安全优先）。 */
        public boolean editNeedsReview() {
            return !Boolean.FALSE.equals(review.reviewOnEdit());
        }
    }

    public record Posting(Integer titleMaxLength) {
    }

    public record Review(Boolean requireReview, Boolean reviewOnEdit) {
    }
}
