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
        if (StringUtils.isBlank(request.getTitle())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        var post = new BbsPost();
        var metadata = new Metadata();
        metadata.setGenerateName("bbs-post-");
        post.setMetadata(metadata);
        var spec = post.getSpec();
        spec.setOwner(owner);
        applyRequest(spec, request, managed);
        return resolveSubmitPhase(managed, publish)
                .doOnNext(phase -> {
                    spec.setPhase(phase);
                    if (phase == BbsPost.Phase.PUBLISHED) {
                        spec.setPublishTime(Instant.now());
                    }
                })
                .then(uniqueSlug(spec.getSlug(), null))
                .doOnNext(spec::setSlug)
                .then(Mono.defer(() -> client.create(post)));
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
        return getRequired(name)
                .flatMap(post -> {
                    checkOwner(post, requiredOwner);
                    var spec = post.getSpec();
                    var oldSlug = spec.getSlug();
                    var wasPublished = spec.getPhase() == BbsPost.Phase.PUBLISHED;
                    applyRequest(spec, request, managed);
                    spec.setLastEditTime(Instant.now());
                    // 用户编辑：需审核时退回待审核；已发布帖在「编辑需重审」关闭时直接生效。
                    // 新发布与被驳回后的重新提交始终走审核（防“过审后改内容”绕过）。
                    var phaseMono = managed ? Mono.empty()
                            : moderation()
                                    .doOnNext(m -> {
                                        boolean needsReview = m.required()
                                                && (!wasPublished || m.editNeedsReview());
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
                                    })
                                    .then();
                    var slugMono = StringUtils.equals(oldSlug, spec.getSlug())
                            ? Mono.just(spec.getSlug())
                            : uniqueSlug(spec.getSlug(), name);
                    return phaseMono
                            .then(slugMono)
                            .doOnNext(spec::setSlug)
                            .then(Mono.defer(() -> client.update(post)));
                });
    }

    /** 发布 / 审核通过（首次发布时记录发布时间，清空驳回原因）。 */
    public Mono<BbsPost> publish(String name) {
        return mutate(name, post -> {
            post.getSpec().setPhase(BbsPost.Phase.PUBLISHED);
            post.getSpec().setRejectReason(null);
            if (post.getSpec().getPublishTime() == null) {
                post.getSpec().setPublishTime(Instant.now());
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
        return moderation()
                .map(m -> m.required() ? BbsPost.Phase.PENDING : BbsPost.Phase.PUBLISHED);
    }

    /** 读取审核设置（bbs-settings / moderation 组），缺省：审核关闭、编辑重审开启。 */
    private Mono<ModerationSetting> moderation() {
        return settingFetcher.fetch("moderation", ModerationSetting.class)
                .defaultIfEmpty(new ModerationSetting(false, true));
    }

    /** 审核设置组（bbs-settings / moderation）。 */
    public record ModerationSetting(Boolean requireReview, Boolean reviewOnEdit) {

        /** 是否开启发帖审核。 */
        boolean required() {
            return Boolean.TRUE.equals(requireReview);
        }

        /** 编辑已发布内容是否需重审（缺省开启，安全优先）。 */
        boolean editNeedsReview() {
            return !Boolean.FALSE.equals(reviewOnEdit);
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

    /**
     * 删除帖子。
     *
     * @param requiredOwner 非空时校验归属（UC 场景）
     */
    public Mono<Void> delete(String name, String requiredOwner) {
        return getRequired(name)
                .flatMap(post -> {
                    checkOwner(post, requiredOwner);
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

    /** 把请求体写入 spec：净化正文、兜底摘要；管理字段仅管理端可改。 */
    private void applyRequest(BbsPost.Spec spec, PostRequest request, boolean managed) {
        spec.setTitle(StringUtils.trim(request.getTitle()));
        spec.setCategoryName(StringUtils.trimToNull(request.getCategoryName()));
        spec.setContent(HtmlSanitizer.clean(Objects.toString(request.getContent(), "")));
        var excerpt = StringUtils.trimToNull(request.getExcerpt());
        spec.setExcerpt(excerpt != null
                ? excerpt
                : HtmlSanitizer.plainExcerpt(spec.getContent(), EXCERPT_LENGTH));
        var slug = StringUtils.trimToNull(request.getSlug());
        spec.setSlug(slug != null ? slugify(slug) : slugify(spec.getTitle()));
        if (managed) {
            if (request.getType() != null) {
                spec.setType(request.getType());
            }
            if (request.getPinned() != null) {
                spec.setPinned(request.getPinned());
            }
            if (request.getPinPriority() != null) {
                spec.setPinPriority(request.getPinPriority());
            }
        } else if (spec.getType() == null) {
            // 用户侧不可指定类型：新建默认普通帖子；编辑保留原类型
            // （避免管理员自己的公告经 UC 编辑被悄悄降级为普通帖子）
            spec.setType(BbsPost.PostType.POST);
        }
    }

    /** slug 归一：小写、空白转连字符，仅保留字母数字 / 中文 / 连字符。 */
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
