package com.timxs.bbs.router;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.timxs.bbs.finder.BbsFinder;
import com.timxs.bbs.vo.BbsPostVo;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;

/**
 * BBS 社区前台路由：列表页 {@code /bbs}（?category={slug}&q=&page=）与
 * 详情页 {@code /bbs/post/{slug}}。
 *
 * <p>模板视图名经 {@link TemplateNameResolver} 解析——激活主题若提供同名模板
 * （{@code bbs.html} / {@code bbs_post.html}）则优先使用，否则回退到插件
 * 自带的现代化默认模板（开箱即用）。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsRouter {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_ANNOUNCEMENT_COUNT = 3;
    private static final String DEFAULT_ACCENT_COLOR = "#4d698e";

    private final BbsFinder bbsFinder;
    private final TemplateNameResolver templateNameResolver;
    private final ReactiveSettingFetcher settingFetcher;

    public BbsRouter(BbsFinder bbsFinder, TemplateNameResolver templateNameResolver,
            ReactiveSettingFetcher settingFetcher) {
        this.bbsFinder = bbsFinder;
        this.templateNameResolver = templateNameResolver;
        this.settingFetcher = settingFetcher;
    }

    @Bean
    RouterFunction<ServerResponse> bbsRouterFunction() {
        return route(GET("/bbs"), listHandler())
                .andRoute(GET("/bbs/rss.xml"), rssHandler())
                .andRoute(GET("/bbs/u/{username:\\S+}"), authorHandler())
                .andRoute(GET("/bbs/post/{slug:\\S+}"), detailHandler());
    }

    /** 列表页：公告区 + 分类导航 + 帖子列表（分页 / 分类过滤 / 关键词搜索）。 */
    private HandlerFunction<ServerResponse> listHandler() {
        return request -> loadConfig().flatMap(cfg -> {
            int page = resolvePage(request);
            String categorySlug = request.queryParam("category")
                    .filter(StringUtils::isNotBlank).orElse("");
            String keyword = request.queryParam("q")
                    .filter(StringUtils::isNotBlank).orElse(null);

            var posts = bbsFinder.listPosts(page, cfg.pageSize());
            if (StringUtils.isNotBlank(categorySlug) || keyword != null) {
                posts = bbsFinderListFiltered(categorySlug, keyword, page, cfg.pageSize());
            }

            Map<String, Object> model = new HashMap<>();
            model.put("posts", posts);
            model.put("announcements",
                    bbsFinder.listAnnouncements(cfg.announcementCount()).collectList());
            model.put("categories", bbsFinder.listCategories().collectList());
            model.put("currentCategory", categorySlug);
            model.put("keyword", keyword == null ? "" : keyword);
            model.put("currentPage", page);
            model.put("accentColor", cfg.accentColor());
            model.put("logoUrl", cfg.logoUrl());
            model.put("slogan", cfg.slogan());
            model.put("bannerUrl", cfg.bannerUrl());
            model.put("showStats", cfg.showStats());
            model.put("title", cfg.pageTitle());
            model.put(ModelConst.TEMPLATE_ID, "bbs");
            return templateNameResolver
                    .resolveTemplateNameOrDefault(request.exchange(), "bbs")
                    .flatMap(template -> ServerResponse.ok().render(template, model));
        });
    }

    /** 详情页：仅已发布帖子可访问，未找到 404；附同分类最新帖子推荐。 */
    private HandlerFunction<ServerResponse> detailHandler() {
        return request -> loadConfig().flatMap(cfg -> {
            String slug = request.pathVariable("slug");
            return bbsFinder.getBySlug(slug)
                    .flatMap(post -> {
                        Map<String, Object> model = new HashMap<>();
                        model.put("post", post);
                        model.put("relatedPosts", loadRelated(post));
                        model.put("accentColor", cfg.accentColor());
                        model.put("logoUrl", cfg.logoUrl());
                        model.put("bbsTitle", cfg.pageTitle());
                        model.put("title", post.getTitle());
                        model.put(ModelConst.TEMPLATE_ID, "bbs_post");
                        return templateNameResolver
                                .resolveTemplateNameOrDefault(request.exchange(), "bbs_post")
                                .flatMap(template -> ServerResponse.ok().render(template, model));
                    })
                    .switchIfEmpty(ServerResponse.notFound().build());
        });
    }

    /** 作者页 /bbs/u/{username}：作者信息 + 其已发布内容分页（含公告）。 */
    private HandlerFunction<ServerResponse> authorHandler() {
        return request -> loadConfig().flatMap(cfg -> {
            String username = request.pathVariable("username");
            int page = resolvePage(request);
            return Mono.zip(
                            bbsFinder.getAuthor(username),
                            bbsFinder.listPostsByOwner(username, page, cfg.pageSize()))
                    .flatMap(tuple -> {
                        var author = tuple.getT1();
                        Map<String, Object> model = new HashMap<>();
                        model.put("author", author);
                        model.put("posts", tuple.getT2());
                        model.put("currentPage", page);
                        model.put("accentColor", cfg.accentColor());
                        model.put("logoUrl", cfg.logoUrl());
                        model.put("bbsTitle", cfg.pageTitle());
                        model.put("title", author.getDisplayName());
                        model.put(ModelConst.TEMPLATE_ID, "bbs_author");
                        return templateNameResolver
                                .resolveTemplateNameOrDefault(request.exchange(), "bbs_author")
                                .flatMap(template -> ServerResponse.ok().render(template, model));
                    });
        });
    }

    /** RSS 2.0 输出：最新已发布内容（含公告，纯时间倒序），绝对链接基于当前请求地址。 */
    private HandlerFunction<ServerResponse> rssHandler() {
        return request -> loadConfig().flatMap(cfg ->
                bbsFinder.listLatest(20)
                        .collectList()
                        .flatMap(items -> {
                            var uri = request.uri();
                            var base = uri.getScheme() + "://" + uri.getAuthority();
                            return ServerResponse.ok()
                                    .contentType(MediaType.APPLICATION_RSS_XML)
                                    .bodyValue(buildRss(cfg, items, base));
                        }));
    }

    private static final DateTimeFormatter RSS_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private String buildRss(BbsConfig cfg, List<BbsPostVo> items, String base) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rss version=\"2.0\"><channel>")
                .append("<title>").append(escapeXml(cfg.pageTitle())).append("</title>")
                .append("<link>").append(escapeXml(base)).append("/bbs</link>")
                .append("<description>")
                .append(escapeXml(StringUtils.defaultIfBlank(cfg.slogan(), cfg.pageTitle())))
                .append("</description>");
        for (var item : items) {
            var link = base + item.getPermalink();
            sb.append("<item>")
                    .append("<title>").append(escapeXml(item.getTitle())).append("</title>")
                    .append("<link>").append(escapeXml(link)).append("</link>")
                    .append("<guid>").append(escapeXml(link)).append("</guid>");
            if (StringUtils.isNotBlank(item.getExcerpt())) {
                sb.append("<description>").append(escapeXml(item.getExcerpt()))
                        .append("</description>");
            }
            if (item.getOwner() != null) {
                sb.append("<author>").append(escapeXml(item.getOwner().getDisplayName()))
                        .append("</author>");
            }
            var time = item.getPublishTime() != null
                    ? item.getPublishTime() : item.getCreationTimestamp();
            if (time != null) {
                sb.append("<pubDate>").append(RSS_DATE.format(time)).append("</pubDate>");
            }
            sb.append("</item>");
        }
        sb.append("</channel></rss>");
        return sb.toString();
    }

    private static String escapeXml(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** 相关推荐：同分类最新帖子（无分类的公告取全站最新），排除当前帖，至多 5 条。 */
    private Mono<List<BbsPostVo>> loadRelated(BbsPostVo post) {
        var listMono = post.getCategory() != null
                ? bbsFinder.listPostsByCategory(post.getCategory().getSlug(), 1, 6)
                : bbsFinder.listPosts(1, 6);
        return listMono.map(result -> result.getItems().stream()
                .filter(p -> !p.getName().equals(post.getName()))
                .limit(5)
                .toList());
    }

    private Mono<ListResult<BbsPostVo>> bbsFinderListFiltered(String categorySlug,
            String keyword, int page, int size) {
        // 关键词搜索经公开查询通道（含分类过滤）；两者都空时不会走到这里
        return StringUtils.isNotBlank(keyword)
                ? bbsFinder.listPostsByCategoryAndKeyword(categorySlug, keyword, page, size)
                : bbsFinder.listPostsByCategory(categorySlug, page, size);
    }

    private int resolvePage(ServerRequest request) {
        return request.queryParam("page")
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .map(p -> Math.max(1, p))
                .orElse(1);
    }

    /** 读取插件设置（base=列表行为，appearance=品牌与 Hero）并套用默认值，保证开箱即用。 */
    private Mono<BbsConfig> loadConfig() {
        var base = settingFetcher.fetch("base", BaseSetting.class)
                .defaultIfEmpty(new BaseSetting(null, null));
        var appearance = settingFetcher.fetch("appearance", AppearanceSetting.class)
                .defaultIfEmpty(new AppearanceSetting(null, null, null, null, null, null, null));
        return Mono.zip(base, appearance).map(t -> {
            var b = t.getT1();
            var a = t.getT2();
            // Banner 仅在选择图片模式且 URL 合法时生效
            var bannerUrl = "image".equals(a.heroStyle()) ? sanitizeUrl(a.bannerImage()) : null;
            return new BbsConfig(
                    StringUtils.defaultIfBlank(a.pageTitle(), "BBS 社区"),
                    (b.pageSize() == null || b.pageSize() < 1)
                            ? DEFAULT_PAGE_SIZE : b.pageSize(),
                    (b.announcementCount() == null || b.announcementCount() < 1)
                            ? DEFAULT_ANNOUNCEMENT_COUNT : b.announcementCount(),
                    sanitizeColor(a.accentColor()),
                    sanitizeUrl(a.logo()),
                    StringUtils.defaultIfBlank(a.slogan(), "").strip(),
                    bannerUrl,
                    !Boolean.FALSE.equals(a.showStats()));
        });
    }

    /** 前台以内联样式使用主色，仅放行合法 HEX，防样式注入。 */
    private static String sanitizeColor(String color) {
        return (color != null && color.matches("^#([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$"))
                ? color : DEFAULT_ACCENT_COLOR;
    }

    /** Logo / Banner URL 进入内联样式与 src，仅放行站内路径或 http(s)，且不含引号括号空白。 */
    private static String sanitizeUrl(String url) {
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

    /** 基础设置组（bbs-settings / base）：列表行为。 */
    public record BaseSetting(Integer pageSize, Integer announcementCount) {
    }

    /** 外观设置组（bbs-settings / appearance）：品牌与 Hero。 */
    public record AppearanceSetting(String pageTitle, String logo, String slogan,
            String accentColor, String heroStyle, String bannerImage, Boolean showStats) {
    }

    /** 解析后的有效配置（已套用默认值）。 */
    private record BbsConfig(String pageTitle, int pageSize, int announcementCount,
            String accentColor, String logoUrl, String slogan, String bannerUrl,
            boolean showStats) {
    }
}
