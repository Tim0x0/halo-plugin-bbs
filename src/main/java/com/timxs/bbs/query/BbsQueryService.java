package com.timxs.bbs.query;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;
import static run.halo.app.extension.index.query.Queries.or;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import com.timxs.bbs.vo.OwnerVo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
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
 * <p><b>分类作用域</b>（两级分类，公告与普通帖同规则、无跨分类特权）：
 * 首页 = 全部已发布帖子；一级分类页 = 本级 + 全部子分类帖子；子分类页 = 本子分类帖子。
 * 未选分类的帖子（含公告）只在首页出现。</p>
 *
 * <p><b>置顶为排序键而非独立段</b>（对齐 Flarum / Discourse）：
 * 列表统一按 {@code pinned desc → pinPriority desc → 时间 desc} 排序，置顶帖占用分页
 * 名额浮在当前视图最前。单查询单列表，故分页天然对齐、无重复、置顶再多也不会丢帖，
 * 且关键词 / 类型筛选下置顶帖照常参与结果。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsQueryService {

    /** Console 列表排序白名单（键 → Sort），防任意字段排序打到未索引字段。 */
    private static final Map<String, Sort> CONSOLE_SORTS = Map.of(
            "creationTimestamp,desc", Sort.by(Sort.Order.desc("metadata.creationTimestamp")),
            "creationTimestamp,asc", Sort.by(Sort.Order.asc("metadata.creationTimestamp")),
            "publishTime,desc", Sort.by(Sort.Order.desc("spec.publishTime")),
            "publishTime,asc", Sort.by(Sort.Order.asc("spec.publishTime")));

    private static final GroupVersionKind POST_GVK = GroupVersionKind.fromExtension(BbsPost.class);

    /** 前台排序标识：热门（评论数） / 最后活跃（回帖顶起，默认） / 最新发布。 */
    public static final String SORT_HOT = "hot";
    public static final String SORT_ACTIVE = "active";
    public static final String SORT_LATEST = "latest";

    /** 热门排序参与排名的最大扫描条数（更早的旧帖不参与热门排名）。 */
    private static final int HOT_SCAN_LIMIT = 300;

    /** 置顶优先的排序前缀（所有前台排序共用；索引已把 pinned 归一为 true/false）。 */
    private static final List<Sort.Order> PINNED_FIRST = List.of(
            Sort.Order.desc("spec.pinned"),
            Sort.Order.desc("spec.pinPriority"));

    /** 分类展示排序：priority 升序，同权重按创建时间。 */
    private static final Sort CATEGORY_SORT = Sort.by(
            Sort.Order.asc("spec.priority"),
            Sort.Order.asc("metadata.creationTimestamp"));

    private final ReactiveExtensionClient client;

    public BbsQueryService(ReactiveExtensionClient client) {
        this.client = client;
    }

    // ---------------- 列表查询 ----------------

    /** Console 管理列表：关键词 / 分类 / 类型 / 状态筛选 + 排序白名单。 */
    public Mono<ListResult<BbsPostVo>> listConsole(int page, int size, String keyword,
            String categoryName, String type, String phase, String sort) {
        Condition condition = null;
        if (StringUtils.isNotBlank(keyword)) {
            condition = append(condition, contains("spec.title", keyword));
        }
        if (StringUtils.isNotBlank(categoryName)) {
            condition = append(condition, equal("spec.categoryName", categoryName));
        }
        var typeFilter = normalizeType(type);
        if (typeFilter != null) {
            condition = append(condition, equal("spec.type", typeFilter));
        }
        if (StringUtils.isNotBlank(phase)) {
            condition = append(condition, equal("spec.phase", phase));
        }
        var sortOrder = CONSOLE_SORTS.getOrDefault(
                java.util.Objects.toString(sort, ""),
                CONSOLE_SORTS.get("creationTimestamp,desc"));
        return listVos(buildOptions(condition), page, size, sortOrder);
    }

    /** UC「我的帖子」列表（关键词 / 状态 / 分类 / 类型筛选）。 */
    public Mono<ListResult<BbsPostVo>> listMine(String owner, int page, int size,
            String keyword, String phase, String categoryName, String type) {
        Condition condition = equal("spec.owner", owner);
        if (StringUtils.isNotBlank(keyword)) {
            condition = append(condition, contains("spec.title", keyword));
        }
        if (StringUtils.isNotBlank(phase)) {
            condition = append(condition, equal("spec.phase", phase));
        }
        if (StringUtils.isNotBlank(categoryName)) {
            condition = append(condition, equal("spec.categoryName", categoryName));
        }
        var typeFilter = normalizeType(type);
        if (typeFilter != null) {
            condition = append(condition, equal("spec.type", typeFilter));
        }
        return listVos(buildOptions(condition),
                page, size, Sort.by(Sort.Order.desc("metadata.creationTimestamp")));
    }

    /** 公开列表（默认排序，兼容旧签名）。 */
    public Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword) {
        return listPublicPosts(page, size, categoryName, categorySlug, keyword, null, null);
    }

    /** 公开列表（可指定排序，兼容旧签名）。 */
    public Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword, String sort) {
        return listPublicPosts(page, size, categoryName, categorySlug, keyword, sort, null);
    }

    /**
     * 公开列表主入口：已发布内容（公告与普通帖 / 问答帖混排，置顶浮在最前）。
     *
     * @param sort {@link #SORT_ACTIVE}（默认，最后活跃）/ {@link #SORT_LATEST} / {@link #SORT_HOT}
     * @param type 类型筛选（POST / QUESTION / ANNOUNCEMENT，空 = 全部）
     */
    public Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword, String sort, String type) {
        return loadEnabledCategories()
                .flatMap(all -> listPublicPosts(
                        resolveContext(all, categoryName, categorySlug),
                        page, size, keyword, sort, type));
    }

    private Mono<ListResult<BbsPostVo>> listPublicPosts(CategoryContext ctx, int page, int size,
            String keyword, String sort, String type) {
        var condition = visibleCondition(ctx);
        if (StringUtils.isNotBlank(keyword)) {
            condition = and(condition, contains("spec.title", keyword));
        }
        var typeFilter = normalizeType(type);
        if (typeFilter != null) {
            condition = and(condition, equal("spec.type", typeFilter));
        }

        if (SORT_HOT.equals(sort)) {
            return listHotPosts(condition, page, size);
        }
        // 置顶优先 + 时间倒序；单查询单列表，分页天然对齐
        var orders = new ArrayList<>(PINNED_FIRST);
        if (SORT_LATEST.equals(sort)) {
            orders.add(Sort.Order.desc("spec.publishTime"));
        } else {
            orders.add(Sort.Order.desc("spec.lastActivityTime"));
            orders.add(Sort.Order.desc("spec.publishTime"));
        }
        return client.listBy(BbsPost.class, buildOptions(condition),
                        PageRequestImpl.of(page, size, Sort.by(orders)))
                .flatMap(result -> assembleVos(result.getItems(), false)
                        .map(vos -> new ListResult<>(result.getPage(), result.getSize(),
                                result.getTotal(), vos)));
    }

    /**
     * 热门排序：评论数为实时统计（不落库、无法走索引排序），故扫描最近
     * {@link #HOT_SCAN_LIMIT} 条候选在内存内按（置顶 → 评论数 → 发布时间）排名后分页；
     * 更早的旧帖不参与热门排名，total 亦以参与排名的条数为准。
     */
    private Mono<ListResult<BbsPostVo>> listHotPosts(Condition condition, int page, int size) {
        var scanSort = Sort.by(Sort.Order.desc("spec.publishTime"));
        return client.listBy(BbsPost.class, buildOptions(condition),
                        PageRequestImpl.of(1, HOT_SCAN_LIMIT, scanSort))
                .flatMap(scan -> fetchCommentCounts(scan.getItems()).flatMap(counts -> {
                    var ranked = rankHot(scan.getItems(), counts);
                    int from = Math.min((page - 1) * size, ranked.size());
                    int to = Math.min(from + size, ranked.size());
                    return assembleVos(ranked.subList(from, to), false)
                            .map(vos -> new ListResult<>(page, size, ranked.size(), vos));
                }));
    }

    /** 热门排名：置顶优先（权重次之），再按评论数与发布时间倒序。 */
    private static List<BbsPost> rankHot(List<BbsPost> posts, Map<String, Long> counts) {
        Comparator<BbsPost> cmp = Comparator
                .comparing((BbsPost p) -> Boolean.TRUE.equals(p.getSpec().getPinned()))
                .thenComparing(p -> p.getSpec().getPinPriority() == null
                        ? 0 : p.getSpec().getPinPriority())
                .thenComparing(p -> counts.getOrDefault(p.getMetadata().getName(), 0L))
                .thenComparing(p -> p.getSpec().getPublishTime(),
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        return posts.stream().sorted(cmp.reversed()).toList();
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

    /** 启用中的分类全集（分类量小，整取组树）。 */
    private Mono<List<BbsCategory>> loadEnabledCategories() {
        var options = ListOptions.builder().fieldQuery(equal("spec.enabled", true)).build();
        return client.listAll(BbsCategory.class, options, CATEGORY_SORT).collectList();
    }

    /**
     * 解析分类上下文（name 优先，其次 slug；找不到按首页处理）。
     * 两级封顶防御：parentName 指向的分类自身还有父（脏数据）时不认此父子关系。
     */
    private static CategoryContext resolveContext(List<BbsCategory> all, String name,
            String slug) {
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
            return CategoryContext.HOME;
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
     * 未选分类的帖子只在首页出现。
     */
    private static Condition visibleCondition(CategoryContext ctx) {
        var published = equal("spec.phase", BbsPost.Phase.PUBLISHED.name());
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
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                        equal("spec.type", BbsPost.PostType.ANNOUNCEMENT.name())))
                .build();
        var sort = Sort.by(
                Sort.Order.desc("spec.pinPriority"),
                Sort.Order.desc("spec.publishTime"));
        return listVos(options, 1, Math.max(1, limit), sort)
                .flatMapMany(result -> Flux.fromIterable(result.getItems()));
    }

    /** 最新已发布内容（含公告，按发布时间倒序，不做置顶提权）——全站 RSS 等时间线场景用。 */
    public Flux<BbsPostVo> listLatestPublished(int size) {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.phase", BbsPost.Phase.PUBLISHED.name()))
                .build();
        var sort = Sort.by(Sort.Order.desc("spec.publishTime"));
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
            if (ctx.isHome()) {
                return Flux.empty();
            }
            var names = new ArrayList<String>();
            names.add(ctx.selfName());
            ctx.children().forEach(c -> names.add(c.getMetadata().getName()));
            var options = ListOptions.builder()
                    .fieldQuery(and(
                            equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                            in("spec.categoryName", names)))
                    .build();
            return listVos(options, 1, Math.max(1, size),
                    Sort.by(Sort.Order.desc("spec.publishTime")))
                    .flatMapMany(result -> Flux.fromIterable(result.getItems()));
        });
    }

    /** 某作者的已发布内容分页（含公告，发布时间倒序）——作者页用。 */
    public Mono<ListResult<BbsPostVo>> listPublicByOwner(String owner, int page, int size) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
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

    /** 公开详情：按 slug 取已发布帖子（含正文）。 */
    public Mono<BbsPostVo> getPublishedBySlug(String slug) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.slug", slug),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name())))
                .build();
        return client.listAll(BbsPost.class, options, Sort.unsorted())
                .next()
                .flatMap(this::assembleDetail);
    }

    /** 按 metadata.name 装配详情 VO（含正文，不限状态——Console 编辑用）。 */
    public Mono<BbsPostVo> assembleDetail(BbsPost post) {
        return assembleVos(List.of(post), true).map(list -> list.get(0));
    }

    /** 已发布帖子总数（含公告）。 */
    public Mono<Long> countPublished() {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.phase", BbsPost.Phase.PUBLISHED.name()))
                .build();
        return client.countBy(BbsPost.class, options);
    }

    /** 某作者的已发布内容数（含公告）——统计贡献用，口径与作者页 {@link #listPublicByOwner} 一致。 */
    public Mono<Long> countPublishedByOwner(String owner) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
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

    /** 分类 VO 全量装配：计数 + 组树 + 树序平铺。 */
    private Mono<List<CategoryVo>> categoryVosWithCount(boolean onlyEnabled) {
        var builder = ListOptions.builder();
        if (onlyEnabled) {
            builder.fieldQuery(equal("spec.enabled", true));
        }
        return client.listAll(BbsCategory.class, builder.build(), CATEGORY_SORT)
                .collectList()
                .flatMap(cats -> Flux.fromIterable(cats)
                        .flatMap(cat -> countPublishedInCategory(cat.getMetadata().getName())
                                .map(count -> Map.entry(cat.getMetadata().getName(), count)))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                        .map(counts -> assembleCategoryVos(cats, counts)));
    }

    private Mono<Long> countPublishedInCategory(String categoryName) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.categoryName", categoryName),
                        equal("spec.phase", BbsPost.Phase.PUBLISHED.name())))
                .build();
        return client.countBy(BbsPost.class, options);
    }

    /** 平铺 VO 列表按树序组装：一级在前、其子紧随；聚合含子分类的帖子合计。 */
    private static List<CategoryVo> assembleCategoryVos(List<BbsCategory> cats,
            Map<String, Long> counts) {
        var dict = cats.stream().collect(Collectors.toMap(
                c -> c.getMetadata().getName(), Function.identity(), (a, b) -> a));
        var vos = cats.stream().map(cat -> {
            var vo = toCategoryVo(cat, dict);
            var count = counts.getOrDefault(cat.getMetadata().getName(), 0L);
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
                    vo.setCover(parent.getSpec().getCover());
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
        if (condition != null) {
            builder.fieldQuery(condition);
        }
        return builder.build();
    }

    private Mono<ListResult<BbsPostVo>> listVos(ListOptions options, int page, int size,
            Sort sort) {
        return client.listBy(BbsPost.class, options, PageRequestImpl.of(page, size, sort))
                .flatMap(result -> assembleVos(result.getItems(), false)
                        .map(vos -> new ListResult<>(
                                result.getPage(), result.getSize(), result.getTotal(), vos)));
    }

    /** 批量装配：一次性载入分类（全量，供父摘要 / 封面继承）与作者字典，内联展示属性。 */
    private Mono<List<BbsPostVo>> assembleVos(List<BbsPost> posts, boolean withContent) {
        if (posts.isEmpty()) {
            return Mono.just(List.of());
        }
        Set<String> userNames = new HashSet<>();
        for (BbsPost post : posts) {
            addIfNotBlank(userNames, post.getSpec().getOwner());
        }
        var categoriesMono = fetchCategoryDict();
        var usersMono = fetchMapByNames(User.class, userNames);
        var commentCountsMono = fetchCommentCounts(posts);
        return Mono.zip(categoriesMono, usersMono, commentCountsMono)
                .map(tuple -> posts.stream()
                        .map(post -> buildVo(post,
                                tuple.getT1(), tuple.getT2(), tuple.getT3(), withContent))
                        .toList());
    }

    /** 全量分类字典（含停用——帖子所属分类停用后徽章仍可渲染）。 */
    private Mono<Map<String, BbsCategory>> fetchCategoryDict() {
        return client.listAll(BbsCategory.class, ListOptions.builder().build(), Sort.unsorted())
                .collectMap(c -> c.getMetadata().getName());
    }

    /** 批量统计各帖公开可见的评论数（已通过且未隐藏；楼中楼回复不计入）。 */
    private Mono<Map<String, Long>> fetchCommentCounts(List<BbsPost> posts) {
        return Flux.fromIterable(posts)
                .flatMap(post -> {
                    var name = post.getMetadata().getName();
                    var ref = new Ref();
                    ref.setGroup(POST_GVK.group());
                    ref.setKind(POST_GVK.kind());
                    ref.setName(name);
                    var options = ListOptions.builder()
                            .fieldQuery(and(
                                    equal("spec.subjectRef", Comment.toSubjectRefKey(ref)),
                                    equal("spec.approved", true),
                                    equal("spec.hidden", false)))
                            .build();
                    return client.countBy(Comment.class, options)
                            .map(count -> Map.entry(name, count));
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private BbsPostVo buildVo(BbsPost post, Map<String, BbsCategory> categories,
            Map<String, User> users, Map<String, Long> commentCounts, boolean withContent) {
        var spec = post.getSpec();
        var category = spec.getCategoryName() == null
                ? null : categories.get(spec.getCategoryName());
        return BbsPostVo.builder()
                .name(post.getMetadata().getName())
                .title(spec.getTitle())
                .slug(spec.getSlug())
                .type(spec.getType() == null
                        ? BbsPost.PostType.POST.name() : spec.getType().name())
                .phase(spec.getPhase() == null
                        ? BbsPost.Phase.DRAFT.name() : spec.getPhase().name())
                .pinned(Boolean.TRUE.equals(spec.getPinned()))
                .pinPriority(spec.getPinPriority() == null ? 0 : spec.getPinPriority())
                .allowComment(!Boolean.FALSE.equals(spec.getAllowComment()))
                .locked(Boolean.TRUE.equals(spec.getLocked()))
                .solved(Boolean.TRUE.equals(spec.getSolved()))
                .rejectReason(spec.getRejectReason())
                .commentCount(commentCounts.getOrDefault(post.getMetadata().getName(), 0L))
                .excerpt(spec.getExcerpt())
                .content(withContent ? spec.getContent() : null)
                .permalink("/bbs/post/" + spec.getSlug())
                .category(category == null ? null : toCategoryVo(category, categories))
                .owner(spec.getOwner() == null
                        ? null : OwnerVo.from(spec.getOwner(), users.get(spec.getOwner())))
                .publishTime(spec.getPublishTime())
                .lastActivityTime(spec.getLastActivityTime() != null
                        ? spec.getLastActivityTime() : spec.getPublishTime())
                .lastEditTime(spec.getLastEditTime())
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
}
