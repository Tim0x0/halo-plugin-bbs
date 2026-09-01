package com.timxs.bbs.query;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;
import static run.halo.app.extension.index.query.Queries.isNull;
import static run.halo.app.extension.index.query.Queries.not;
import static run.halo.app.extension.index.query.Queries.or;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.service.BbsPostContentService;
import com.timxs.bbs.util.BbsExcerpts;
import com.timxs.bbs.util.BbsUrls;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import com.timxs.bbs.vo.CommentOwnerVo;
import com.timxs.bbs.vo.OwnerVo;
import com.timxs.bbs.vo.RoCommentVo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.extension.index.query.Condition;

/**
 * 查询与 VO 批量装配（Console / UC / 公开 API / Finder 四端共用）。
 *
 * <p>装配原则：对一页条目先收集全部关联资源名（分类 / 作者），每类资源仅发一次
 * 批量查询建 Map，再逐条内联——避免 N+1。</p>
 *
 * <p><b>分类作用域</b>（两级分类，公告与普通帖同规则）：
 * 首页 = 全部已发布帖子；一级分类页 = 本级 + 全部子分类；子分类页 = 本子分类。
 * 帖子须归属分类（含公告）。</p>
 *
 * <p><b>置顶</b>（跟列表流）：第 1 页顶部先挂本视图置顶（不限条数），再接普通流；
 * 第 2 页起无置顶头。置顶候选整类从普通流排除。首页置顶 =
 * 帖 {@code pinned} ∩ 所属分类落在「开了 {@code pinToHome} 的一级分类及其子分类」内
 * （仅一级可开，覆盖整棵子树）；分类页置顶 = 作用域内 {@code pinned}。
 * 浮顶的那批 VO 会被标记 {@code pinnedInView}——置顶徽标以此为准，而非帖子的
 * {@code pinned} 属性，避免未浮顶的帖挂出指向空位置的图钉。</p>
 *
 * @author Tim0x0
 */
@Slf4j
@Component
public class BbsQueryService {

    /**
     * Console 列表排序白名单（键 → Sort），防任意字段排序打到未索引字段。
     *
     * <p>每个键对应的字段都必须在 {@code BbsPlugin} 里注册过索引，否则排序会退化或报错。</p>
     */
    private static final Map<String, Sort> CONSOLE_SORTS = Map.of(
            "creationTimestamp,desc", Sort.by(Sort.Order.desc("metadata.creationTimestamp")),
            "creationTimestamp,asc", Sort.by(Sort.Order.asc("metadata.creationTimestamp")),
            "publishTime,desc", Sort.by(Sort.Order.desc("spec.publishTime")),
            "publishTime,asc", Sort.by(Sort.Order.asc("spec.publishTime")),
            "lastActivityTime,desc", Sort.by(Sort.Order.desc("spec.lastActivityTime")),
            "lastActivityTime,asc", Sort.by(Sort.Order.asc("spec.lastActivityTime")),
            "lastEditTime,desc", Sort.by(Sort.Order.desc("spec.lastEditTime")),
            "lastEditTime,asc", Sort.by(Sort.Order.asc("spec.lastEditTime")),
            "commentsCount,desc", Sort.by(Sort.Order.desc("status.commentsCount")),
            "commentsCount,asc", Sort.by(Sort.Order.asc("status.commentsCount")));

    private static final GroupVersionKind POST_GVK = GroupVersionKind.fromExtension(BbsPost.class);

    /** 前台排序标识：热门（评论数，走 status 索引） / 最后活跃（回帖顶起，默认） / 最新发布。 */
    public static final String SORT_HOT = "hot";
    public static final String SORT_ACTIVE = "active";
    public static final String SORT_LATEST = "latest";

    /** 置顶优先的排序前缀（所有前台排序共用；索引已把 pinned 归一为 true/false）。 */
    private static final List<Sort.Order> PINNED_FIRST = List.of(
            Sort.Order.desc("spec.pinned"),
            Sort.Order.desc("spec.pinPriority"));

    /** 分类展示排序：priority 升序，同权重按创建时间。 */
    private static final Sort CATEGORY_SORT = Sort.by(
            Sort.Order.asc("spec.priority"),
            Sort.Order.asc("metadata.creationTimestamp"));

    /** 楼中楼排序：按发表时间正序（与官方评论组件一致，楼中楼不置顶）。 */
    private static final Sort REPLY_SORT = Sort.by(
            Sort.Order.asc("spec.creationTime"),
            Sort.Order.asc("metadata.name"));

    private final ReactiveExtensionClient client;
    private final BbsPostContentService contentService;

    public BbsQueryService(ReactiveExtensionClient client,
            BbsPostContentService contentService) {
        this.client = client;
        this.contentService = contentService;
    }

    // ---------------- 列表查询 ----------------

    /**
     * Console 管理列表：关键词 / 分类 / 类型 / 状态 / 作者筛选 + 排序白名单。
     *
     * @param deleted true 只看回收站；否则只看未删除的（回收站内容不混进正常列表）
     * @param scopedCategories 版主管辖分类集合；{@code empty} = 不限（全站版主 / 管理角色）。
     *     非空集合按 {@code in} 过滤，空集合表示一个分类都管不着，直接返回空页——
     *     让分区版主只看见自己管得了的帖子，免得满屏都是点一下就 403 的东西
     */
    public Mono<ListResult<BbsPostVo>> listConsole(int page, int size, String keyword,
            String categoryName, String type, String phase, String sort, String owner,
            boolean deleted, Optional<Set<String>> scopedCategories) {
        if (scopedCategories.isPresent() && scopedCategories.get().isEmpty()) {
            // 空管辖 = 静默返回空列表。若这是「解析失败被误判成无权限」而非「真无权限」，
            // 用户只会看到空页面而无任何报错——打 warn 让根因在日志里可见。
            log.warn("Console post list short-circuited to empty by empty moderation scope "
                    + "(page={}, deleted={})", page, deleted);
            return Mono.just(new ListResult<>(page, size, 0, List.of()));
        }
        Condition condition = equal("spec.deleted", deleted);
        if (StringUtils.isNotBlank(keyword)) {
            // 别名同属匹配范围：外部自动化（如 GitHub Actions 按 release-<tag>
            // 认领已有帖）靠它精确定位，标题可被人为改动而别名恒定。
            condition = append(condition, or(
                    contains("spec.title", keyword),
                    contains("spec.draft.title", keyword),
                    contains("spec.slug", keyword)));
        }
        if (scopedCategories.isPresent()) {
            condition = append(condition,
                    in("spec.categoryName", scopedCategories.get()));
        }
        condition = appendSharedFilters(condition, categoryName, type, phase);
        if (StringUtils.isNotBlank(owner)) {
            condition = append(condition, equal("spec.owner", owner));
        }
        var sortKey = java.util.Objects.toString(sort, "");
        var sortOrder = CONSOLE_SORTS.getOrDefault(
                sortKey,
                CONSOLE_SORTS.get("creationTimestamp,desc"));
        return listVos(buildOptions(condition), page, size, sortOrder, true);
    }

