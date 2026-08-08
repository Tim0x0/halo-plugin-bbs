package com.timxs.bbs.router;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timxs.bbs.finder.BbsFinder;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.OwnerVo;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import run.halo.app.core.extension.RoleBinding;
import run.halo.app.core.extension.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.infra.SystemInfoGetter;
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
@Slf4j
@Component
public class BbsRouter {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_RELATED = 5;
    /** 相关推荐默认策略：同分类最新。 */
    private static final String DEFAULT_RELATED_STRATEGY = "category-latest";
    /** 相关推荐策略白名单（与 settings.yaml options 对齐）。 */
    private static final Set<String> RELATED_STRATEGIES =
            Set.of("category-latest", "latest", "most-reply", "same-author", "relevance");
    private static final String DEFAULT_ACCENT_COLOR = "#4d698e";
    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd";
    private static final Set<String> DATE_FORMATS =
            Set.of("yyyy-MM-dd", "yyyy-MM-dd HH:mm", "MM-dd");
    /** BBS 作者链接默认目标：Halo 主题作者页。 */
    private static final String DEFAULT_AUTHOR_LINK_TEMPLATE = "/authors/{name}";
    /** interaction-plus 插件 ConfigMap（与其 plugin.yaml configMapName 一致）。 */
    private static final String HIP_CONFIG_MAP = "interaction-plus-configmap";
    /** hip 展示配置组名（含 userCardLinkTemplate）。 */
    private static final String HIP_DISPLAY_GROUP = "decoration.display";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BbsFinder bbsFinder;
    private final TemplateNameResolver templateNameResolver;
    private final ReactiveSettingFetcher settingFetcher;
    private final SystemInfoGetter systemInfoGetter;
    private final ReactiveExtensionClient client;

    public BbsRouter(BbsFinder bbsFinder, TemplateNameResolver templateNameResolver,
            ReactiveSettingFetcher settingFetcher, SystemInfoGetter systemInfoGetter,
            ReactiveExtensionClient client) {
        this.bbsFinder = bbsFinder;
        this.templateNameResolver = templateNameResolver;
        this.settingFetcher = settingFetcher;
        this.systemInfoGetter = systemInfoGetter;
        this.client = client;
    }

    @Bean
    RouterFunction<ServerResponse> bbsRouterFunction() {
        return route(GET("/bbs"), listHandler())
                .andRoute(GET("/bbs/post/{slug:\\S+}"), detailHandler());
    }

    /** 前台列表排序白名单：最后活跃（默认）/ 最新发布 / 热门。 */
    private static final Set<String> LIST_SORTS = Set.of("active", "latest", "hot");
    /** 前台类型筛选白名单（小写）。 */
    private static final Set<String> LIST_TYPES = Set.of("post", "question", "announcement");

    /** 列表页：分类导航 + 帖子列表（公告混排、置顶按作用域浮顶；分页 / 分类 / 关键词 / 排序 / 类型）。 */
    private HandlerFunction<ServerResponse> listHandler() {
        return request -> Mono.zip(loadConfig(), currentUser(), siteTitle()).flatMap(tuple -> {
            var cfg = tuple.getT1();
            var me = tuple.getT2().orElse(null);
            int page = resolvePage(request);
            String categorySlug = request.queryParam("category")
                    .filter(StringUtils::isNotBlank).orElse("");
            String keyword = request.queryParam("q")
                    .filter(StringUtils::isNotBlank).orElse(null);
            // 排序白名单：active（最后活跃，默认）/ latest（最新发布）/ hot（评论数）
            String sort = request.queryParam("sort")
                    .filter(LIST_SORTS::contains).orElse("active");
            // 类型筛选白名单：post / question / announcement，空=全部
            String type = request.queryParam("type")
                    .map(String::toLowerCase)
                    .filter(LIST_TYPES::contains).orElse("");

            var posts = bbsFinder.list(page, cfg.pageSize(), categorySlug, keyword, sort,
                    type.isEmpty() ? null : type);

            Map<String, Object> model = new HashMap<>();
            model.put("posts", posts);
            // 分类树（一级含 children）——首页分区卡片与移动端分类菜单
            model.put("categories", bbsFinder.listCategoryTree().collectList());
            model.put("currentCategory", categorySlug);
            // 当前分类完整 VO（hero 主题色 / 封面、面包屑、子分类 chips）；无过滤时为 null
            model.put("currentCategoryVo", StringUtils.isBlank(categorySlug)
                    ? Mono.empty()
                    : bbsFinder.getCategoryBySlug(categorySlug));
            // 移动端「当前分类 ▾」按钮文案：无过滤或 slug 无效时回退「全部帖子」
            model.put("currentCategoryName", StringUtils.isBlank(categorySlug)
                    ? Mono.just("全部帖子")
                    : bbsFinder.getCategoryBySlug(categorySlug)
                            .map(c -> c.getDisplayName())
                            .defaultIfEmpty("全部帖子"));
            model.put("currentSort", sort);
            model.put("currentType", type);
            model.put("keyword", keyword == null ? "" : keyword);
            model.put("currentPage", page);
            model.put("title", cfg.pageTitle());
            putCommonModel(model, request, cfg, me, tuple.getT3());
            // 列表页 SEO：直接用社区副标题
            model.put("metaDescription", cfg.slogan());
            model.put(ModelConst.TEMPLATE_ID, "bbs");
            // 管理权限 SSR 判定：显式 resolve 成同步 Boolean，避免依赖 view 层 unwrap Mono
            return hasAdminPermission(me != null ? me.getName() : null)
                    .flatMap(hasAdmin -> {
                        model.put("hasAdminPermission", hasAdmin);
                        return templateNameResolver
                                .resolveTemplateNameOrDefault(request.exchange(), "bbs")
                                .flatMap(template -> ServerResponse.ok().render(template, model));
                    });
        });
    }

