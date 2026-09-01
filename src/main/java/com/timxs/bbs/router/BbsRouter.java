package com.timxs.bbs.router;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.timxs.bbs.finder.BbsFinder;
import com.timxs.bbs.service.BbsRoles;
import com.timxs.bbs.service.BbsSettings;
import com.timxs.bbs.util.BbsPageRequests;
import com.timxs.bbs.util.BbsTimeFormats;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import com.timxs.bbs.vo.OwnerVo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.infra.ExternalUrlSupplier;
import run.halo.app.infra.SystemInfoGetter;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;
import tools.jackson.databind.json.JsonMapper;

/**
 * BBS 社区前台路由：列表页 {@code /bbs}（?category={slug}&q=&page=）与
 * 详情页 {@code /bbs/post/{slug}}（列表另支持 {@code sort}/{@code type}）。
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
    private static final String DEFAULT_DATE_FORMAT = "relative";
    private static final Set<String> DATE_FORMATS =
            Set.of("relative", "yyyy-MM-dd", "yyyy-MM-dd HH:mm", "MM-dd");
    /** BBS 作者链接默认目标：Halo 主题作者页。 */
    private static final String DEFAULT_AUTHOR_LINK_TEMPLATE = "/authors/{name}";
    /** interaction-plus 插件 ConfigMap（与其 plugin.yaml configMapName 一致）。 */
    private static final String HIP_CONFIG_MAP = "interaction-plus-configmap";
    /** hip 展示配置组名（含 userCardLinkTemplate）。 */
    private static final String HIP_DISPLAY_GROUP = "decoration.display";

    private static final JsonMapper JSON = new JsonMapper();

    private final BbsFinder bbsFinder;
    private final TemplateNameResolver templateNameResolver;
    private final BbsSettings settings;
    private final SystemInfoGetter systemInfoGetter;
    private final ReactiveExtensionClient client;
    private final BbsTimeFormats bbsTimeFormats;
    private final RoleService roleService;
    private final ExternalUrlSupplier externalUrlSupplier;

    public BbsRouter(BbsFinder bbsFinder, TemplateNameResolver templateNameResolver,
            BbsSettings settings, SystemInfoGetter systemInfoGetter,
            ReactiveExtensionClient client, BbsTimeFormats bbsTimeFormats,
            RoleService roleService, ExternalUrlSupplier externalUrlSupplier) {
        this.bbsFinder = bbsFinder;
        this.templateNameResolver = templateNameResolver;
        this.settings = settings;
        this.systemInfoGetter = systemInfoGetter;
        this.client = client;
        this.bbsTimeFormats = bbsTimeFormats;
        this.roleService = roleService;
        this.externalUrlSupplier = externalUrlSupplier;
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
            int page = BbsPageRequests.page(request, 1);
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

            if (StringUtils.isBlank(categorySlug)) {
                return renderList(request, cfg, me, tuple.getT3(), page, "", keyword, sort, type,
                        Mono.empty(), null);
            }
            // 停用 / 不存在的分类不能回首页（会把全站列表当成该分类页）。
            // 以 404 结束：错误页渲染由 Halo + 激活主题负责（官方 SinglePageRoute
            // 抛 NotFoundException 同样是给异常管道一个 404 状态；插件 jar 不暴露
            // 该异常类，用等价的 ResponseStatusException）。
            return bbsFinder.getCategoryBySlug(categorySlug)
                    .flatMap(cat -> renderList(request, cfg, me, tuple.getT3(), page, categorySlug,
                            keyword, sort, type, Mono.just(cat), cat.getDisplayName()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "分类不存在")));
        });
    }

    /**
     * @param categoryTitle 分类页的分类名（首页为 null）——只用于浏览器标题；
     *                      调用点已在 flatMap 里拿到同步的 CategoryVo，不必从 Mono 里再取
     */
    private Mono<ServerResponse> renderList(ServerRequest request, BbsConfig cfg, OwnerVo me,
            String siteTitle, int page, String categorySlug, String keyword, String sort,
            String type, Mono<CategoryVo> categoryVo, String categoryTitle) {
        var posts = bbsFinder.list(page, cfg.pageSize(), categorySlug, keyword, sort,
                type.isEmpty() ? null : type);
        Map<String, Object> model = new HashMap<>();
        model.put("posts", posts);
        model.put("categories", bbsFinder.listCategoryTree().collectList());
        model.put("currentCategory", categorySlug);
        model.put("currentCategoryVo", categoryVo);
        model.put("currentCategoryName", StringUtils.isBlank(categorySlug)
                ? Mono.just("全部帖子")
                : categoryVo.map(CategoryVo::getDisplayName));
        model.put("currentSort", sort);
        model.put("currentType", type);
        model.put("keyword", keyword == null ? "" : keyword);
        model.put("currentPage", page);
        model.put("title", cfg.pageTitle());
        // 浏览器标题（段之间用配置的分隔符，空段自动跳过）：
        // - 首页第 1 页「社区名 - 副标题」；第 2 页起副标题让位给页码（副标题是入口页的介绍）
        // - 分类页「分类名 [- 第 N 页] - 社区名」：分类页是内页，对齐详情页
        // 分页必须进标题：否则第 2 页与第 1 页标题逐字相同，与分类页撞车是同一类 duplicate title。
        // 搜索 / 类型筛选刻意不进标题（那些页不该被索引，拼进来只会又长又碎）。
        // ${title} 仍是纯社区名，顶栏 logo 文字与 Hero h1 继续用它。
        String pageLabel = page > 1 ? "第 " + page + " 页" : null;
        boolean isCategoryPage = StringUtils.isNotBlank(categoryTitle);
        model.put("documentTitle", isCategoryPage
                ? cfg.documentTitle(categoryTitle, pageLabel, cfg.pageTitle())
                : cfg.documentTitle(cfg.pageTitle(), page > 1 ? pageLabel : cfg.slogan()));
        // og:title 用页面主体（不带社区名后缀），与详情页 og:title=帖子标题 对齐
        model.put("ogTitle", isCategoryPage ? categoryTitle : cfg.pageTitle());
        // canonical：只保留分类与页码，丢弃 sort / type / q——那些是同一批内容的不同视图，
        // 各自被索引会产生大量重复内容
        model.put("canonicalUrl", canonicalListUrl(siteBaseUrl(request), categorySlug, page));
        putCommonModel(model, request, cfg, me, siteTitle);
        model.put("metaDescription", cfg.slogan());
        model.put(ModelConst.TEMPLATE_ID, "bbs");
        return hasAdminPermission(me != null ? me.getName() : null)
                .flatMap(hasAdmin -> {
                    model.put("hasAdminPermission", hasAdmin);
                    return templateNameResolver
                            .resolveTemplateNameOrDefault(request.exchange(), "bbs")
                            .flatMap(template -> ServerResponse.ok().render(template, model));
                });
    }

    /** 详情页：仅已发布帖子可访问，未找到 404；附同分类最新帖子推荐。 */
    private HandlerFunction<ServerResponse> detailHandler() {
        return request -> Mono.zip(loadConfig(), currentUser(), siteTitle()).flatMap(tuple -> {
            var cfg = tuple.getT1();
            var me = tuple.getT2().orElse(null);
            String slug = request.pathVariable("slug");
            // 未找到以 404 结束（官方 SinglePageRoute 抛 NotFoundException 同理），
            // 错误页渲染由 Halo + 激活主题负责
            return bbsFinder.getBySlug(slug)
                    .flatMap(post -> {
                        Map<String, Object> model = new HashMap<>();
                        model.put("post", post);
                        model.put("relatedPosts", loadRelated(post, cfg.relatedPostCount(),
                                cfg.relatedPostStrategy()));
                        model.put("relatedStrategy", cfg.relatedPostStrategy());
                        model.put("bbsTitle", cfg.pageTitle());
                        model.put("title", post.getTitle());
                        // 详情页后缀用社区名而非副标题：读者要认出「这是哪儿」
                        model.put("documentTitle",
                                cfg.documentTitle(post.getTitle(), cfg.pageTitle()));
                        // canonical：帖子的规范地址就是它的 permalink（绝对 URL，og:url 共用）
                        model.put("canonicalUrl", siteBaseUrl(request) + post.getPermalink());
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
                    .switchIfEmpty(Mono.error(new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "帖子不存在")));
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
            case "latest" -> pick(bbsFinder.listLatest(fetch), post, count);
            case "most-reply" -> pick(bbsFinder.listMostReplied(fetch), post, count);
            case "same-author" -> post.getOwner() != null
                    ? pick(bbsFinder.listPostsByOwner(post.getOwner().getName(), 1, fetch),
                            post, count, false)
                    : pick(bbsFinder.listPosts(1, fetch), post, count, false);
            case "relevance" -> loadRelevance(post, count);
            default -> pick(post.getCategory() != null
                    ? bbsFinder.listLatestByCategory(post.getCategory().getSlug(), fetch)
                    : bbsFinder.listLatest(fetch), post, count);
        };
    }

    /** 取时间线 / 索引排序结果，排除当前帖后截断。 */
    private Mono<List<BbsPostVo>> pick(Flux<BbsPostVo> source,
            BbsPostVo post, int count) {
        return source
                .filter(candidate -> !candidate.getName().equals(post.getName()))
                .take(count)
                .collectList();
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

    /**
     * 各页共用 model：登录态、回跳、互动增强、作者链接、页脚、外观/浏览开关与徽标。
     */
    private void putCommonModel(Map<String, Object> model, ServerRequest request,
            BbsConfig cfg, OwnerVo me, String siteTitle) {
        model.put("me", me);
        model.put("requestPath", requestPath(request));
        model.put("hipEnabled", cfg.interactionPlus());
        model.put("listDecoration", cfg.listDecoration());
        model.put("authorLinkTemplate", cfg.authorLinkTemplate());
        model.put("siteTitle", StringUtils.defaultIfBlank(siteTitle, cfg.pageTitle()));
        model.put("footerNotice", cfg.footerNotice());
        model.put("year", Year.now().getValue());

        model.put("accentColor", cfg.accentColor());
        model.put("logoUrl", cfg.logoUrl());
        model.put("slogan", cfg.slogan());
        model.put("showHero", cfg.showHero());
        model.put("bannerUrl", cfg.bannerUrl());
        model.put("listShowExcerpt", cfg.listShowExcerpt());
        model.put("dateFormat", cfg.dateFormat());
        // 经 model 注入，模板用 ${bbsTime.display(...)}，勿用 @bbsTime（主题上下文无插件 bean）
        model.put("bbsTime", bbsTimeFormats);
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
     * 当前用户是否具备本插件管理权限（版主 / 管理）。SSR 阶段判定，
     * 避免无权限用户在 HTML 源码里看到「后台管理」入口。
     * 失败静默回退 false，避免前台菜单因查询异常而炸。
     */
    private Mono<Boolean> hasAdminPermission(String username) {
        if (StringUtils.isBlank(username)) {
            return Mono.just(false);
        }
        // 故意展开组角色 / 聚合角色 / super-role：只决定入口显隐，判宽无害。
        // 管辖判定见 BbsModerationScope，那边绝不能展开。
        return roleService.getRolesByUsername(username)
                .collectList()
                .flatMap(roles -> {
                    if (roles.stream().anyMatch(r ->
                            BbsRoles.SUPER.equals(r)
                                    || BbsRoles.MODERATE.equals(r)
                                    || BbsRoles.MANAGE.equals(r))) {
                        return Mono.just(true);
                    }
                    return roleService.listDependenciesFlux(new HashSet<>(roles))
                            .map(role -> role.getMetadata().getName())
                            .any(name -> BbsRoles.MODERATE.equals(name)
                                    || BbsRoles.MANAGE.equals(name));
                })
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
     * 列表页 canonical：绝对 URL，只保留 {@code category} 与 {@code page}。
     *
     * <p>{@code sort} / {@code type} / {@code q} 一律丢弃——它们是同一批内容的不同视图或
     * 搜索结果，各自被索引就是重复内容。第 1 页不写 {@code page=1}，避免
     * {@code /bbs} 与 {@code /bbs?page=1} 互指两个 URL。</p>
     *
     * <p>用绝对 URL：`og:url` 按 OG 规范必须绝对（相对值会被抓取方解析错），canonical
     * 绝对也是推荐做法。基地址取 {@link ExternalUrlSupplier}——与本插件 RSS 生成绝对链接
     * 同一来源，站点地址未配置时 Halo 以当前请求兜底。</p>
     */
    private static String canonicalListUrl(String baseUrl, String categorySlug, int page) {
        StringBuilder sb = new StringBuilder(baseUrl).append("/bbs");
        boolean hasCategory = StringUtils.isNotBlank(categorySlug);
        if (hasCategory) {
            sb.append("?category=").append(
                    URLEncoder.encode(categorySlug, StandardCharsets.UTF_8));
        }
        if (page > 1) {
            sb.append(hasCategory ? "&" : "?").append("page=").append(page);
        }
        return sb.toString();
    }

    /** 站点对外基地址（去尾斜杠，避免与以 / 开头的路径拼出双斜杠）。 */
    private String siteBaseUrl(ServerRequest request) {
        return Strings.CS.removeEnd(
                externalUrlSupplier.getURL(request.exchange().getRequest()).toString(), "/");
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
        var appearance = settings.appearance();
        var browsing = settings.browsing();
        var integration = settings.integration();
        return Mono.zip(appearance, browsing, integration).flatMap(t -> {
            var a = t.getT1();
            var b = t.getT2();
            var integ = t.getT3();
            var brand = a.brand();
            var hero = a.hero();
            var seo = a.seo();
            var list = b.list();
            var detail = b.detail();
            var interaction = integ.interaction();

            boolean hip = Boolean.TRUE.equals(interaction.enableInteractionPlus());
            // 列表装扮仅在接入互动增强时生效
            boolean listDecoration = hip && Boolean.TRUE.equals(interaction.listDecoration());
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
                    footerNotice,
                    Boolean.TRUE.equals(list.listShowExcerpt()),
                    dateFormat,
                    related,
                    relatedStrategy,
                    !Boolean.FALSE.equals(detail.enableToc()),
                    hip,
                    listDecoration,
                    authorTpl,
                    // 清空 = 回到默认「-」；限长防止把整段文案塞进分隔符
                    StringUtils.left(
                            StringUtils.defaultIfBlank(brand.titleSeparator(), "-").strip(),
                            8)));
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
                                node.path("userCardLinkTemplate").asString(""));
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

    /** 前台以内联样式使用主色：仅放行 HEX（含透明）；空或非法返回空串。 */
    private static String sanitizeColor(String color) {
        return com.timxs.bbs.util.BbsColors.sanitize(color);
    }

    /** Logo / Banner URL 进入内联样式与 src，仅放行站内路径或 http(s)，且不含引号括号空白。 */
    private static String sanitizeUrl(String url) {
        return com.timxs.bbs.util.BbsUrls.sanitize(url);
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

    /** 解析后的有效配置（已套用默认值；authorLinkTemplate 已是最终有效模板，可为空串）。 */
    private record BbsConfig(
            String pageTitle, int pageSize,
            String accentColor, String logoUrl, String slogan,
            boolean showHero, String bannerUrl,
            String footerNotice,
            boolean listShowExcerpt, String dateFormat,
            int relatedPostCount, String relatedPostStrategy, boolean enableToc,
            boolean interactionPlus, boolean listDecoration, String authorLinkTemplate,
            String titleSeparator) {

        /**
         * 浏览器标题：按顺序拼接非空段，空段跳过，只剩一段时不出现分隔符。
         * 分隔符两侧固定补空格——存的是「-」这类裸符号，拼出来才是「BBS 社区 - 副标题」。
         */
        String documentTitle(String... segments) {
            return java.util.Arrays.stream(segments)
                    .filter(StringUtils::isNotBlank)
                    .map(String::strip)
                    .collect(java.util.stream.Collectors.joining(" " + titleSeparator + " "));
        }
    }
}