    /** UC「我的帖子」列表（关键词 / 状态 / 分类 / 类型筛选）。 */
    public Mono<ListResult<BbsPostVo>> listMine(String owner, int page, int size,
            String keyword, String phase, String categoryName, String type) {
        Condition condition = and(equal("spec.owner", owner), equal("spec.deleted", false));
        if (StringUtils.isNotBlank(keyword)) {
            condition = append(condition, or(
                    contains("spec.title", keyword),
                    contains("spec.draft.title", keyword)));
        }
        condition = appendSharedFilters(condition, categoryName, type, phase);
        return listVos(buildOptions(condition), page, size,
                Sort.by(Sort.Order.desc("metadata.creationTimestamp")), true);
    }

    /** 公开列表（默认排序）。 */
    public Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword) {
        return listPublicPosts(page, size, categoryName, categorySlug, keyword, null, null);
    }

    /**
     * 公开列表主入口：已发布内容（公告与普通帖 / 问答帖混排，置顶浮在最前）。
     *
     * <p><b>分类无效（停用 / 不存在 / 拼错）时返回空列表</b>，不抛异常——本方法是主题
     * {@code ${bbs}} Finder 的底座，主题里写错一个 slug 不该把整页渲染打断。需要
     * 「无效分类报 404」的 HTTP 语义时用 {@link #listPublicPostsOrNotFound}。</p>
     *
     * @param sort {@link #SORT_ACTIVE}（默认，最后活跃）/ {@link #SORT_LATEST} / {@link #SORT_HOT}
     * @param type 类型筛选（POST / QUESTION / ANNOUNCEMENT，空 = 全部）
     */
    public Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword, String sort, String type) {
        return listPublicPosts(page, size, categoryName, categorySlug, keyword, sort, type, false);
    }

    /**
     * 公开列表，分类无效时以 404 结束——对外 HTTP 端点用。
     *
     * <p>不能静默回首页：{@code ?categorySlug=停用slug} 若返回全站列表，调用方会把
     * 它当成该分类的内容。</p>
     */
    public Mono<ListResult<BbsPostVo>> listPublicPostsOrNotFound(int page, int size,
            String categoryName, String categorySlug, String keyword, String sort, String type) {
        return listPublicPosts(page, size, categoryName, categorySlug, keyword, sort, type, true);
    }

    private Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword, String sort, String type,
            boolean notFoundOnInvalidCategory) {
        return loadEnabledCategories()
                .flatMap(all -> {
                    var ctx = resolveContext(all, categoryName, categorySlug);
                    if (ctx == null) {
                        return notFoundOnInvalidCategory
                                ? Mono.error(new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "分类不存在"))
                                : Mono.just(ListResult.emptyResult());
                    }
                    return listPublicPosts(ctx, all, page, size, keyword, sort, type);
                });
    }

    private Mono<ListResult<BbsPostVo>> listPublicPosts(CategoryContext ctx, List<BbsCategory> allCats,
            int page, int size, String keyword, String sort, String type) {
        var pinCatNames = homePinCategoryNames(allCats, ctx);
        var base = appendFilters(visibleCondition(ctx), keyword, type);

        var timeOrders = new ArrayList<Sort.Order>();
        if (SORT_HOT.equals(sort)) {
            // 评论数已由调和器落在 status 并建了索引，热门可直接走数据库排序 + 分页：
            // 旧帖不再被扫描窗口挡在排名之外，total 也回到真实总数
            timeOrders.add(Sort.Order.desc("status.commentsCount"));
            timeOrders.add(Sort.Order.desc("spec.publishTime"));
        } else if (SORT_LATEST.equals(sort)) {
            timeOrders.add(Sort.Order.desc("spec.publishTime"));
        } else {
            timeOrders.add(Sort.Order.desc("spec.lastActivityTime"));
            timeOrders.add(Sort.Order.desc("spec.publishTime"));
        }
        var pinOrders = new ArrayList<>(PINNED_FIRST);
        pinOrders.addAll(timeOrders);
        var pinSort = Sort.by(pinOrders);
        var normalSort = Sort.by(timeOrders);

        var pinCond = pinCondition(base, pinCatNames, ctx);
        var normalCond = normalCondition(base, pinCatNames, ctx);

        Mono<List<BbsPost>> pinsMono = page == 1 && pinCond != null
                ? client.listAll(BbsPost.class, buildOptions(pinCond), pinSort).collectList()
                : Mono.just(List.of());

        int p = Math.max(1, page);
        int s = Math.max(1, size);
        return pinsMono.flatMap(pins ->
                client.listBy(BbsPost.class, buildOptions(normalCond),
                                PageRequestImpl.of(p, s, normalSort))
                        .flatMap(normalPage -> {
                            var merged = new ArrayList<BbsPost>(
                                    pins.size() + normalPage.getItems().size());
                            merged.addAll(pins);
                            merged.addAll(normalPage.getItems());
                            // total 只计普通流，置顶不占页码
                            return assembleVos(merged, false)
                                    .map(vos -> markPinnedInView(vos, pins))
                                    .map(vos -> new ListResult<>(
                                            p, s, normalPage.getTotal(), vos));
                        }));
    }

    /**
     * 把本视图真正浮顶的那批 VO 标记为 {@code pinnedInView}——前台徽标据此渲染。
     *
     * <p>普通流里可能混有 {@code pinned=true} 但本视图未浮顶的帖（首页里所属分类
     * 不在置顶作用域内——即其一级分类未开 {@code pinToHome}，或帖子无分类），
     * 它们保持 false，否则图标会指向一个并不存在的置顶位置。</p>
     */
    private static List<BbsPostVo> markPinnedInView(List<BbsPostVo> vos, List<BbsPost> pins) {
        if (pins.isEmpty()) {
            return vos;
        }
        var pinNames = pins.stream()
                .map(p -> p.getMetadata().getName())
                .collect(Collectors.toSet());
        vos.forEach(vo -> vo.setPinnedInView(pinNames.contains(vo.getName())));
        return vos;
    }

    /**
     * 首页置顶的候选分类名：**仅一级分类**可开 {@code pinToHome}，开了即覆盖整棵子树
     * （本级 + 全部子分类）——与分类页作用域、分类 RSS 同口径。
     *
     * <p>子分类自身的 {@code pinToHome} 一律不认：「上首页」是板块级特权，开到叶子层
     * 会让每个子分类都能往首页塞置顶帖。表单已对二级隐藏该开关，存量脏数据由
     * {@code BbsCategoryReconciler} 抹平，这里再挡一道。</p>
     *
     * <p>分类页不走这里——分类页的置顶只看帖的 {@code pinned}。</p>
     */
    private static List<String> homePinCategoryNames(List<BbsCategory> all, CategoryContext ctx) {
        if (!ctx.isHome()) {
            return List.of();
        }
        return homePinScope(all);
    }

    /** 首页置顶作用域：开了 {@code pinToHome} 的一级分类 + 其全部直接子分类（包可见，供单测）。 */
    static List<String> homePinScope(List<BbsCategory> all) {
        var roots = all.stream()
                .filter(c -> StringUtils.isBlank(c.getSpec().getParentName()))
                .filter(c -> Boolean.TRUE.equals(c.getSpec().getPinToHome()))
                .map(c -> c.getMetadata().getName())
                .collect(Collectors.toSet());
        if (roots.isEmpty()) {
            return List.of();
        }
        var names = new ArrayList<>(roots);
        all.stream()
                .filter(c -> roots.contains(c.getSpec().getParentName()))
                .map(c -> c.getMetadata().getName())
                .forEach(names::add);
        return names;
    }

    /**
     * 置顶条件：首页 = pinned ∩ 分类落在首页置顶作用域内（见 {@link #homePinScope}）；
     * 分类页 = 作用域内 pinned。作用域为空时首页返回 null（表示无置顶段）。
     */
    private static Condition pinCondition(Condition base, List<String> homePinCats, CategoryContext ctx) {
        if (ctx.isHome()) {
            if (homePinCats.isEmpty()) {
                return null;
            }
            return and(base, and(equal("spec.pinned", Boolean.TRUE),
                    in("spec.categoryName", homePinCats)));
        }
        return and(base, equal("spec.pinned", Boolean.TRUE));
    }

    /** 普通流：排除本视图全部置顶候选（不仅是第 1 页展示出来的）。 */
    private static Condition normalCondition(Condition base, List<String> homePinCats,
            CategoryContext ctx) {
        if (ctx.isHome()) {
            if (homePinCats.isEmpty()) {
                return base;
            }
            // 排除：pinned 且分类落在首页置顶作用域内
            return and(base, not(and(equal("spec.pinned", Boolean.TRUE),
                    in("spec.categoryName", homePinCats))));
        }
        return and(base, not(equal("spec.pinned", Boolean.TRUE)));
    }

    private static Condition appendFilters(Condition base, String keyword, String type) {
        var c = base;
        if (StringUtils.isNotBlank(keyword)) {
            c = and(c, contains("spec.title", keyword));
        }
        var typeFilter = normalizeType(type);
        if (typeFilter != null) {
            c = and(c, equal("spec.type", typeFilter));
        }
        return c;
    }

    /** 分类 / 类型 / 状态筛选：Console 与 UC 列表共用，一律含 {@code spec.draft.*} 镜像口径。 */
    private static Condition appendSharedFilters(Condition condition, String categoryName,
            String type, String phase) {
        if (StringUtils.isNotBlank(categoryName)) {
            condition = append(condition, or(
                    equal("spec.categoryName", categoryName),
                    equal("spec.draft.categoryName", categoryName)));
        }
        var typeFilter = normalizeType(type);
        if (typeFilter != null) {
            condition = append(condition, or(
                    equal("spec.type", typeFilter),
                    equal("spec.draft.type", typeFilter)));
        }
        if (StringUtils.isNotBlank(phase)) {
            var phaseFilter = phase.toUpperCase();
            condition = append(condition, isDraftReviewPhase(phaseFilter)
                    ? or(equal("spec.phase", phaseFilter),
                            equal("spec.draft.phase", phaseFilter))
                    : equal("spec.phase", phaseFilter));
        }
        return condition;
    }

    /** 类型筛选白名单（非法值忽略，等同不过滤）。 */
    private static String normalizeType(String type) {
        if (StringUtils.isBlank(type)) {
            return null;
        }
        try {
            return BbsPost.PostType.valueOf(type.toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** PUBLISHED 修改稿只有待审核 / 已驳回需要进入对应管理筛选，普通工作稿仍归“已发布”。 */
    private static boolean isDraftReviewPhase(String phase) {
        return BbsPost.Phase.PENDING.name().equalsIgnoreCase(phase)
                || BbsPost.Phase.REJECTED.name().equalsIgnoreCase(phase);
    }

    // ---------------- 分类上下文与作用域条件 ----------------

    /** 分类上下文：self=null 表示首页；parent 非空表示子分类页；children 仅一级分类填充。 */
    private record CategoryContext(BbsCategory self, BbsCategory parent,
            List<BbsCategory> children) {

        static final CategoryContext HOME = new CategoryContext(null, null, List.of());

        boolean isHome() {
            return self == null;
        }

        boolean isChild() {
            return parent != null;
        }

        String selfName() {
            return self.getMetadata().getName();
        }
    }

    /** 启用中的分类全集（分类量小，整取组树）。null 视为启用（字段默认就是 true）。 */
    private Mono<List<BbsCategory>> loadEnabledCategories() {
        var options = ListOptions.builder()
                .fieldQuery(or(equal("spec.enabled", true), isNull("spec.enabled")))
                .build();
        return client.listAll(BbsCategory.class, options, CATEGORY_SORT).collectList();
    }

    /**
     * 解析分类上下文（name 优先，其次 slug）。
     * 未指定分类返回首页；指定了但启用集里找不到（停用 / 不存在 / 拼错）返回 {@code null}，
     * 由调用方 404——不能回首页，否则 {@code /bbs?category=停用slug} 会静默变成全站列表。
     * 两级封顶防御：parentName 指向的分类自身还有父（脏数据）时不认此父子关系。
     */
    private static CategoryContext resolveContext(List<BbsCategory> all, String name,
            String slug) {
        boolean specified = StringUtils.isNotBlank(name) || StringUtils.isNotBlank(slug);
        BbsCategory self = null;
        if (StringUtils.isNotBlank(name)) {
            self = all.stream()
                    .filter(c -> name.equals(c.getMetadata().getName()))
                    .findFirst().orElse(null);
        } else if (StringUtils.isNotBlank(slug)) {
            self = all.stream()
                    .filter(c -> slug.equals(c.getSpec().getSlug()))
                    .findFirst().orElse(null);
        }
        if (self == null) {
            return specified ? null : CategoryContext.HOME;
        }
        var byName = all.stream().collect(Collectors.toMap(
                c -> c.getMetadata().getName(), Function.identity(), (a, b) -> a));
        BbsCategory parent = null;
        var parentName = self.getSpec().getParentName();
        if (StringUtils.isNotBlank(parentName)) {
            var candidate = byName.get(parentName);
            if (candidate != null && StringUtils.isBlank(candidate.getSpec().getParentName())) {
                parent = candidate;
            }
        }
        if (parent != null) {
            return new CategoryContext(self, parent, List.of());
        }
        var selfName = self.getMetadata().getName();
        var children = all.stream()
                .filter(c -> selfName.equals(c.getSpec().getParentName()))
                .toList();
        return new CategoryContext(self, null, children);
    }

    /**
     * 页面可见集合：纯分类作用域，公告与普通帖同规则（无跨分类特权）——
     * 首页为全部已发布帖子；一级分类页为本级 + 全部子分类；子分类页仅本子分类。
     */
    private static Condition visibleCondition(CategoryContext ctx) {
        var published = and(equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                equal("spec.deleted", false));
        if (ctx.isHome()) {
            return published;
        }
        if (ctx.isChild()) {
            return and(published, equal("spec.categoryName", ctx.selfName()));
        }
        var names = new ArrayList<String>();
        names.add(ctx.selfName());
        ctx.children().forEach(c -> names.add(c.getMetadata().getName()));
        return and(published, in("spec.categoryName", names));
    }

    // ---------------- 其余公开查询 ----------------

    /** 公告列表：已发布公告，按置顶权重与发布时间排序。 */
    public Flux<BbsPostVo> listAnnouncements(int limit) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.deleted", false),
                        equal("spec.type", BbsPost.PostType.ANNOUNCEMENT.name())))
                .build();
        var sort = Sort.by(
                Sort.Order.desc("spec.pinned"),
                Sort.Order.desc("spec.pinPriority"),
                Sort.Order.desc("spec.publishTime"));
        return listVos(options, 1, Math.max(1, limit), sort)
                .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    /** 最新已发布内容（含公告，按发布时间倒序，不做置顶提权）——全站 RSS 等时间线场景用。 */
    public Flux<BbsPostVo> listLatestPublished(int size) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.deleted", false)))
                .build();
        var sort = Sort.by(Sort.Order.desc("spec.publishTime"));
        return listVos(options, 1, Math.max(1, size), sort)
                .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    /**
     * 全站最多回复的已发布内容：完全走 {@code status.commentsCount} 索引，
     * 不叠加置顶排序，也不以最近发布时间窗口抽样。
     */
    public Flux<BbsPostVo> listMostRepliedPublished(int size) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.deleted", false)))
                .build();
        var sort = Sort.by(
                Sort.Order.desc("status.commentsCount"),
                Sort.Order.desc("spec.publishTime"),
                Sort.Order.asc("metadata.name"));
        return listVos(options, 1, Math.max(1, size), sort)
                .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    /**
     * 某一级分类树内（本级 + 子分类）最新已发布内容——分类 RSS 用。
     * 未选分类的帖子不属于任何分类树，只进全站 feed。
     */
    public Flux<BbsPostVo> listLatestByCategorySlug(String slug, int size) {
        return loadEnabledCategories().flatMapMany(all -> {
            var ctx = resolveContext(all, null, slug);
            if (ctx == null || ctx.isHome()) {
                return Flux.empty();
            }
            var names = new ArrayList<String>();
            names.add(ctx.selfName());
            ctx.children().forEach(c -> names.add(c.getMetadata().getName()));
            var options = ListOptions.builder()
                    .fieldQuery(and(
                            isNull("metadata.deletionTimestamp"),
                            equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                            equal("spec.deleted", false),
                            in("spec.categoryName", names)))
                    .build();
            return listVos(options, 1, Math.max(1, size),
                    Sort.by(Sort.Order.desc("spec.publishTime")))
                    .flatMapMany(result -> Flux.fromIterable(result.getItems()));
        });
    }

    /** 某作者的已发布内容分页（含公告，发布时间倒序）。 */
    public Mono<ListResult<BbsPostVo>> listPublicByOwner(String owner, int page, int size) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.deleted", false),
                        equal("spec.owner", owner)))
                .build();
        return listVos(options, page, size, Sort.by(Sort.Order.desc("spec.publishTime")));
    }

    /** 作者展示信息（用户不存在时以用户名兜底）。 */
    public Mono<OwnerVo> getAuthor(String username) {
        return client.fetch(User.class, username)
                .map(user -> OwnerVo.from(username, user))
                .defaultIfEmpty(OwnerVo.from(username, null));
    }

    /**
     * 只读评论列表（锁定帖的历史评论，bbs.js roItem 消费）。
     * 与 Halo 公开评论 API 的差别：{@code owner.name} 仅 User kind 返回（供 hip-user-avatar
     * 拉装扮）；Email kind 不返回 name（防 email 泄露）。content 为 Halo 已净化 HTML。
     * 批量装配：先收集 User owner 名 + Counter 名，各一次 listAll 建 Map 再内联，避免 N+1。
     *
     * @param replySize 每条评论预取的楼中楼条数（&lt;=0 不预取）；超出部分前端再走
     *                  {@link #listRoReplies} 分页展开
     */
    public Mono<ListResult<RoCommentVo>> listRoComments(String postName, int page, int size,
            int replySize) {
        if (StringUtils.isBlank(postName)) {
            return Mono.just(ListResult.emptyResult());
        }
        // 先确认帖仍公开（已发布、未软删、未打删除戳）；回收 / 撤下后历史评论不再对外
        return requirePublicPost(postName)
                .flatMap(ignored -> doListRoComments(postName, page, size, replySize))
                .defaultIfEmpty(ListResult.emptyResult());
    }

    private Mono<ListResult<RoCommentVo>> doListRoComments(String postName, int page, int size,
            int replySize) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.subjectRef", "bbs.timxs.com/BbsPost/" + postName),
                        equal("spec.approved", true),
                        equal("spec.hidden", false),
                        isNull("metadata.deletionTimestamp")))
                .build();
        /* 楼层流按发表时间正序：1 楼是楼主，评论顺延 2 楼、3 楼往下时间递增——
           这是「楼层」语义（含前端楼层号）成立的前提，倒序会让号的方向和阅读方向都反过来。
           与 REPLY_SORT 同向，两级一致；置顶评论仍浮顶（top desc 在最前）。
           代价：非锁定态走官方评论组件是最新在前，同一帖锁定前后顺序会颠倒（已确认接受）。 */
        var sort = Sort.by(
                Sort.Order.desc("spec.top"),
                Sort.Order.asc("spec.priority"),
                Sort.Order.asc("spec.creationTime"),
                Sort.Order.asc("metadata.name"));
        return client.listBy(Comment.class, options, PageRequestImpl.of(page, size, sort))
                .flatMap(result -> fetchPreviewReplies(result.getItems(), replySize)
                        .flatMap(previews -> assembleRoComments(result.getItems(), previews))
                        .map(vos -> new ListResult<>(
                                result.getPage(), result.getSize(), result.getTotal(), vos)));
    }

    /**
     * 只读回复列表（某评论的楼中楼，bbs.js roReplies 消费）。同 listRoComments 的装扮 +
     * 批量装配；Reply 无 replyCount（回复无子回复）。Counter meterName = replies.content.halo.run/{name}。
     */
    public Mono<ListResult<RoCommentVo>> listRoReplies(String commentName, int page, int size) {
        if (StringUtils.isBlank(commentName)) {
            return Mono.just(ListResult.emptyResult());
        }
        // 必须挂在仍公开的 BBS 帖上——否则任意 Halo 评论名都能拉博客 / 单页楼中楼
        return client.fetch(Comment.class, commentName)
                .flatMap(comment -> {
                    var ref = comment.getSpec() == null ? null : comment.getSpec().getSubjectRef();
                    if (ref == null
                            || !POST_GVK.group().equals(ref.getGroup())
                            || !POST_GVK.kind().equals(ref.getKind())) {
                        return Mono.empty();
                    }
                    return requirePublicPost(ref.getName());
                })
                .flatMap(ignored -> client.listBy(Reply.class, replyListOptions(commentName),
                        PageRequestImpl.of(page, size, REPLY_SORT)))
                .flatMap(result -> assembleRoReplies(result.getItems())
                        .map(vos -> new ListResult<>(
                                result.getPage(), result.getSize(), result.getTotal(), vos)))
                .defaultIfEmpty(ListResult.emptyResult());
    }

    /** 帖仍对外公开：已发布、未进回收站、未打删除戳。 */
    private Mono<BbsPost> requirePublicPost(String postName) {
        return client.fetch(BbsPost.class, postName)
                .filter(post -> post.getMetadata().getDeletionTimestamp() == null
                        && post.getSpec() != null
                        && post.getSpec().getPhase() == BbsPost.Phase.PUBLISHED
                        && !Boolean.TRUE.equals(post.getSpec().getDeleted()));
    }

    /** 楼中楼查询条件：已审核、非私密、未删除。 */
    private static ListOptions replyListOptions(String commentName) {
        return ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.commentName", commentName),
                        equal("spec.approved", true),
                        equal("spec.hidden", false),
                        isNull("metadata.deletionTimestamp")))
                .build();
    }

    /**
     * 并发预取每条评论的前 {@code replySize} 条楼中楼，返回 commentName → 回复列表。
     *
     * <p>仅对 {@code replyCount>0} 的评论发查询：一页 20 条评论最多 20 次内存索引查询，
     * 仍远优于前端逐条发 HTTP；无回复的评论一次查询都不发。</p>
     */
    private Mono<Map<String, List<Reply>>> fetchPreviewReplies(List<Comment> comments,
            int replySize) {
        if (replySize <= 0 || comments.isEmpty()) {
            return Mono.just(Map.of());
        }
        var targets = comments.stream()
                .filter(c -> visibleReplyCount(c) > 0)
                .map(c -> c.getMetadata().getName())
                .toList();
        if (targets.isEmpty()) {
            return Mono.just(Map.of());
        }
        return Flux.fromIterable(targets)
                .flatMap(commentName -> client
                        .listBy(Reply.class, replyListOptions(commentName),
                                PageRequestImpl.of(1, replySize, REPLY_SORT))
                        .map(ListResult::getItems)
                        .filter(items -> items != null && !items.isEmpty())
                        .map(items -> Map.entry(commentName, items)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * 批量装配评论及其预取回复：评论与回复的 User owner 名 + Counter 名合并收集，
     * 共用一次上下文查询（各一次 listAll 建 Map）再内联。
     */
    private Mono<List<RoCommentVo>> assembleRoComments(List<Comment> comments,
            Map<String, List<Reply>> previews) {
        if (comments.isEmpty()) {
            return Mono.just(List.of());
        }
        var allReplies = previews.values().stream().flatMap(List::stream).toList();
        return fetchQuotes(allReplies).flatMap(quotes -> {
            var ownerNames = new HashSet<String>();
            var counterNames = new HashSet<String>();
            for (Comment c : comments) {
                collectUserOwner(c.getSpec().getOwner(), ownerNames);
                counterNames.add("comments.content.halo.run/" + c.getMetadata().getName());
                for (Reply r : previews.getOrDefault(c.getMetadata().getName(), List.of())) {
                    collectUserOwner(r.getSpec().getOwner(), ownerNames);
                    counterNames.add("replies.content.halo.run/" + r.getMetadata().getName());
                }
            }
            // 被引用者的昵称也走 User 表取最新值（改名后旧 owner 快照会过时）
            for (Reply q : quotes.values()) {
                collectUserOwner(q.getSpec().getOwner(), ownerNames);
            }
            return fetchRoContext(ownerNames, counterNames, quotes).map(ctx -> comments.stream()
                    .map(c -> {
                        var vo = buildRoVo(c.getMetadata().getName(), c.getSpec().getOwner(),
                                c.getSpec().getContent(), c.getSpec().getCreationTime(),
                                c.getSpec().getTop(), c.getSpec().getPriority(),
                                visibleReplyCount(c),
                                "comments.content.halo.run/" + c.getMetadata().getName(), ctx);
                        var replies = previews.getOrDefault(c.getMetadata().getName(), List.of());
                        if (!replies.isEmpty()) {
                            vo.setReplies(replies.stream().map(r -> buildReplyVo(r, ctx)).toList());
                        }
                        return vo;
                    })
                    .toList());
        });
    }

    /** 批量装配回复：同 assembleRoComments，Counter meterName 用 replies.content.halo.run/。 */
    private Mono<List<RoCommentVo>> assembleRoReplies(List<Reply> replies) {
        if (replies.isEmpty()) {
            return Mono.just(List.of());
        }
        return fetchQuotes(replies).flatMap(quotes -> {
            var ownerNames = new HashSet<String>();
            var counterNames = new HashSet<String>();
            for (Reply r : replies) {
                collectUserOwner(r.getSpec().getOwner(), ownerNames);
                counterNames.add("replies.content.halo.run/" + r.getMetadata().getName());
            }
            for (Reply q : quotes.values()) {
                collectUserOwner(q.getSpec().getOwner(), ownerNames);
            }
            return fetchRoContext(ownerNames, counterNames, quotes)
                    .map(ctx -> replies.stream().map(r -> buildReplyVo(r, ctx)).toList());
        });
    }

    /**
     * 被引用回复批量取回（{@code Reply.spec.quoteReply} → 那条回复本身），供楼中楼渲染
     * 「回复 @昵称」。
     *
     * <p>刻意回表查而不是在已加载集合里找：列表页每条评论只预取前几条回复
     * （{@code RO_REPLY_PREVIEW}），引用目标经常不在其中，就地找会大面积漏掉 @。</p>
     *
     * <p>过滤条件与 {@link #replyListOptions} 同口径（已审核 + 非私密）——被隐藏的回复
     * 不该通过「谁被 @ 了」这条侧信道泄露作者昵称；查不到则前端不显示 @。</p>
     */
    private Mono<Map<String, Reply>> fetchQuotes(List<Reply> replies) {
        var names = replies.stream()
                .map(r -> r.getSpec().getQuoteReply())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        if (names.isEmpty()) {
            return Mono.just(Map.of());
        }
        var options = ListOptions.builder()
                .fieldQuery(and(
                        in("metadata.name", names),
                        equal("spec.approved", true),
                        equal("spec.hidden", false),
                        isNull("metadata.deletionTimestamp")))
                .build();
        return client.listAll(Reply.class, options, Sort.unsorted())
                .collectMap(r -> r.getMetadata().getName());
    }

    /** Reply → VO（回复无子回复，replyCount 恒 0）；带被引用者供前端渲染「回复 @昵称」。 */
    private RoCommentVo buildReplyVo(Reply r, RoContext ctx) {
        var vo = buildRoVo(r.getMetadata().getName(), r.getSpec().getOwner(),
                r.getSpec().getContent(), r.getSpec().getCreationTime(),
                r.getSpec().getTop(), r.getSpec().getPriority(), 0,
                "replies.content.halo.run/" + r.getMetadata().getName(), ctx);
        var quoteName = r.getSpec().getQuoteReply();
        if (StringUtils.isNotBlank(quoteName)) {
            var quoted = ctx.quotes().get(quoteName);
            if (quoted != null) {
                vo.setQuote(CommentOwnerVo.from(quoted.getSpec().getOwner(), ctx.users()));
            }
        }
        return vo;
    }

    /**
     * 可见回复数：口径须与 {@link #replyListOptions} 一致（已审核 + 非私密），否则前端
     * 「展开剩余 N 条」会数不对。旧数据未回填 visibleReplyCount 时退回总数兜底。
     */
    private static int visibleReplyCount(Comment c) {
        var status = c.getStatus();
        if (status == null) {
            return 0;
        }
        if (status.getVisibleReplyCount() != null) {
            return status.getVisibleReplyCount();
        }
        // 未回填时回 0，不退到 replyCount——后者含隐藏 / 未审，会让「展开剩余 N 条」虚高
        return 0;
    }

    /** 收集 User kind owner 的 username（Email kind 不收集，防 email 暴露）。 */
    private static void collectUserOwner(Comment.CommentOwner owner, Set<String> names) {
        if (owner != null && "User".equals(owner.getKind())
                && StringUtils.isNotBlank(owner.getName())) {
            names.add(owner.getName());
        }
    }

    /**
     * 一次 listAll User + 一次 listAll Counter 建 Map（2 次查询替代 N+N）。
     * 空 ownerNames/counterNames 直接返回空 Map，不触发查询。
     */
    private Mono<RoContext> fetchRoContext(Set<String> ownerNames, Set<String> counterNames,
            Map<String, Reply> quotes) {
        Mono<Map<String, User>> userMapMono = ownerNames.isEmpty()
                ? Mono.just(Map.of())
                : client.listAll(User.class,
                        ListOptions.builder().fieldQuery(in("metadata.name", ownerNames)).build(),
                        Sort.unsorted())
                    .collectMap(u -> u.getMetadata().getName());
        Mono<Map<String, Counter>> counterMapMono = counterNames.isEmpty()
                ? Mono.just(Map.of())
                : client.listAll(Counter.class,
                        ListOptions.builder().fieldQuery(in("metadata.name", counterNames)).build(),
                        Sort.unsorted())
                    .collectMap(co -> co.getMetadata().getName());
        return Mono.zip(userMapMono, counterMapMono)
                .map(tuple -> new RoContext(tuple.getT1(), tuple.getT2(), quotes));
    }

    private RoCommentVo buildRoVo(String name, Comment.CommentOwner owner, String content,
            Instant creationTime, Boolean top, Integer priority, Integer replyCount,
            String counterName, RoContext ctx) {
        var counter = ctx.counters().get(counterName);
        return RoCommentVo.builder()
                .name(name)
                .owner(CommentOwnerVo.from(owner, ctx.users()))
                .content(content)
                .creationTime(creationTime != null ? creationTime.toString() : null)
                .upvote(counter != null && counter.getUpvote() != null ? counter.getUpvote() : 0)
                .replyCount(replyCount)
                .top(top)
                .priority(priority)
                .build();
    }

    /** 批量查询结果（User Map + Counter Map + 被引用回复 Map），供 buildRoVo 内联。 */
    private record RoContext(Map<String, User> users, Map<String, Counter> counters,
            Map<String, Reply> quotes) {
    }

    /** 公开详情：按 slug 取已发布帖子（含正文）。 */
    public Mono<BbsPostVo> getPublishedBySlug(String slug) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.slug", slug),
                        equal("spec.deleted", false),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name())))
                .build();
        // 并发撞 slug 时取发布时间最早的一条（确定性，避免路由随机）
        return client.listAll(BbsPost.class, options,
                        Sort.by(Sort.Order.asc("spec.publishTime"),
                                Sort.Order.asc("metadata.name")))
                .next()
                .flatMap(this::assembleDetail);
    }

    /** 按 metadata.name 装配发布 / 当前实体详情（公开详情与只读场景用）。 */
    public Mono<BbsPostVo> assembleDetail(BbsPost post) {
        return assembleVos(List.of(post), true).map(list -> list.get(0));
    }

    /**
     * 装配编辑器详情：PUBLISHED 且存在 draft 时返回工作稿字段，同时保留
     * {@code phase=PUBLISHED} 表示当前前台版本仍在线，并用 draftPhase 表达修改稿状态。
     * 该入口只供经过 Console / UC 鉴权的端点调用，公开入口绝不调用。
     */
    public Mono<BbsPostVo> assembleEditingDetail(BbsPost post) {
        return assembleEditingVos(List.of(post), true).map(list -> list.get(0));
    }

    /** 已发布帖子总数（含公告）。 */
    public Mono<Long> countPublished() {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.deleted", false)))
                .build();
        return client.countBy(BbsPost.class, options);
    }

    /** 某作者的已发布内容数（含公告）——统计贡献用，口径与 {@link #listPublicByOwner} 一致。 */
    public Mono<Long> countPublishedByOwner(String owner) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        isNull("metadata.deletionTimestamp"),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.deleted", false),
                        equal("spec.owner", owner)))
                .build();
        return client.countBy(BbsPost.class, options);
    }

    // ---------------- 分类 ----------------

    /**
     * 分类平铺列表（Console 管理用）：树序排列（一级按 priority，其子紧随其后），
     * 每项带直属已发布帖子数与含子分类合计数；一级分类附 children。
     */
    public Flux<CategoryVo> listCategories(boolean onlyEnabled) {
        return categoryVosWithCount(onlyEnabled).flatMapMany(Flux::fromIterable);
    }

    /** 分类树（前台导航用）：仅一级分类（children 内嵌），priority 升序。 */
    public Flux<CategoryVo> listCategoryTree(boolean onlyEnabled) {
        return categoryVosWithCount(onlyEnabled)
                .map(list -> list.stream()
                        .filter(vo -> StringUtils.isBlank(vo.getParentName()))
                        .toList())
                .flatMapMany(Flux::fromIterable);
    }

    /** 按 slug 取启用中的分类（完整 VO：父摘要 / 子分类 / 封面继承 / 帖子数）。 */
    public Mono<CategoryVo> getCategoryBySlug(String slug) {
        if (StringUtils.isBlank(slug)) {
            return Mono.empty();
        }
        return categoryVosWithCount(true)
                .flatMapIterable(Function.identity())
                .filter(vo -> slug.equals(vo.getSlug()))
                .next();
    }

    /** 分类 VO 全量装配：读 status 里的计数 + 组树 + 树序平铺。 */
    private Mono<List<CategoryVo>> categoryVosWithCount(boolean onlyEnabled) {
        var builder = ListOptions.builder();
        if (onlyEnabled) {
            builder.fieldQuery(or(equal("spec.enabled", true), isNull("spec.enabled")));
        }
        // 计数由调和器维护在 status 里，这里一次查询取回全部分类即可——
        // 不再为每个分类各发一次 countBy
        return client.listAll(BbsCategory.class, builder.build(), CATEGORY_SORT)
                .collectList()
                .map(BbsQueryService::assembleCategoryVos);
    }

    /** 平铺 VO 列表按树序组装：一级在前、其子紧随；聚合含子分类的帖子合计。 */
    private static List<CategoryVo> assembleCategoryVos(List<BbsCategory> cats) {
        var dict = cats.stream().collect(Collectors.toMap(
                c -> c.getMetadata().getName(), Function.identity(), (a, b) -> a));
        var vos = cats.stream().map(cat -> {
            var vo = toCategoryVo(cat, dict);
            // 前台口径取已发布数；含子分类的合计在下面按树聚合（纯内存，不再查询）
            var status = cat.getStatus();
            long count = status == null || status.getVisiblePostCount() == null
                    ? 0L : status.getVisiblePostCount();
            vo.setPostCount(count);
            vo.setTotalPostCount(count);
            return vo;
        }).toList();
        var childrenByParent = vos.stream()
                .filter(vo -> StringUtils.isNotBlank(vo.getParentName()))
                .collect(Collectors.groupingBy(CategoryVo::getParentName,
                        LinkedHashMap::new, Collectors.toList()));
        var ordered = new ArrayList<CategoryVo>(vos.size());
        for (var vo : vos) {
            if (StringUtils.isNotBlank(vo.getParentName())) {
                continue;
            }
            var children = childrenByParent.getOrDefault(vo.getName(), List.of());
            vo.setChildren(children);
            vo.setTotalPostCount(vo.getPostCount() + children.stream()
                    .mapToLong(c -> c.getPostCount() == null ? 0 : c.getPostCount()).sum());
            ordered.add(vo);
            ordered.addAll(children);
        }
        return ordered;
    }

    /**
     * 单分类 VO：内联父分类摘要 + 封面继承（子分类留空取父）。
     * 两级封顶防御：父分类自身还有父（脏数据）时不认父子关系，按一级分类展示。
     */
    private static CategoryVo toCategoryVo(BbsCategory category, Map<String, BbsCategory> dict) {
        var vo = CategoryVo.from(category);
        var parentName = category.getSpec().getParentName();
        if (StringUtils.isNotBlank(parentName)) {
            var parent = dict.get(parentName);
            if (parent != null && StringUtils.isBlank(parent.getSpec().getParentName())) {
                vo.setParent(CategoryVo.from(parent));
                if (StringUtils.isBlank(vo.getCover())) {
                    vo.setCover(BbsUrls.sanitize(parent.getSpec().getCover()));
                }
            } else {
                vo.setParentName(null);
            }
        }
        return vo;
    }

    // ---------------- 装配 ----------------

    private static Condition append(Condition base, Condition next) {
        return base == null ? next : and(base, next);
    }

    private ListOptions buildOptions(Condition condition) {
        var builder = ListOptions.builder();
        // 彻底删除已打删除戳、finalizer 未跑完的窗口里，前台 / Console 都不应再看到该帖
        var notDeleting = isNull("metadata.deletionTimestamp");
        builder.fieldQuery(condition == null ? notDeleting : and(notDeleting, condition));
        return builder.build();
    }

    private Mono<ListResult<BbsPostVo>> listVos(ListOptions options, int page, int size,
            Sort sort) {
        return client.listBy(BbsPost.class, options, PageRequestImpl.of(page, size, sort))
                .flatMap(result -> assembleVos(result.getItems(), false)
                        .map(vos -> new ListResult<>(
                                result.getPage(), result.getSize(), result.getTotal(), vos)));
    }

    /** Console / UC 列表专用：允许展示 head 工作稿，公开列表仍走上面的 release 装配。 */
    private Mono<ListResult<BbsPostVo>> listVos(ListOptions options, int page, int size,
            Sort sort, boolean editing) {
        if (!editing) {
            return listVos(options, page, size, sort);
        }
        return client.listBy(BbsPost.class, options, PageRequestImpl.of(page, size, sort))
                .flatMap(result -> assembleEditingVos(result.getItems(), false)
                        .map(vos -> new ListResult<>(
                                result.getPage(), result.getSize(), result.getTotal(), vos)));
    }

    /** 批量装配：一次性载入分类（全量，供父摘要 / 封面继承）与作者字典，内联展示属性。 */
    private Mono<List<BbsPostVo>> assembleVos(List<BbsPost> posts, boolean withContent) {
        return assembleVos(posts, withContent, false);
    }

    private Mono<List<BbsPostVo>> assembleEditingVos(List<BbsPost> posts, boolean withContent) {
        return assembleVos(posts, withContent, true);
    }

    private Mono<List<BbsPostVo>> assembleVos(List<BbsPost> posts, boolean withContent,
            boolean editing) {
        if (posts.isEmpty()) {
            return Mono.just(List.of());
        }
        Set<String> userNames = new HashSet<>();
        for (BbsPost post : posts) {
            addIfNotBlank(userNames, post.getSpec().getOwner());
        }
        var categoriesMono = fetchCategoryDict();
        var usersMono = fetchMapByNames(User.class, userNames);
        var contentsMono = contentService.resolveContents(posts, editing);
        // 评论数已由调和器维护在 status 里，不再逐帖 countBy（原本一页 20 条 = 20 次查询）
        return Mono.zip(categoriesMono, usersMono, contentsMono)
                .map(tuple -> posts.stream()
                        .map(post -> buildVo(post, tuple.getT1(), tuple.getT2(), withContent,
                                editing, tuple.getT3().get(post.getMetadata().getName())))
                        .toList());
    }

    /** 全量分类字典（含停用——帖子所属分类停用后徽章仍可渲染）。 */
    private Mono<Map<String, BbsCategory>> fetchCategoryDict() {
        return client.listAll(BbsCategory.class, ListOptions.builder().build(), Sort.unsorted())
                .collectMap(c -> c.getMetadata().getName());
    }

    private BbsPostVo buildVo(BbsPost post, Map<String, BbsCategory> categories,
            Map<String, User> users, boolean withContent, boolean editing, String content) {
        var spec = post.getSpec();
        var draft = editing && spec.getPhase() == BbsPost.Phase.PUBLISHED
                ? spec.getDraft() : null;
        var title = draft == null ? spec.getTitle() : draft.getTitle();
        var slug = draft == null ? spec.getSlug() : draft.getSlug();
        var type = draft == null ? spec.getType() : draft.getType();
        var categoryName = draft == null ? spec.getCategoryName() : draft.getCategoryName();
        var excerpt = draft == null ? spec.getExcerpt() : draft.getExcerpt();
        var category = categoryName == null ? null : categories.get(categoryName);
        return BbsPostVo.builder()
                .name(post.getMetadata().getName())
                .title(title)
                .slug(slug)
                .type(type == null ? BbsPost.PostType.POST.name() : type.name())
                .phase(spec.getPhase() == null
                        ? BbsPost.Phase.DRAFT.name() : spec.getPhase().name())
                .draftPhase(draft == null || draft.getPhase() == null
                        ? null : draft.getPhase().name())
                .hasDraft(editing && spec.getPhase() == BbsPost.Phase.PUBLISHED
                        && StringUtils.isNotBlank(spec.getHeadSnapshot())
                        && !Objects.equals(spec.getHeadSnapshot(), spec.getReleaseSnapshot()))
                .baseSnapshot(editing ? spec.getBaseSnapshot() : null)
                .headSnapshot(editing ? spec.getHeadSnapshot() : null)
                .releaseSnapshot(editing ? spec.getReleaseSnapshot() : null)
                .snapshotVersion(editing && post.getStatus() != null
                        ? post.getStatus().getHeadSnapshotVersion() : null)
                .pinned(Boolean.TRUE.equals(spec.getPinned()))
                // 视图态默认 false，由列表入口对真正浮顶的那批回填（见 markPinnedInView）
                .pinnedInView(false)
                .pinPriority(spec.getPinPriority() == null ? 0 : spec.getPinPriority())
                .locked(Boolean.TRUE.equals(spec.getLocked()))
                .solved(Boolean.TRUE.equals(spec.getSolved()))
                .rejectReason(draft == null ? spec.getRejectReason() : draft.getRejectReason())
                .commentsCount(safeCount(post, BbsPost.Status::getCommentsCount))
                .totalCommentCount(safeCount(post, BbsPost.Status::getTotalCommentCount))
                .pendingCommentCount(safeCount(post, BbsPost.Status::getPendingCommentCount))
                .excerpt(BbsExcerpts.resolve(excerpt, content))
                .autoExcerpt(BbsExcerpts.isAuto(excerpt))
                .content(withContent ? content : null)
                // 前台链接永远指向当前 release slug；工作稿 slug 尚未发布，不能预览它。
                .permalink(BbsUrls.postPermalink(spec.getSlug()))
                .category(category == null ? null : toCategoryVo(category, categories))
                .owner(spec.getOwner() == null
                        ? null : OwnerVo.from(spec.getOwner(), users.get(spec.getOwner())))
                .publishTime(spec.getPublishTime())
                .lastActivityTime(spec.getLastActivityTime() != null
                        ? spec.getLastActivityTime() : spec.getPublishTime())
                .lastEditTime(draft == null ? spec.getLastEditTime() : draft.getLastEditTime())
                .creationTimestamp(post.getMetadata().getCreationTimestamp())
                .build();
    }

    private <T extends AbstractExtension> Mono<Map<String, T>> fetchMapByNames(Class<T> type,
            Collection<String> names) {
        if (names.isEmpty()) {
            return Mono.just(Map.of());
        }
        var options = ListOptions.builder()
                .fieldQuery(in("metadata.name", names))
                .build();
        return client.listAll(type, options, Sort.unsorted())
                .collectMap(ext -> ext.getMetadata().getName());
    }

    private static void addIfNotBlank(Set<String> set, String value) {
        if (StringUtils.isNotBlank(value)) {
            set.add(value);
        }
    }

    /** Jackson 缺 status / 计数字段时不当 NPE，按 0 计。 */
    private static long safeCount(BbsPost post, Function<BbsPost.Status, Integer> getter) {
        var status = post.getStatus();
        if (status == null) {
            return 0L;
        }
        var value = getter.apply(status);
        return value == null ? 0L : value.longValue();
    }
}