    /** 详情页：仅已发布帖子可访问，未找到 404；附同分类最新帖子推荐。 */
    private HandlerFunction<ServerResponse> detailHandler() {
        return request -> Mono.zip(loadConfig(), currentUser(), siteTitle()).flatMap(tuple -> {
            var cfg = tuple.getT1();
            var me = tuple.getT2().orElse(null);
            String slug = request.pathVariable("slug");
            return bbsFinder.getBySlug(slug)
                    .flatMap(post -> {
                        Map<String, Object> model = new HashMap<>();
                        model.put("post", post);
                        model.put("relatedPosts", loadRelated(post, cfg.relatedPostCount(),
                                cfg.relatedPostStrategy()));
                        model.put("relatedStrategy", cfg.relatedPostStrategy());
                        model.put("bbsTitle", cfg.pageTitle());
                        model.put("title", post.getTitle());
                        putCommonModel(model, request, cfg, me, tuple.getT3());
                        model.put(ModelConst.TEMPLATE_ID, "bbs_post");
                        return hasAdminPermission(me != null ? me.getName() : null)
                                .flatMap(hasAdmin -> {
                                    model.put("hasAdminPermission", hasAdmin);
                                    return templateNameResolver
                                            .resolveTemplateNameOrDefault(request.exchange(), "bbs_post")
                                            .flatMap(template -> ServerResponse.ok().render(template, model));
                                });
                    })
                    .switchIfEmpty(ServerResponse.notFound().build());
        });
    }

    /**
     * 相关推荐：按策略取候选并排除当前帖；条数 0 则空列表。
     * <ul>
     *   <li>category-latest：同分类最新（无分类取全站最新，默认）</li>
     *   <li>latest：全站最新</li>
     *   <li>most-reply：全站最多回复</li>
     *   <li>same-author：同作者最新（无作者取全站最新）</li>
     *   <li>relevance：同分类最近候选 + 标题 bigram-Jaccard + 同分类命中 + 时间衰减</li>
     * </ul>
     */
    private Mono<List<BbsPostVo>> loadRelated(BbsPostVo post, int count, String strategy) {
        if (count <= 0) {
            return Mono.just(List.of());
        }
        String s = strategy != null && RELATED_STRATEGIES.contains(strategy)
                ? strategy : DEFAULT_RELATED_STRATEGY;
        int fetch = Math.min(count + 1, 50);
        return switch (s) {
            case "latest" -> pick(bbsFinder.listPosts(1, fetch), post, count, false);
            case "most-reply" -> pick(bbsFinder.list(1, Math.min(count + 5, 50),
                    null, null, "hot", null), post, count, false);
            case "same-author" -> post.getOwner() != null
                    ? pick(bbsFinder.listPostsByOwner(post.getOwner().getName(), 1, fetch),
                            post, count, false)
                    : pick(bbsFinder.listPosts(1, fetch), post, count, false);
            case "relevance" -> loadRelevance(post, count);
            default -> pick(post.getCategory() != null
                    ? bbsFinder.listPostsByCategory(post.getCategory().getSlug(), 1, fetch)
                    : bbsFinder.listPosts(1, fetch), post, count, false);
        };
    }

