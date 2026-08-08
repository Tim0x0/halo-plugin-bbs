package com.timxs.bbs.service;

import static run.halo.app.extension.index.query.Queries.equal;

import com.timxs.bbs.extension.BbsPost;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 帖子核心业务：创建 / 更新 / 发布 / 置顶 / 删除。
 *
 * <p>统一在此处理正文净化（XSS）、slug 生成与唯一性、自动摘要与 owner 越权校验；
 * 端点层只做路由与参数解析。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsPostService {

    private static final int EXCERPT_LENGTH = 160;
    private static final int DEFAULT_TITLE_MAX = 100;

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;

    public BbsPostService(ReactiveExtensionClient client, ReactiveSettingFetcher settingFetcher) {
        this.client = client;
        this.settingFetcher = settingFetcher;
    }

    /**
     * 创建帖子。
     *
     * @param request 请求体
     * @param owner 归属用户名
     * @param managed 是否管理端（允许指定类型 / 置顶）
     * @param publish 是否直接发布
     */
    public Mono<BbsPost> create(PostRequest request, String owner, boolean managed,
            boolean publish) {
        return contentPolicy().flatMap(policy -> {
            validateRequest(request, managed, policy, null);
            var post = new BbsPost();
            var metadata = new Metadata();
            metadata.setGenerateName("bbs-post-");
            post.setMetadata(metadata);
            var spec = post.getSpec();
            spec.setOwner(owner);
            applyRequest(spec, request, managed, null);
            return resolveSubmitPhase(managed, publish)
                    .doOnNext(phase -> {
                        spec.setPhase(phase);
                        if (phase == BbsPost.Phase.PUBLISHED) {
                            spec.setPublishTime(Instant.now());
                            spec.setLastActivityTime(spec.getPublishTime());
                        }
                    })
                    .then(uniqueSlug(spec.getSlug(), null))
                    .doOnNext(spec::setSlug)
                    .then(Mono.defer(() -> client.create(post)));
        });
    }

    /**
     * 更新帖子。
     *
     * @param name 帖子 metadata.name
     * @param request 请求体
     * @param requiredOwner 非空时校验归属（UC 场景），越权返回 403
     * @param managed 是否管理端（允许改类型 / 置顶）
     */
    public Mono<BbsPost> update(String name, PostRequest request, String requiredOwner,
            boolean managed) {
        return Mono.zip(getRequired(name), contentPolicy())
                .flatMap(tuple -> {
                    var post = tuple.getT1();
                    var policy = tuple.getT2();
                    checkOwner(post, requiredOwner);
                    // 锁定帖封存：作者（用户侧）不可再编辑，管理端不受限
                    if (!managed && Boolean.TRUE.equals(post.getSpec().getLocked())) {
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "帖子已被锁定，无法编辑");
                    }
                    var existingType = post.getSpec().getType();
                    validateRequest(request, managed, policy, existingType);
                    var spec = post.getSpec();
                    var oldSlug = spec.getSlug();
                    var wasPublished = spec.getPhase() == BbsPost.Phase.PUBLISHED;
                    applyRequest(spec, request, managed, existingType);
                    spec.setLastEditTime(Instant.now());
                    // 用户编辑：需审核时退回待审核；已发布帖在「编辑需重审」关闭时直接生效。
                    // 新发布与被驳回后的重新提交始终走审核（防“过审后改内容”绕过）。
                    var phaseMono = managed ? Mono.<Void>empty()
                            : Mono.fromRunnable(() -> {
                                boolean needsReview = policy.required()
                                        && (!wasPublished || policy.editNeedsReview());
                                var phase = needsReview
                                        ? BbsPost.Phase.PENDING
                                        : BbsPost.Phase.PUBLISHED;
                                spec.setPhase(phase);
                                // 重新提交即进入新一轮流程，清掉上一轮的驳回原因
                                spec.setRejectReason(null);
                                if (phase == BbsPost.Phase.PUBLISHED
                                        && spec.getPublishTime() == null) {
                                    spec.setPublishTime(Instant.now());
                                }
                                if (phase == BbsPost.Phase.PUBLISHED
                                        && spec.getLastActivityTime() == null) {
                                    spec.setLastActivityTime(spec.getPublishTime());
                                }
                            });
                    var slugMono = StringUtils.equals(oldSlug, spec.getSlug())
                            ? Mono.just(spec.getSlug())
                            : uniqueSlug(spec.getSlug(), name);
                    return phaseMono
                            .then(slugMono)
                            .doOnNext(spec::setSlug)
                            .then(Mono.defer(() -> client.update(post)));
                });
    }

    /** 发布 / 审核通过（首次发布时记录发布时间与活跃时间，清空驳回原因）。 */
    public Mono<BbsPost> publish(String name) {
        return mutate(name, post -> {
            post.getSpec().setPhase(BbsPost.Phase.PUBLISHED);
            post.getSpec().setRejectReason(null);
            if (post.getSpec().getPublishTime() == null) {
                post.getSpec().setPublishTime(Instant.now());
            }
            if (post.getSpec().getLastActivityTime() == null) {
                post.getSpec().setLastActivityTime(post.getSpec().getPublishTime());
            }
        });
    }

    /** 撤销发布，回到草稿（保留原发布时间供再次发布沿用）。 */
    public Mono<BbsPost> unpublish(String name) {
        return mutate(name, post -> post.getSpec().setPhase(BbsPost.Phase.DRAFT));
    }

    /** 审核驳回（可附原因，展示给作者）。 */
    public Mono<BbsPost> reject(String name, String reason) {
        return mutate(name, post -> {
            post.getSpec().setPhase(BbsPost.Phase.REJECTED);
            post.getSpec().setRejectReason(StringUtils.trimToNull(reason));
        });
    }

    /** 提交时的目标状态：管理端按 publish 参数；用户按审核开关（开=待审核，关=直接发布）。 */
    private Mono<BbsPost.Phase> resolveSubmitPhase(boolean managed, boolean publish) {
        if (managed) {
            return Mono.just(publish ? BbsPost.Phase.PUBLISHED : BbsPost.Phase.DRAFT);
        }
        return contentPolicy()
                .map(m -> m.required() ? BbsPost.Phase.PENDING : BbsPost.Phase.PUBLISHED);
    }

    /**
     * 读取内容策略（bbs-settings / content）：标题上限 + 审核。
     * 缺省：标题 100 字、审核关、编辑重审开。普通帖分类始终必选（产品规则，不设开关）。
     */
    private Mono<ContentSetting> contentPolicy() {
        return settingFetcher.fetch("content", ContentSetting.class)
                .defaultIfEmpty(ContentSetting.empty());
    }

    /**
     * 校验标题与分类约束。
     *
     * @param existingType 编辑时帖子原类型（用户侧编辑公告时类型保持不变）
     */
    private void validateRequest(PostRequest request, boolean managed, ContentSetting policy,
            BbsPost.PostType existingType) {
        var title = StringUtils.trimToEmpty(request.getTitle());
        if (StringUtils.isBlank(title)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        int max = policy.titleMaxOrDefault();
        if (title.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "标题不能超过 " + max + " 个字符");
        }
        var type = resolveType(request, managed, existingType);
        // 公告可不选分类（不选时只在首页显示）；普通帖与问答帖必须选分类
        if (type != BbsPost.PostType.ANNOUNCEMENT
                && StringUtils.isBlank(request.getCategoryName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择分类");
        }
    }

    /**
     * 解析将落库的帖子类型（校验与写入共用，保证一致）：
     * <ul>
     *   <li>未传类型：编辑保留原类型，新建默认普通帖</li>
     *   <li>用户侧不可将帖子设为公告（403）；编辑公告时类型强制保持公告，
     *       防止管理员的公告经 UC 编辑被悄悄降级</li>
     *   <li>普通帖与问答帖可互改（作者选错类型可修正）</li>
     * </ul>
     */
    private static BbsPost.PostType resolveType(PostRequest request, boolean managed,
            BbsPost.PostType existingType) {
        if (!managed && existingType == BbsPost.PostType.ANNOUNCEMENT) {
            return BbsPost.PostType.ANNOUNCEMENT;
        }
        var requested = request.getType();
        if (requested == null) {
            return existingType != null ? existingType : BbsPost.PostType.POST;
        }
        if (!managed && requested == BbsPost.PostType.ANNOUNCEMENT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权发布公告");
        }
        return requested;
    }

    /**
     * 内容设置组（bbs-settings / content）。
     * 结构与 formSchema 的 {@code $formkit: group} 对齐：posting / review。
     * 同时保留旧版扁平字段；group 缺失时回退，避免升级后审核/标题上限静默失效。
     * 普通帖必选分类为固定规则，不进配置。
     */
    public record ContentSetting(
            Posting posting, Review review,
            // —— 旧版扁平字段（group 重构前）——
            Integer titleMaxLength, Boolean requireReview, Boolean reviewOnEdit) {

        static ContentSetting empty() {
            return new ContentSetting(null, null, null, null, null);
        }

        int titleMaxOrDefault() {
            Integer max = posting != null ? posting.titleMaxLength() : titleMaxLength;
            if (max == null) {
                return DEFAULT_TITLE_MAX;
            }
            return Math.min(200, Math.max(10, max));
        }

        /** 是否开启发帖审核。 */
        boolean required() {
            Boolean flag = review != null ? review.requireReview() : requireReview;
            return Boolean.TRUE.equals(flag);
        }

        /** 编辑已发布内容是否需重审（缺省开启，安全优先）。 */
        boolean editNeedsReview() {
            Boolean flag = review != null ? review.reviewOnEdit() : reviewOnEdit;
            // 显式 false 才关闭；null / 缺省均视为开启
            return !Boolean.FALSE.equals(flag);
        }

        public record Posting(Integer titleMaxLength) {
        }

        public record Review(Boolean requireReview, Boolean reviewOnEdit) {
        }
    }

    /** 置顶。 */
    public Mono<BbsPost> pin(String name) {
        return mutate(name, post -> post.getSpec().setPinned(true));
    }

    /** 取消置顶。 */
    public Mono<BbsPost> unpin(String name) {
        return mutate(name, post -> post.getSpec().setPinned(false));
    }

    /** 锁定（管理端）：封存帖子——禁评论、禁作者编辑与删除，前台显示锁定标识。 */
    public Mono<BbsPost> lock(String name) {
        return mutate(name, post -> post.getSpec().setLocked(true));
    }

    /** 解锁（管理端）。 */
    public Mono<BbsPost> unlock(String name) {
        return mutate(name, post -> post.getSpec().setLocked(false));
    }

    /** 标记 / 取消已解决（管理端）：仅问答帖可操作。 */
    public Mono<BbsPost> setSolved(String name, boolean solved) {
        return mutate(name, post -> {
            requireQuestion(post);
            post.getSpec().setSolved(solved);
        });
    }

    /** 标记 / 取消已解决（UC，发帖人操作）：越权 403；锁定帖不可操作。 */
    public Mono<BbsPost> setSolvedOwned(String name, String owner, boolean solved) {
        return mutate(name, post -> {
            checkOwner(post, owner);
            requireQuestion(post);
            if (Boolean.TRUE.equals(post.getSpec().getLocked())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "帖子已被锁定，无法操作");
            }
            post.getSpec().setSolved(solved);
        });
    }

    private static void requireQuestion(BbsPost post) {
        if (post.getSpec().getType() != BbsPost.PostType.QUESTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "仅问答帖可标记已解决");
        }
    }

    /**
     * 删除帖子。
     *
     * @param requiredOwner 非空时校验归属（UC 场景）；锁定帖封存语义下作者不可删除
     */
    public Mono<Void> delete(String name, String requiredOwner) {
        return getRequired(name)
                .flatMap(post -> {
                    checkOwner(post, requiredOwner);
                    if (requiredOwner != null
                            && Boolean.TRUE.equals(post.getSpec().getLocked())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "帖子已被锁定，无法删除"));
                    }
                    return client.delete(post);
                })
                .then();
    }

    /** 取单篇帖子，不存在时 404。 */
    public Mono<BbsPost> getRequired(String name) {
        return client.fetch(BbsPost.class, name)
                .switchIfEmpty(Mono.error(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "帖子不存在")));
    }

    /** 取归属于指定用户的帖子，越权 403。 */
    public Mono<BbsPost> getOwned(String name, String owner) {
        return getRequired(name)
                .doOnNext(post -> checkOwner(post, owner));
    }

    private void checkOwner(BbsPost post, String requiredOwner) {
        if (requiredOwner != null
                && !StringUtils.equals(post.getSpec().getOwner(), requiredOwner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作他人的帖子");
        }
    }

    private Mono<BbsPost> mutate(String name, java.util.function.Consumer<BbsPost> mutation) {
        return getRequired(name)
                .flatMap(post -> {
                    mutation.accept(post);
                    return client.update(post);
                });
    }

    /** 把请求体写入 spec：净化正文、兜底摘要；类型经 {@link #resolveType} 统一解析，
     *  管理专属字段（置顶）仅管理端可改。
     *
     *  @param existingType 编辑时的原类型；创建场景传 null
     */
    private void applyRequest(BbsPost.Spec spec, PostRequest request, boolean managed,
            BbsPost.PostType existingType) {
        spec.setTitle(StringUtils.trim(request.getTitle()));
        spec.setCategoryName(StringUtils.trimToNull(request.getCategoryName()));
        spec.setContent(HtmlSanitizer.clean(Objects.toString(request.getContent(), "")));
        var excerpt = StringUtils.trimToNull(request.getExcerpt());
        spec.setExcerpt(excerpt != null
                ? excerpt
                : HtmlSanitizer.plainExcerpt(spec.getContent(), EXCERPT_LENGTH));
        var slug = StringUtils.trimToNull(request.getSlug());
        spec.setSlug(slug != null ? slugify(slug) : slugify(spec.getTitle()));
        spec.setType(resolveType(request, managed, existingType));
        // 允许评论：作者与管理端均可设；null 表示不修改（编辑保留原值）
        if (request.getAllowComment() != null) {
            spec.setAllowComment(request.getAllowComment());
        }
        if (managed) {
            if (request.getPinned() != null) {
                spec.setPinned(request.getPinned());
            }
            if (request.getPinPriority() != null) {
                spec.setPinPriority(request.getPinPriority());
            }
        }
    }

    /** slug 归一：小写、空白转连字符，仅保留字母数字 / 中文 / 连字符。
     *  <p>幂等：对已存在的别名（含历史中文别名）重复 slugify 不改变其值，
     *  保证编辑老帖子时别名稳定。别名转拼音由前端 transliteration 在提交前完成
     *  （对标 Halo 官方 use-slugify）；此处仅做归一与留空兜底。 */
    private static String slugify(String input) {
        var slug = Objects.toString(input, "")
                .strip()
                .toLowerCase()
                .replaceAll("[\\s_]+", "-")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        return StringUtils.isBlank(slug) ? randomSlug() : slug;
    }

    private static String randomSlug() {
        return "post-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 确保 slug 唯一：被占用时追加随机后缀（唯一索引兜底并发冲突）。 */
    private Mono<String> uniqueSlug(String slug, String excludeName) {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.slug", slug))
                .build();
        return client.listAll(BbsPost.class, options,
                        org.springframework.data.domain.Sort.unsorted())
                .filter(existing -> excludeName == null
                        || !existing.getMetadata().getName().equals(excludeName))
                .hasElements()
                .map(taken -> Boolean.TRUE.equals(taken)
                        ? slug + "-" + UUID.randomUUID().toString().substring(0, 4)
                        : slug);
    }
}
