package com.timxs.bbs.query;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.vo.BbsPostVo;
import com.timxs.bbs.vo.CategoryVo;
import com.timxs.bbs.vo.OwnerVo;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import run.halo.app.extension.Ref;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Condition;

/**
 * 查询与 VO 批量装配（Console / UC / 公开 API / Finder 四端共用）。
 *
 * <p>装配原则：对一页条目先收集全部关联资源名（分类 / 作者），每类资源仅发一次
 * {@code in("metadata.name", names)} 批量查询建 Map，再逐条内联——避免 N+1。</p>
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
        if (StringUtils.isNotBlank(type)) {
            condition = append(condition, equal("spec.type", type));
        }
        if (StringUtils.isNotBlank(phase)) {
            condition = append(condition, equal("spec.phase", phase));
        }
        var sortOrder = CONSOLE_SORTS.getOrDefault(
                java.util.Objects.toString(sort, ""),
                CONSOLE_SORTS.get("creationTimestamp,desc"));
        return listVos(buildOptions(condition), page, size, sortOrder);
    }

    /** UC「我的帖子」列表（关键词 / 状态 / 分类筛选）。 */
    public Mono<ListResult<BbsPostVo>> listMine(String owner, int page, int size,
            String keyword, String phase, String categoryName) {
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
        return listVos(buildOptions(condition),
                page, size, Sort.by(Sort.Order.desc("metadata.creationTimestamp")));
    }

    /** 公开列表：仅已发布普通帖子，置顶优先，支持分类（name 或 slug）与关键词。 */
    public Mono<ListResult<BbsPostVo>> listPublicPosts(int page, int size,
            String categoryName, String categorySlug, String keyword) {
        Mono<String> categoryMono = StringUtils.isNotBlank(categoryName)
                ? Mono.just(categoryName)
                : resolveCategoryNameBySlug(categorySlug);
        return categoryMono
                .defaultIfEmpty("")
                .flatMap(catName -> {
                    var condition = and(
                            equal("spec.phase", BbsPost.Phase.PUBLISHED.name()),
                            equal("spec.type", BbsPost.PostType.POST.name()));
                    if (StringUtils.isNotBlank(catName)) {
                        condition = append(condition, equal("spec.categoryName", catName));
                    }
                    if (StringUtils.isNotBlank(keyword)) {
                        condition = append(condition, contains("spec.title", keyword));
                    }
                    var sort = Sort.by(
                            Sort.Order.desc("spec.pinned"),
                            Sort.Order.desc("spec.pinPriority"),
                            Sort.Order.desc("spec.publishTime"));
                    return listVos(buildOptions(condition), page, size, sort);
                });
    }

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

    /** 最新已发布内容（含公告，按发布时间倒序，不做置顶提权）——RSS 等时间线场景用。 */
    public Flux<BbsPostVo> listLatestPublished(int size) {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.phase", BbsPost.Phase.PUBLISHED.name()))
                .build();
        var sort = Sort.by(Sort.Order.desc("spec.publishTime"));
        return listVos(options, 1, Math.max(1, size), sort)
                .flatMapMany(result -> Flux.fromIterable(result.getItems()));
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

    // ---------------- 分类 ----------------

    /** 分类列表（含已发布帖子数），priority 升序。onlyEnabled=true 供前台。 */
    public Flux<CategoryVo> listCategories(boolean onlyEnabled) {
        var builder = ListOptions.builder();
        if (onlyEnabled) {
            builder.fieldQuery(equal("spec.enabled", true));
        }
        var sort = Sort.by(
                Sort.Order.asc("spec.priority"),
                Sort.Order.asc("metadata.creationTimestamp"));
        return client.listAll(BbsCategory.class, builder.build(), sort)
                .concatMap(category -> {
                    var vo = CategoryVo.from(category);
                    var countOptions = ListOptions.builder()
                            .fieldQuery(and(
                                    equal("spec.categoryName", category.getMetadata().getName()),
                                    equal("spec.phase", BbsPost.Phase.PUBLISHED.name())))
                            .build();
                    return client.countBy(BbsPost.class, countOptions)
                            .map(count -> {
                                vo.setPostCount(count);
                                return vo;
                            });
                });
    }

    /** 按 slug 取启用中的分类。 */
    public Mono<CategoryVo> getCategoryBySlug(String slug) {
        var options = ListOptions.builder()
                .fieldQuery(and(equal("spec.slug", slug), equal("spec.enabled", true)))
                .build();
        return client.listAll(BbsCategory.class, options, Sort.unsorted())
                .next()
                .map(CategoryVo::from);
    }

    private Mono<String> resolveCategoryNameBySlug(String slug) {
        if (StringUtils.isBlank(slug)) {
            return Mono.empty();
        }
        return getCategoryBySlug(slug).map(CategoryVo::getName);
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

    /** 批量装配：一次性载入分类与作者字典，内联展示属性。 */
    private Mono<List<BbsPostVo>> assembleVos(List<BbsPost> posts, boolean withContent) {
        if (posts.isEmpty()) {
            return Mono.just(List.of());
        }
        Set<String> categoryNames = new HashSet<>();
        Set<String> userNames = new HashSet<>();
        for (BbsPost post : posts) {
            addIfNotBlank(categoryNames, post.getSpec().getCategoryName());
            addIfNotBlank(userNames, post.getSpec().getOwner());
        }
        var categoriesMono = fetchMapByNames(BbsCategory.class, categoryNames);
        var usersMono = fetchMapByNames(User.class, userNames);
        var commentCountsMono = fetchCommentCounts(posts);
        return Mono.zip(categoriesMono, usersMono, commentCountsMono)
                .map(tuple -> posts.stream()
                        .map(post -> buildVo(post,
                                tuple.getT1(), tuple.getT2(), tuple.getT3(), withContent))
                        .toList());
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
                .rejectReason(spec.getRejectReason())
                .commentCount(commentCounts.getOrDefault(post.getMetadata().getName(), 0L))
                .excerpt(spec.getExcerpt())
                .content(withContent ? spec.getContent() : null)
                .permalink("/bbs/post/" + spec.getSlug())
                .category(category == null ? null : CategoryVo.from(category))
                .owner(spec.getOwner() == null
                        ? null : OwnerVo.from(spec.getOwner(), users.get(spec.getOwner())))
                .publishTime(spec.getPublishTime())
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