    /** 取候选、排除当前帖；relevance=true 走相关度评分降序，否则保持原序后截断。 */
    private Mono<List<BbsPostVo>> pick(Mono<ListResult<BbsPostVo>> listMono,
            BbsPostVo post, int count, boolean relevance) {
        return listMono.map(result -> {
            var items = result.getItems().stream()
                    .filter(p -> !p.getName().equals(post.getName()))
                    .toList();
            if (items.isEmpty()) {
                return List.<BbsPostVo>of();
            }
            if (relevance) {
                return items.stream()
                        .sorted((a, b) -> Double.compare(
                                relevanceScore(b, post), relevanceScore(a, post)))
                        .limit(count)
                        .toList();
            }
            return items.stream().limit(count).toList();
        });
    }

    /** 相关度策略：候选=同分类最近 N（无分类取全站），按相关度评分降序。 */
    private Mono<List<BbsPostVo>> loadRelevance(BbsPostVo post, int count) {
        int pool = Math.min(Math.max(count, 5) * 6, 60);
        var listMono = post.getCategory() != null
                ? bbsFinder.listPostsByCategory(post.getCategory().getSlug(), 1, pool)
                : bbsFinder.listPosts(1, pool);
        return pick(listMono, post, count, true);
    }

    /**
     * Flarum 式轻量相关度：标题 bigram-Jaccard 相似度为主，叠加同分类命中与时间衰减。
     * 无搜索后端、无 TF-IDF；纯内存算，仅用于详情页底部少量推荐排序。
     */
    private static double relevanceScore(BbsPostVo candidate, BbsPostVo post) {
        double titleSim = jaccard(tokenize(candidate.getTitle()), tokenize(post.getTitle()));
        boolean sameCat = post.getCategory() != null && candidate.getCategory() != null
                && post.getCategory().getSlug().equals(candidate.getCategory().getSlug());
        double recency = 1.0;
        if (candidate.getLastActivityTime() != null) {
            long ageDays = java.time.Duration.between(
                    candidate.getLastActivityTime(), java.time.Instant.now()).toDays();
            recency = 1.0 / (1.0 + ageDays / 30.0);
        }
        return 0.6 * titleSim + (sameCat ? 0.25 : 0.0) + 0.15 * recency;
    }

    /** 标题分词：连续字母数字段按小写整体，纯中文段（≥3 字）按 2-gram；无外部分词依赖。 */
    private static java.util.Set<String> tokenize(String title) {
        if (title == null || title.isBlank()) {
            return java.util.Set.of();
        }
        var tokens = new java.util.LinkedHashSet<String>();
        var seg = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                seg.append(Character.toLowerCase(c));
            } else {
                flushSegment(seg, tokens);
            }
        }
        flushSegment(seg, tokens);
        return tokens;
    }

    private static void flushSegment(StringBuilder seg, java.util.Set<String> tokens) {
        if (seg.length() == 0) {
            return;
        }
        String s = seg.toString();
        // 含 ASCII 字母数字的段：整体作一个 token；纯中文段（≥3 字）：2-gram
        if (s.length() <= 2 || s.codePoints().anyMatch(c -> c <= 0x2E7F)) {
            tokens.add(s);
        } else {
            for (int i = 0; i + 1 < s.length(); i++) {
                tokens.add(s.substring(i, i + 2));
            }
        }
        seg.setLength(0);
    }

    /** Jaccard 相似度：|A∩B| / |A∪B|；任一为空返回 0。 */
    private static double jaccard(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String t : a) {
            if (b.contains(t)) {
                inter++;
            }
        }
        return (double) inter / (a.size() + b.size() - inter);
    }

    private int resolvePage(ServerRequest request) {
        return request.queryParam("page")
                .filter(s -> s.matches("\\d+"))
                .map(Integer::parseInt)
                .map(p -> Math.max(1, p))
                .orElse(1);
    }

    /**
     * 各页共用 model：登录态、回跳、互动增强、作者链接、页脚、外观/浏览开关与徽标。
     */
    private void putCommonModel(Map<String, Object> model, ServerRequest request,
            BbsConfig cfg, OwnerVo me, String siteTitle) {
        model.put("me", me);
        model.put("requestPath", requestPath(request));
        model.put("hipEnabled", cfg.interactionPlus());
        model.put("authorLinkTemplate", cfg.authorLinkTemplate());
        model.put("siteTitle", StringUtils.defaultIfBlank(siteTitle, cfg.pageTitle()));
        model.put("footerNotice", cfg.footerNotice());
        model.put("year", Year.now().getValue());

        model.put("accentColor", cfg.accentColor());
        model.put("logoUrl", cfg.logoUrl());
        model.put("slogan", cfg.slogan());
        model.put("showHero", cfg.showHero());
        model.put("bannerUrl", cfg.bannerUrl());
        model.put("showStats", cfg.showStats());
        model.put("listShowExcerpt", cfg.listShowExcerpt());
        model.put("dateFormat", cfg.dateFormat());
        model.put("enableToc", cfg.enableToc());
    }

    /** 站点名称（Halo 系统设置的 title）——页脚版权主体。 */
    private Mono<String> siteTitle() {
        return systemInfoGetter.get()
                .map(info -> StringUtils.defaultString(info.getTitle()))
                .defaultIfEmpty("");
    }

    /** 当前登录用户（未登录 / 匿名时为空 Optional）——前台页匿名可浏览，不抛 401。 */
    private Mono<Optional<OwnerVo>> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> auth.getName())
                .filter(name -> !AnonymousUserConst.isAnonymousUser(name))
                .flatMap(bbsFinder::getAuthor)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /**
     * 当前用户是否具备本插件管理权限（版主 / 管理，spec §0 D2 SSR 判定）。
     * 查 RoleBinding：subject 为该用户且 roleRef 为 bbs-moderate / bbs-manage
     * （后者 dependencies 继承前者，二者均属管理级）。失败静默回退 false，
     * 避免前台菜单因查询异常而炸。
     */
    private Mono<Boolean> hasAdminPermission(String username) {
        if (StringUtils.isBlank(username)) {
            return Mono.just(false);
        }
        // 走索引计数（countBy 不反序列化记录，最轻）：
        // subjects 多索引值 = apiGroup/kind/name，User 即 "rbac.../User/{username}"；
        // roleRef.name 单索引 in {bbs-moderate, bbs-manage}（后者 dependencies 继承前者）
        var options = ListOptions.builder()
                .fieldQuery(and(
                        in("roleRef.name", List.of("bbs-moderate", "bbs-manage")),
                        equal("subjects", User.GROUP + "/" + User.KIND + "/" + username)
                ))
                .build();
        return client.countBy(RoleBinding.class, options)
                .map(count -> count > 0)
                .defaultIfEmpty(false)
                .onErrorResume(e -> {
                    log.debug("查询 BBS 管理权限失败，隐藏后台管理入口: {}", e.toString());
                    return Mono.just(false);
                });
    }

    /** 当前请求路径（含查询串），供登录后回跳（/login?redirect_uri=…）。 */
    private static String requestPath(ServerRequest request) {
        var uri = request.uri();
        return uri.getRawQuery() == null
                ? uri.getRawPath()
                : uri.getRawPath() + "?" + uri.getRawQuery();
    }

    /**
     * 读取插件设置并解析作者链接模板。
     * <ul>
     *   <li>接入互动增强开启时：优先读 hip 的 userCardLinkTemplate；插件未就绪或模板空则静默回退 BBS 模板</li>
     *   <li>未接入：只用 BBS 作者链接模板</li>
     *   <li>BBS 模板也空：作者名不跳转</li>
     * </ul>
     */
    private Mono<BbsConfig> loadConfig() {
        var appearance = settingFetcher.fetch("appearance", AppearanceSetting.class)
                .defaultIfEmpty(AppearanceSetting.empty());
        var browsing = settingFetcher.fetch("browsing", BrowsingSetting.class)
                .defaultIfEmpty(BrowsingSetting.empty());
        var integration = settingFetcher.fetch("integration", IntegrationSetting.class)
                .defaultIfEmpty(new IntegrationSetting(null, null));
        return Mono.zip(appearance, browsing, integration).flatMap(t -> {
            var a = t.getT1();
            var b = t.getT2();
            var integ = t.getT3();
            var brand = a.brandOrEmpty();
            var hero = a.heroOrEmpty();
            var seo = a.seoOrEmpty();
            var list = b.listOrEmpty();
            var detail = b.detailOrEmpty();

            boolean hip = Boolean.TRUE.equals(integ.enableInteractionPlus());
            // Banner 仅在显示 Hero、选择图片模式且 URL 合法时生效
            var bannerUrl = Boolean.FALSE.equals(hero.showHero()) ? null
                    : ("image".equals(hero.heroStyle()) ? sanitizeUrl(hero.bannerImage()) : null);
            // 页脚声明：未配置时用默认文案；显式清空表示不显示
            var footerNotice = seo.footerNotice() == null
                    ? "本社区内容由用户发布，不代表站点立场" : seo.footerNotice().strip();
            // BBS 兜底作者链接：null=未配置用默认；显式空串=不跳转
            String bbsAuthorTpl = integ.authorLinkTemplate() == null
                    ? DEFAULT_AUTHOR_LINK_TEMPLATE
                    : sanitizeLinkTemplate(integ.authorLinkTemplate());
            Mono<String> authorTplMono = hip
                    ? resolveAuthorLinkTemplate(bbsAuthorTpl)
                    : Mono.just(bbsAuthorTpl == null ? "" : bbsAuthorTpl);

            int pageSize = (list.pageSize() == null || list.pageSize() < 1)
                    ? DEFAULT_PAGE_SIZE : Math.min(50, list.pageSize());
            int related = detail.relatedPostCount() == null
                    ? DEFAULT_RELATED
                    : Math.min(20, Math.max(0, detail.relatedPostCount()));
            String relatedStrategy = StringUtils.defaultIfBlank(
                    detail.relatedPostStrategy(), DEFAULT_RELATED_STRATEGY);
            // dateFormat 可能为 null（未保存过 / 旧扁平配置未映射）；
            // Immutable Set.contains(null) 会 NPE，必须先判空
            String rawDateFormat = list.dateFormat();
            String dateFormat = rawDateFormat != null && DATE_FORMATS.contains(rawDateFormat)
                    ? rawDateFormat : DEFAULT_DATE_FORMAT;

            return authorTplMono.map(authorTpl -> new BbsConfig(
                    StringUtils.defaultIfBlank(brand.pageTitle(), "BBS 社区"),
                    pageSize,
                    sanitizeColor(brand.accentColor()),
                    sanitizeUrl(brand.logo()),
                    StringUtils.defaultIfBlank(brand.slogan(), "").strip(),
                    !Boolean.FALSE.equals(hero.showHero()),
                    bannerUrl,
                    !Boolean.FALSE.equals(hero.showStats()),
                    footerNotice,
                    Boolean.TRUE.equals(list.listShowExcerpt()),
                    dateFormat,
                    related,
                    relatedStrategy,
                    !Boolean.FALSE.equals(detail.enableToc()),
                    hip,
                    authorTpl));
        });
    }

    /**
     * 接入互动增强时解析有效作者链接模板：hip 非空优先，否则回退 BBS；读失败静默回退，不炸前台。
     */
    private Mono<String> resolveAuthorLinkTemplate(String bbsFallback) {
        String fallback = bbsFallback == null ? "" : bbsFallback;
        return client.fetch(ConfigMap.class, HIP_CONFIG_MAP)
                .map(cm -> {
                    var data = cm.getData();
                    if (data == null) {
                        return "";
                    }
                    var raw = data.get(HIP_DISPLAY_GROUP);
                    if (StringUtils.isBlank(raw)) {
                        return "";
                    }
                    try {
                        var node = JSON.readTree(raw);
                        return sanitizeLinkTemplate(
                                node.path("userCardLinkTemplate").asText(""));
                    } catch (Exception e) {
                        log.debug("解析 interaction-plus 展示配置失败，作者链接回退 BBS 模板", e);
                        return "";
                    }
                })
                .defaultIfEmpty("")
                .onErrorResume(e -> {
                    log.debug("读取 interaction-plus 配置失败，作者链接回退 BBS 模板: {}",
                            e.toString());
                    return Mono.just("");
                })
                .map(hipTpl -> StringUtils.isNotBlank(hipTpl) ? hipTpl : fallback);
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

    /**
     * 作者链接模板校验：允许 {@code {name}} 占位；规则同 URL（站内路径或 http(s)，无引号括号空白）。
     * 空白或非法返回空串（表示不跳转），与「显式清空」一致。
     */
    private static String sanitizeLinkTemplate(String template) {
        if (StringUtils.isBlank(template)) {
            return "";
        }
        var t = template.strip();
        if (!t.matches("[^'\"()\\s]+")) {
            return "";
        }
        if (!(t.startsWith("/") || t.startsWith("http://") || t.startsWith("https://"))) {
            return "";
        }
        return t;
    }

    /**
     * 外观组（bbs-settings / appearance）。
     * 结构与 formSchema 的 {@code $formkit: group} 对齐：brand / hero / seo。
     *
     * <p>同时保留旧版扁平字段（无 group 嵌套时 ConfigMap 直接挂顶层），
     * {@code *OrEmpty()} 优先读嵌套 group，缺失时回退扁平字段，避免升级后前台 500。</p>
     */
    public record AppearanceSetting(
            Brand brand, Hero hero, Seo seo,
            // —— 旧版扁平字段（group 重构前）——
            String pageTitle, String logo, String slogan, String accentColor,
            Boolean showHero, String heroStyle, String bannerImage, Boolean showStats,
            String footerNotice) {

        static AppearanceSetting empty() {
            return new AppearanceSetting(
                    null, null, null,
                    null, null, null, null,
                    null, null, null, null,
                    null);
        }

        public Brand brandOrEmpty() {
            return brand != null ? brand
                    : new Brand(pageTitle, logo, slogan, accentColor);
        }

        Hero heroOrEmpty() {
            return hero != null ? hero
                    : new Hero(showHero, heroStyle, bannerImage, showStats);
        }

        Seo seoOrEmpty() {
            return seo != null ? seo : new Seo(footerNotice);
        }

        public record Brand(String pageTitle, String logo, String slogan, String accentColor) {
        }

        public record Hero(Boolean showHero, String heroStyle, String bannerImage,
                Boolean showStats) {
        }

        /** 页脚配置（group 名仍为 seo，兼容已有 ConfigMap）。 */
        public record Seo(String footerNotice) {
        }
    }

    /**
     * 浏览组（bbs-settings / browsing）。
     * 结构与 formSchema 的 {@code $formkit: group} 对齐：list / detail / rss。
     * 同时保留旧版扁平字段，{@code *OrEmpty()} 在 group 缺失时回退。
     */
    public record BrowsingSetting(
            ListOpts list, Detail detail, Rss rss,
            // —— 旧版扁平字段 ——
            Integer pageSize, Boolean listShowExcerpt, String dateFormat,
            Integer relatedPostCount, String relatedPostStrategy, Boolean enableToc,
            Boolean enableRss, Integer rssSize) {

        static BrowsingSetting empty() {
            return new BrowsingSetting(
                    null, null, null,
                    null, null, null,
                    null, null, null,
                    null, null);
        }

        ListOpts listOrEmpty() {
            return list != null ? list
                    : new ListOpts(pageSize, listShowExcerpt, dateFormat);
        }

        Detail detailOrEmpty() {
            return detail != null ? detail
                    : new Detail(relatedPostCount, relatedPostStrategy, enableToc);
        }

        public Rss rssOrEmpty() {
            return rss != null ? rss : new Rss(enableRss, rssSize);
        }

        public record ListOpts(Integer pageSize, Boolean listShowExcerpt, String dateFormat) {
        }

        public record Detail(Integer relatedPostCount, String relatedPostStrategy,
                Boolean enableToc) {
        }

        public record Rss(Boolean enableRss, Integer rssSize) {
        }
    }

    /**
     * 集成设置组（bbs-settings / integration）：
     * enableInteractionPlus=接入互动增强（装扮 + 作者链接优先 hip）；
     * authorLinkTemplate=BBS 兜底作者链接（{name}=用户名，空=不跳转）。
     */
    public record IntegrationSetting(Boolean enableInteractionPlus, String authorLinkTemplate) {
    }

    /** 解析后的有效配置（已套用默认值；authorLinkTemplate 已是最终有效模板，可为空串）。 */
    private record BbsConfig(
            String pageTitle, int pageSize,
            String accentColor, String logoUrl, String slogan,
            boolean showHero, String bannerUrl, boolean showStats,
            String footerNotice,
            boolean listShowExcerpt, String dateFormat,
            int relatedPostCount, String relatedPostStrategy, boolean enableToc,
            boolean interactionPlus, String authorLinkTemplate) {
    }
}
