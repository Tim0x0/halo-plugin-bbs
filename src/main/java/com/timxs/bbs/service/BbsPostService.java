package com.timxs.bbs.service;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsModerationRecord;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.reconciler.BbsPostReconciler;
import com.timxs.bbs.util.BbsExcerpts;
import com.timxs.bbs.util.ReactiveOptimisticUpdates;
import com.timxs.bbs.vo.BbsContentVo;
import com.timxs.bbs.vo.BbsSnapshotDto;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 帖子核心业务：创建 / 更新 / 发布 / 置顶 / 删除。
 *
 * <p>统一处理 Snapshot 生命周期、正文净化（XSS）、slug 唯一性、自动摘要、审核审计与
 * owner 越权校验；端点层只做路由与参数解析。</p>
 *
 * @author Tim0x0
 */
@Component
@Slf4j
public class BbsPostService {

    private static final String UNTITLED = "未命名";

    /** 兜底标题家族：未命名 / 未命名 2 / 未命名 3……（空格 + 数字是防重顺延的后缀）。 */
    private static final Pattern UNTITLED_PATTERN =
            Pattern.compile("^" + UNTITLED + "(?: (\\d+))?$");

    /** 写回乐观锁重试次数（评论调和与帖子调和会同时写同一条 BbsPost）。 */
    private static final int RETRY_TIMES = 5;

    private final ReactiveExtensionClient client;
    private final BbsSettings settings;
    private final BbsModerationScope moderationScope;
    private final BbsPostContentService contentService;
    private final BbsModerationRecordService moderationRecordService;
    private final BbsModerationNotificationService moderationNotificationService;

    public BbsPostService(ReactiveExtensionClient client, BbsSettings settings,
            BbsModerationScope moderationScope, BbsPostContentService contentService,
            BbsModerationRecordService moderationRecordService,
            BbsModerationNotificationService moderationNotificationService) {
        this.client = client;
        this.settings = settings;
        this.moderationScope = moderationScope;
        this.contentService = contentService;
        this.moderationRecordService = moderationRecordService;
        this.moderationNotificationService = moderationNotificationService;
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
        requireOwner(owner);
        // 未命名防重必须在填充 spec 之前完成：标题与由标题派生的别名都依赖解析结果
        return resolveUntitledDefaults(request, owner)
                .then(contentPolicy())
                .flatMap(policy -> {
            validateRequest(request, managed, policy, null);
            var post = new BbsPost();
            var metadata = new Metadata();
            metadata.setGenerateName("bbs-post-");
            metadata.setAnnotations(new HashMap<>(Map.of(
                    BbsPostContentService.INITIALIZING_ANNO, "true")));
            metadata.setFinalizers(new HashSet<>(Set.of(BbsPostReconciler.FINALIZER)));
            post.setMetadata(metadata);
            var spec = post.getSpec();
            spec.setOwner(owner);
            applyRequest(spec, request, managed, null);
            // BbsPost 必须先存在，核心 Snapshot 才能用 subjectRef 指向它。初始化完成前
            // 暂存一份已净化正文，进程若在两次创建之间中断，调和器仍能幂等恢复；
            // initialize 成功会立即清空该暂存，不形成长期双数据源。
            spec.setContent(HtmlSanitizer.clean(request.getContent()));
            // 发布必须带分类；草稿可暂缺（对齐 UC 建稿与官方编辑器「保存即保存」，
            // 分类在发布前补齐即可）。无分类草稿的管辖校验落在下方
            // requireScopeOn——covers(null) 仅全站主体通过，分区版主建稿必须先选板块
            return (publish
                            ? requireCategory(spec.getCategoryName())
                            : requireCategoryIfPresent(spec.getCategoryName()))
                    // 管理端发帖同样要过管辖：否则分区版主能往任何板块投放内容，
                    // 还能直接发布 + 置顶，而发完自己又管不了（不在管辖内）。
                    // 用户侧发帖（managed=false）不受此限——往公开分类发帖是正常行为
                    .then(managed
                            ? requireScopeOn(spec.getCategoryName(), "无权在该分类下发帖")
                            : Mono.empty())
                    .then(resolveSubmitPhase(managed, publish))
                    .doOnNext(phase -> {
                        spec.setPhase(phase);
                        if (phase == BbsPost.Phase.PUBLISHED) {
                            spec.setPublishTime(Instant.now());
                            spec.setLastActivityTime(spec.getPublishTime());
                            moderationRecordService.enqueue(post,
                                    BbsModerationRecord.Action.PUBLISHED, owner, null,
                                    BbsPost.Phase.DRAFT.name(),
                                    BbsPost.Phase.PUBLISHED.name(), null);
                        }
                    })
                    .then(Mono.defer(() -> resolveSlugForCreate(spec.getSlug())))
                    .doOnNext(spec::setSlug)
                    .then(Mono.defer(() -> client.create(post)))
                    .flatMap(created -> contentService.initialize(created,
                                    request.getContent(), owner,
                                    created.getSpec().getPhase() == BbsPost.Phase.PUBLISHED)
                            .onErrorResume(error -> client.delete(created)
                                    .onErrorResume(ignored -> Mono.empty())
                                    .then(Mono.error(error))))
                    .flatMap(this::subscribeModerationNotifications)
                    .flatMap(this::flushModerationRecords);
        });
    }

    /**
     * UC 新建服务端草稿。
     *
     * <p>对齐 Halo 文章编辑器的 createMyPost 语义：首次保存只创建 DRAFT，不触发审核或发布；
     * 标题为空时用“未命名”兜底，分类允许稍后在提交前补齐。</p>
     */
    public Mono<BbsPost> createOwnedDraft(PostRequest request, String owner) {
        requireOwner(owner);
        applyDraftDefaults(request);
        // 同管理端 create：兜底标题落库前先防重顺延
        return resolveUntitledDefaults(request, owner)
                .then(contentPolicy())
                .flatMap(policy -> {
            validateDraftRequest(request, policy, null);

            var post = new BbsPost();
            var metadata = new Metadata();
            metadata.setGenerateName("bbs-post-");
            metadata.setAnnotations(new HashMap<>(Map.of(
                    BbsPostContentService.INITIALIZING_ANNO, "true")));
            metadata.setFinalizers(new HashSet<>(Set.of(BbsPostReconciler.FINALIZER)));
            post.setMetadata(metadata);

            var spec = post.getSpec();
            spec.setOwner(owner);
            applyRequest(spec, request, false, null);
            // 同管理端 create：初始化中断的正文暂存，调和器消费后清空。
            spec.setContent(HtmlSanitizer.clean(request.getContent()));
            spec.setPhase(BbsPost.Phase.DRAFT);

            return requireCategoryIfPresent(spec.getCategoryName())
                    .then(Mono.defer(() -> client.create(post)))
                    .flatMap(created -> contentService.initialize(created,
                                    request.getContent(), owner, false)
                            .onErrorResume(error -> client.delete(created)
                                    .onErrorResume(ignored -> Mono.empty())
                                    .then(Mono.error(error))))
                    .flatMap(this::subscribeModerationNotifications);
        });
    }

    /**
     * UC 普通保存：只更新工作稿，不提交审核或发布。
     *
     * <p>普通草稿原地更新 head；PUBLISHED 写工作稿而不动 release。若版本已经提交或
     * 被驳回，则把新修改退回 DRAFT——原审核版本由 release 指针天然保留，快照链不参与
     * 审核留痕。</p>
     */
    public Mono<BbsPost> saveOwned(String name, PostRequest request, String owner) {
        return contentPolicy().flatMap(policy -> updateWithRetry(name, post -> {
            checkOwner(post, owner);
            requireOwnedWritable(post);
            applyDraftDefaults(request);
            var spec = post.getSpec();
            var existingType = workingType(spec);
            validateDraftRequest(request, policy, existingType);

            if (spec.getPhase() == BbsPost.Phase.PUBLISHED) {
                // 对齐官方：无需重审且无待审核稿时设置保存即生效；
                // 否则走工作稿流，标题不 bypass 审核
                var pendingDraft = spec.getDraft() != null
                        && spec.getDraft().getPhase() == BbsPost.Phase.PENDING;
                if (!pendingDraft && !(policy.required() && policy.editNeedsReview())) {
                    return savePublishedImmediate(post, request, false, existingType,
                            name, owner);
                }
                var draft = applyDraftRequest(spec, request, false, existingType);
                var submitted = draft.getPhase() == BbsPost.Phase.PENDING;
                var before = HeadState.of(post);
                var categoryMono = submitted
                        ? requireCategory(draft.getCategoryName())
                        : requireCategoryIfPresent(draft.getCategoryName());
                var slugMono = submitted
                        ? resolveSlug(draft.getSlug(), name)
                        : Mono.just(draft.getSlug());
                return categoryMono
                        .then(slugMono)
                        .doOnNext(draft::setSlug)
                        .then(Mono.defer(() -> contentService.prepareHead(
                                post, request.getContent(), owner)))
                        .doOnNext(ignored -> {
                            if (before.changedIn(ignored)) {
                                draft.setLastEditTime(Instant.now());
                                // 被驳回的修改稿一经修改即转入「等待修改后重提」，
                                // 驳回原因随之失效。状态不动（WordPress 式：
                                // 保存只更新内容，审核状态只由提交 / 审核改变）
                                draft.setRejectReason(null);
                            }
                        });
            }

            applyRequest(spec, request, false, existingType);
            // 未发布内容本身就是工作稿，不应残留第二层 draft（兼容旁路写入的脏数据）。
            spec.setDraft(null);
            var before = HeadState.of(post);

            // DRAFT / REJECTED 的别名只是候选值，不占公开链接；PENDING 已进入发布流程，
            // 无论是否改过 slug 都重新校验，防止草稿阶段的冲突被带入待审核。
            var slugMono = spec.getPhase() == BbsPost.Phase.PENDING
                    ? resolveSlug(spec.getSlug(), name)
                    : Mono.just(spec.getSlug());
            var categoryMono = spec.getPhase() == BbsPost.Phase.PENDING
                    ? requireCategory(spec.getCategoryName())
                    : requireCategoryIfPresent(spec.getCategoryName());
            return categoryMono
                    .then(slugMono)
                    .doOnNext(spec::setSlug)
                    .then(Mono.defer(() -> contentService.prepareHead(
                            post, request.getContent(), owner)))
                    .doOnNext(ignored -> {
                        if (before.changedIn(ignored)) {
                            spec.setLastEditTime(Instant.now());
                            // 驳回帖修改后原因失效；保持已驳回状态等作者重新提交
                            spec.setRejectReason(null);
                        }
                    });
        }).flatMap(this::subscribeModerationNotifications)
                .flatMap(this::flushModerationRecords));
    }

    /**
     * UC 显式提交：校验完整发布条件，并按审核策略提交或发布。
     *
     * <p>已发布帖需重审时只把 {@code spec.draft.phase} 置为 PENDING，帖子本体继续
     * PUBLISHED，前台仍读取旧发布副本；无需重审时直接提升工作稿并清空 draft。</p>
     */
    public Mono<BbsPost> submitOwned(String name, PostRequest request, String owner) {
        applyDraftDefaults(request);
        return contentPolicy().flatMap(policy -> updateWithRetry(name, post -> {
                    checkOwner(post, owner);
                    requireOwnedWritable(post);

                    var spec = post.getSpec();
                    var existingType = workingType(spec);
                    validateRequest(request, false, policy, existingType);

                    if (spec.getPhase() == BbsPost.Phase.PUBLISHED) {
                        // 留痕来源状态：已有工作稿取其状态；首次修改稿以帖子主状态
                        // （已发布）为来源——「已发布 → 待审核」正是修改稿送审的语义
                        var oldDraftPhase = spec.getDraft() != null
                                && spec.getDraft().getPhase() != null
                                ? spec.getDraft().getPhase() : BbsPost.Phase.PUBLISHED;
                        var draft = applyDraftRequest(spec, request, false, existingType);
                        var before = HeadState.of(post);
                        return requireCategory(draft.getCategoryName())
                                .then(resolveSlug(draft.getSlug(), name))
                                .doOnNext(draft::setSlug)
                                .then(Mono.defer(() -> contentService.prepareHead(
                                        post, request.getContent(), owner)))
                                .doOnNext(ignored -> {
                                    if (before.changedIn(ignored)) {
                                        draft.setLastEditTime(Instant.now());
                                    }
                                    draft.setRejectReason(null);
                                    boolean pending = policy.required()
                                            && policy.editNeedsReview();
                                    if (pending) {
                                        draft.setPhase(BbsPost.Phase.PENDING);
                                    } else {
                                        promoteDraft(spec);
                                        markPublished(spec);
                                        spec.setReleaseSnapshot(spec.getHeadSnapshot());
                                    }
                                    moderationRecordService.enqueue(ignored,
                                            pending
                                                    ? BbsModerationRecord.Action.SUBMITTED
                                                    : BbsModerationRecord.Action.PUBLISHED,
                                            // 附言只随 SUBMITTED 进审核记录，与驳回原因对称
                                            owner,
                                            pending ? request.getSubmitNote() : null,
                                            oldDraftPhase.name(),
                                            pending ? BbsPost.Phase.PENDING.name()
                                                    : BbsPost.Phase.PUBLISHED.name());
                                });
                    }

                    // 留痕来源状态：提交前的主状态（草稿 / 已驳回重新提交 / 待审核重提）
                    var fromPhase = spec.getPhase();
                    applyRequest(spec, request, false, existingType);
                    spec.setDraft(null);
                    var before = HeadState.of(post);
                    return requireCategory(spec.getCategoryName())
                            .then(resolveSlug(spec.getSlug(), name))
                            .doOnNext(spec::setSlug)
                            .then(Mono.defer(() -> contentService.prepareHead(
                                    post, request.getContent(), owner)))
                            .doOnNext(ignored -> {
                                if (before.changedIn(ignored)) {
                                    spec.setLastEditTime(Instant.now());
                                }
                                spec.setRejectReason(null);
                                boolean pending = policy.required();
                                if (pending) {
                                    spec.setPhase(BbsPost.Phase.PENDING);
                                } else {
                                    markPublished(spec);
                                    spec.setReleaseSnapshot(spec.getHeadSnapshot());
                                }
                                moderationRecordService.enqueue(ignored,
                                        pending
                                                ? BbsModerationRecord.Action.SUBMITTED
                                                : BbsModerationRecord.Action.PUBLISHED,
                                        owner,
                                        pending ? request.getSubmitNote() : null,
                                        fromPhase.name(),
                                        pending ? BbsPost.Phase.PENDING.name()
                                                : BbsPost.Phase.PUBLISHED.name());
                            });
                }))
                .flatMap(this::subscribeModerationNotifications)
                .flatMap(this::flushModerationRecords);
    }

    /** Console 更新帖子；UC 保存统一走 {@link #saveOwned}。 */
    public Mono<BbsPost> updateManaged(String name, PostRequest request) {
        return currentUsername().flatMap(actor -> contentPolicy().flatMap(policy ->
                updateWithRetry(name, post -> requireUpdateScope(post, request)
                        .flatMap(ignored -> {
                            var spec = post.getSpec();
                            var existingType = workingType(spec);
                            validateRequest(request, true, policy, existingType);

                            if (spec.getPhase() == BbsPost.Phase.PUBLISHED) {
                                // 管理端天然免审：无待审核稿时设置保存即生效；
                                // 有待审核稿（作者送审中）仍走工作稿流，不越权绕过审核
                                var pendingDraft = spec.getDraft() != null
                                        && spec.getDraft().getPhase() == BbsPost.Phase.PENDING;
                                if (!pendingDraft) {
                                    return savePublishedImmediate(post, request, true,
                                            existingType, name, actor);
                                }
                                var draft = applyDraftRequest(spec, request, true,
                                        existingType);
                                var before = HeadState.of(post);
                                var slugMono = draft.getPhase() == BbsPost.Phase.PENDING
                                        ? resolveSlug(draft.getSlug(), name)
                                        : Mono.just(draft.getSlug());
                                return requireCategory(draft.getCategoryName())
                                        .then(slugMono)
                                        .doOnNext(draft::setSlug)
                                        .then(Mono.defer(() -> contentService.prepareHead(post,
                                                request.getContent(), actor)))
                                        .doOnNext(updated -> {
                                            if (before.changedIn(updated)) {
                                                draft.setLastEditTime(Instant.now());
                                                draft.setRejectReason(null);
                                            }
                                        });
                            }

                            var oldSlug = spec.getSlug();
                            applyRequest(spec, request, true, existingType);
                            spec.setDraft(null);
                            var before = HeadState.of(post);
                            var slugMono = !Objects.equals(oldSlug, spec.getSlug())
                                    ? resolveSlug(spec.getSlug(), name)
                                    : Mono.just(spec.getSlug());
                            // 未发布内容分类可暂缺（发布 / 提交时强制）；待审核重提除外，
                            // 进入发布流程的帖子必须带着分类走
                            var categoryMono = spec.getPhase() == BbsPost.Phase.PENDING
                                    ? requireCategory(spec.getCategoryName())
                                    : requireCategoryIfPresent(spec.getCategoryName());
                            return categoryMono
                                    .then(slugMono)
                                    .doOnNext(spec::setSlug)
                                    .then(Mono.defer(() -> contentService.prepareHead(post,
                                            request.getContent(), actor)))
                                    .doOnNext(updated -> {
                                        if (before.changedIn(updated)) {
                                            spec.setLastEditTime(Instant.now());
                                            spec.setRejectReason(null);
                                        }
                                    });
                        }))
                        .flatMap(this::flushModerationRecords)));
    }

    /**
     * 对齐官方：已发布帖免重审编辑时设置保存即生效。
     *
     * <p>元数据（标题 / 别名 / 类型 / 分类 / 摘要）直写 {@code spec}，前台立即读到；
     * 正文只更新 {@code headSnapshot}，前台继续读 {@code releaseSnapshot} 直到显式
     * 发布——与官方「spec 即生效、快照需发布」模型一致。已存在的工作稿（非待审核）
     * 同步更新元数据，避免编辑器 draft??spec 读到旧值，phase / 时间戳保持原样。
     * 调用方负责免审判定：Console 天然免审，UC 按「编辑已发布是否重新审核」；
     * 待审核稿一律继续走草稿流（保存不改审核状态，WordPress 式）。</p>
     */
    private Mono<BbsPost> savePublishedImmediate(BbsPost post, PostRequest request,
            boolean managed, BbsPost.PostType existingType, String name, String actor) {
        var spec = post.getSpec();
        var oldSlug = spec.getSlug();
        applyRequest(spec, request, managed, existingType);
        if (spec.getDraft() != null) {
            applyDraftRequest(spec, request, managed, existingType);
        }
        var before = HeadState.of(post);
        // 别名立即公开，唯一性必须当场校验（草稿阶段才允许暂重名）
        var slugMono = !Objects.equals(oldSlug, spec.getSlug())
                ? resolveSlug(spec.getSlug(), name)
                : Mono.just(spec.getSlug());
        return requireCategory(spec.getCategoryName())
                .then(slugMono)
                .doOnNext(spec::setSlug)
                .then(Mono.defer(() -> contentService.prepareHead(
                        post, request.getContent(), actor)))
                .doOnNext(updated -> {
                    stampEditTime(post, before);
                    if (before.changedIn(updated) && spec.getDraft() != null) {
                        // WordPress 式：被驳回的修改稿一经修改，驳回原因随之失效
                        spec.getDraft().setRejectReason(null);
                    }
                });
    }

    /**
     * 编辑场景的管辖校验：源分类与目标分类都必须在管辖范围内。
     *
     * <p>只校验一边都有缺口——只看源，版主能把帖子甩进不归自己管的区；只看目标，
     * 能把别人区的帖子拉进自己区。对齐 Discourse 的
     * {@code can_move_topic_to_category?} 校验目标分类的思路，并补上源侧。</p>
     */
    private Mono<BbsPost> requireUpdateScope(BbsPost post, PostRequest request) {
        return currentUsername()
                .flatMap(moderationScope::resolve)
                .flatMap(scope -> {
                    var from = post.getSpec().getCategoryName();
                    if (!scope.covers(from)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "无权管理该分类下的帖子"));
                    }
                    var to = request.getCategoryName();
                    if (StringUtils.isNotBlank(to) && !Objects.equals(to, from)
                            && !scope.covers(to)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "无权把帖子移动到该分类"));
                    }
                    return Mono.just(post);
                });
    }

    /** Console：取消提交（仅待审核；版主可代作者撤回）。 */
    public Mono<BbsPost> withdrawInScope(String name) {
        return currentUsername().flatMap(actor -> updateWithRetry(name,
                        post -> requireScope(post)
                                .flatMap(scoped -> withdrawSubmission(scoped, actor)))
                .flatMap(this::flushModerationRecords));
    }

    /** UC：作者撤回自己的提交（仅待审核；锁定 / 回收站帖由归属校验拦截）。 */
    public Mono<BbsPost> withdrawOwned(String name, String owner) {
        return updateWithRetry(name, post -> {
                    checkOwner(post, owner);
                    requireOwnedWritable(post);
                    return withdrawSubmission(post, owner);
                })
                .flatMap(this::flushModerationRecords);
    }

    /**
     * 取消提交公共逻辑：未发布的待审核帖退回草稿；已发布帖的待审核修改稿退回
     * 草稿态（前台发布版不受影响）；其余状态 400。留撤回审计，快照绑定当时
     * 的 head 工作版本。撤回是显式动作，保存不触发。
     */
    private Mono<BbsPost> withdrawSubmission(BbsPost post, String actor) {
        var spec = post.getSpec();
        if (spec.getPhase() == BbsPost.Phase.PENDING) {
            spec.setPhase(BbsPost.Phase.DRAFT);
            spec.setRejectReason(null);
            moderationRecordService.enqueue(post,
                    BbsModerationRecord.Action.SUBMISSION_WITHDRAWN, actor,
                    "撤回提交", BbsPost.Phase.PENDING.name(),
                    BbsPost.Phase.DRAFT.name(), spec.getHeadSnapshot());
            return Mono.just(post);
        }
        var draft = spec.getDraft();
        if (spec.getPhase() == BbsPost.Phase.PUBLISHED && draft != null
                && draft.getPhase() == BbsPost.Phase.PENDING) {
            draft.setPhase(BbsPost.Phase.DRAFT);
            draft.setRejectReason(null);
            moderationRecordService.enqueue(post,
                    BbsModerationRecord.Action.SUBMISSION_WITHDRAWN, actor,
                    "撤回提交", BbsPost.Phase.PENDING.name(),
                    BbsPost.Phase.DRAFT.name(), spec.getHeadSnapshot());
            return Mono.just(post);
        }
        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "该帖子没有待审核的提交"));
    }

    /**
     * 发布 / 审核通过。
     *
     * <p>存在工作稿时先完整提升工作稿，再发布；不存在时发布当前未发布内容。
     * 提升之前校验工作稿的目标分类和别名，避免已发布版本掩盖候选版本冲突。</p>
     */
    public Mono<BbsPost> publish(String name) {
        return publish(name, BbsModerationRecord.Action.PUBLISHED);
    }

    /** 审核通过与普通发布共用状态逻辑，但审计动作必须区分。 */
    public Mono<BbsPost> approve(String name) {
        return publish(name, BbsModerationRecord.Action.APPROVED);
    }

    private Mono<BbsPost> publish(String name, BbsModerationRecord.Action action) {
        return currentUsername().flatMap(actor -> updateWithRetry(name, post -> requireScope(post)
                        .flatMap(scoped -> requireDraftScopeIfPresent(scoped).thenReturn(scoped))
                        .flatMap(scoped -> {
                            var categoryName = workingCategoryName(scoped.getSpec());
                            var slug = workingSlug(scoped.getSpec());
                            if (StringUtils.isBlank(slug)) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "请填写别名"));
                            }
                            return requireCategory(categoryName)
                                    .then(slugTaken(slug, name))
                                    .flatMap(taken -> {
                                        if (Boolean.TRUE.equals(taken)) {
                                            return Mono.error(new ResponseStatusException(
                                                    HttpStatus.BAD_REQUEST,
                                                    "别名「" + slug + "」已被占用，请更换"));
                                        }
                                        if (action == BbsModerationRecord.Action.APPROVED) {
                                            var current = scoped.getSpec();
                                            // 待审核或已驳回均可通过（驳回后被推翻直接放行）：
                                            // 未发布帖看主状态，已发布帖看工作稿状态
                                            boolean reviewable = current.getPhase()
                                                    == BbsPost.Phase.PENDING
                                                    || current.getPhase()
                                                    == BbsPost.Phase.REJECTED
                                                    || (current.getPhase()
                                                    == BbsPost.Phase.PUBLISHED
                                                    && current.getDraft() != null
                                                    && (current.getDraft().getPhase()
                                                    == BbsPost.Phase.PENDING
                                                    || current.getDraft().getPhase()
                                                    == BbsPost.Phase.REJECTED));
                                            if (!reviewable) {
                                                return Mono.error(new ResponseStatusException(
                                                        HttpStatus.BAD_REQUEST,
                                                        "该帖子没有待审核或被驳回的版本"));
                                            }
                                        }
                                        return contentService.ensureInitialized(scoped, actor)
                                                .doOnNext(initialized -> {
                                                    var spec = initialized.getSpec();
                                                    // 留痕来源状态：提升前的工作稿状态；
                                                    // 无工作稿时为帖子主状态（草稿/待审核）
                                                    var fromPhase = (spec.getDraft() != null
                                                            && spec.getDraft().getPhase() != null
                                                            ? spec.getDraft().getPhase()
                                                            : spec.getPhase()).name();
                                                    if (spec.getDraft() != null) {
                                                        promoteDraft(spec);
                                                    }
                                                    markPublished(spec);
                                                    spec.setDeleted(false);
                                                    spec.setReleaseSnapshot(
                                                            spec.getHeadSnapshot());
                                                    moderationRecordService.enqueue(initialized,
                                                            action, actor, null, fromPhase,
                                                            BbsPost.Phase.PUBLISHED.name());
                                                });
                                    });
                        }))
                .flatMap(published -> action == BbsModerationRecord.Action.APPROVED
                        ? notifyApproved(published, actor)
                        : Mono.just(published))
                .flatMap(this::flushModerationRecords));
    }

    /**
     * 取消发布，回到未发布（保留原发布时间供再次发布沿用）。存在工作稿时先提升到
     * 当前非公开内容，避免撤稿操作把作者最新工作稿遗留在只适用于 PUBLISHED 的字段中。
     */
    public Mono<BbsPost> unpublish(String name) {
        return currentUsername().flatMap(actor -> updateWithRetry(name, post -> requireScope(post)
                        .flatMap(scoped -> requireDraftScopeIfPresent(scoped).thenReturn(scoped))
                        .flatMap(scoped -> contentService.ensureInitialized(scoped, actor))
                        .doOnNext(scoped -> {
                            var spec = scoped.getSpec();
                            if (spec.getDraft() != null) {
                                promoteDraft(spec);
                            }
                            spec.setPhase(BbsPost.Phase.DRAFT);
                            spec.setRejectReason(null);
                            moderationRecordService.enqueue(scoped,
                                    BbsModerationRecord.Action.UNPUBLISHED, actor, null,
                                    BbsPost.Phase.PUBLISHED.name(),
                                    BbsPost.Phase.DRAFT.name(), spec.getReleaseSnapshot());
                        }))
                .flatMap(this::flushModerationRecords));
    }

    /** 审核驳回（可附原因，展示给作者）。 */
    public Mono<BbsPost> reject(String name, String reason) {
        return currentUsername().flatMap(actor -> updateWithRetry(name, post -> requireScope(post)
                        .flatMap(scoped -> requireDraftScopeIfPresent(scoped).thenReturn(scoped))
                        .flatMap(scoped -> contentService.ensureInitialized(scoped, actor))
                        .map(initialized -> {
                            var spec = initialized.getSpec();
                            var rejectReason = StringUtils.trimToNull(reason);
                            if (spec.getPhase() == BbsPost.Phase.PUBLISHED) {
                                var draft = spec.getDraft();
                                if (draft == null
                                        || draft.getPhase() != BbsPost.Phase.PENDING) {
                                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                            "该帖子没有待审核的修改稿");
                                }
                                draft.setPhase(BbsPost.Phase.REJECTED);
                                draft.setRejectReason(rejectReason);
                            } else {
                                if (spec.getPhase() != BbsPost.Phase.PENDING) {
                                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                            "该帖子不是待审核状态");
                                }
                                spec.setPhase(BbsPost.Phase.REJECTED);
                                spec.setRejectReason(rejectReason);
                            }
                            moderationRecordService.enqueue(initialized,
                                    BbsModerationRecord.Action.REJECTED, actor, reason,
                                    BbsPost.Phase.PENDING.name(),
                                    BbsPost.Phase.REJECTED.name());
                            return initialized;
                        }))
                .flatMap(rejected -> notifyRejected(rejected, actor, reason))
                .flatMap(this::flushModerationRecords));
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
     * 缺省：标题 100 字、审核关、编辑重审开。分类必选（产品规则，不设开关），
     * 但强制点在发布 / 提交链路——草稿阶段可暂缺。
     */
    private Mono<BbsSettings.Content> contentPolicy() {
        return settings.content();
    }

    /**
     * 校验标题与类型约束。
     *
     * <p>分类不在此处强制：草稿阶段可暂缺（对齐官方「保存即保存」），
     * 由发布 / 提交链路上的 {@link #requireCategory} 在真正对外前强制。</p>
     *
     * @param existingType 编辑时帖子原类型（用户侧编辑公告时类型保持不变）
     */
    private void validateRequest(PostRequest request, boolean managed, BbsSettings.Content policy,
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
        resolveType(request, managed, existingType);
    }

    /** 草稿保存校验：标题可由服务端兜底，分类可为空；若已选择分类则仍校验其存在性。 */
    private void validateDraftRequest(PostRequest request, BbsSettings.Content policy,
            BbsPost.PostType existingType) {
        var title = StringUtils.trimToEmpty(request.getTitle());
        int max = policy.titleMaxOrDefault();
        if (title.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "标题不能超过 " + max + " 个字符");
        }
        resolveType(request, false, existingType);
    }

    /** Halo 编辑器在首次建稿前同样会把空标题补成“未命名”。 */
    private static void applyDraftDefaults(PostRequest request) {
        if (StringUtils.isBlank(request.getTitle())) {
            request.setTitle(UNTITLED);
        }
    }

    /**
     * 兜底标题防重：创建时「未命名」（含 未命名 N）与作者既有帖子标题冲突，就顺延
     * 编号取下一个可用值——未命名、未命名 2、未命名 3……别名随同一编号一起顺延。
     *
     * <p>官方编辑器新建不做防重，空标题草稿会堆出一片无法区分的「未命名」；对齐
     * 文件系统新建文件的重名约定，在后面累加数字。只在创建两条路径生效：改名是
     * 用户显式行为，更新路径不干预标题。冲突集合按作者取、含回收站中的帖子——
     * 否则恢复已删草稿后编号又会撞车。</p>
     *
     * <p>别名必须在这里一并顺延：别名是前端按「防重前」的标题生成的（两篇空标题
     * 草稿都带着同一个 {@code wei-ming-ming} 进来），若只改标题，第二篇发布时会撞
     * 别名被 400 拦下。服务端不知道前端的音译算法（拼音/随机策略在前端），故不做
     * 重新生成，只把请求里带来的别名追加同一编号——既保住前端已选的风格，又保证
     * 未命名家族的别名彼此可区分。别名为空时不处理：{@link #applyRequest} 会从
     * 顺延后的新标题派生。别名唯一性的最终防线仍是发布链路的 {@link #resolveSlug}，
     * 草稿阶段允许短暂重名（不占公开链接）。</p>
     */
    private Mono<Void> resolveUntitledDefaults(PostRequest request, String owner) {
        var title = StringUtils.trimToEmpty(request.getTitle());
        if (!UNTITLED_PATTERN.matcher(title).matches()) {
            return Mono.empty();
        }
        var ownerOptions = ListOptions.builder()
                .fieldQuery(equal("spec.owner", owner))
                .build();
        return client.listAll(BbsPost.class, ownerOptions,
                        org.springframework.data.domain.Sort.unsorted())
                .filter(existing -> existing.getMetadata().getDeletionTimestamp() == null)
                .map(existing -> StringUtils.trimToEmpty(existing.getSpec().getTitle()))
                .filter(existing -> UNTITLED_PATTERN.matcher(existing).matches())
                .collectList()
                .doOnNext(taken -> {
                    if (!taken.contains(title)) {
                        return;
                    }
                    var used = new HashSet<>(taken);
                    int n = 2;
                    while (used.contains(UNTITLED + " " + n)) {
                        n++;
                    }
                    request.setTitle(UNTITLED + " " + n);
                    var slug = StringUtils.trimToNull(request.getSlug());
                    if (slug != null) {
                        request.setSlug(slug + "-" + n);
                    }
                })
                .then();
    }

    /** 分类存在性：PUT / 创建共用；不存在一律 400。 */
    private Mono<Void> requireCategory(String categoryName) {
        if (StringUtils.isBlank(categoryName)) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "请选择分类"));
        }
        return client.fetch(BbsCategory.class, categoryName)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "分类不存在")))
                .flatMap(category -> ExtensionUtil.isDeleted(category)
                        ? Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "分类正在删除"))
                        : Mono.empty())
                .then();
    }

    /** 草稿分类可暂缺；一旦带值，就必须引用真实分类。 */
    private Mono<Void> requireCategoryIfPresent(String categoryName) {
        return StringUtils.isBlank(categoryName)
                ? Mono.empty()
                : requireCategory(categoryName);
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
        return mutateOwned(name, owner, post -> {
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

    /** 管理端移入回收站：显式走版主管辖。 */
    public Mono<BbsPost> recycleInScope(String name) {
        return mutate(name, post -> post.getSpec().setDeleted(true));
    }

    /** UC 移入回收站：归属校验必选，锁定帖按封存语义禁止作者删除。 */
    public Mono<BbsPost> recycleOwned(String name, String owner) {
        return mutateOwned(name, owner, post -> {
            if (Boolean.TRUE.equals(post.getSpec().getLocked())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "帖子已被锁定，无法删除");
            }
            post.getSpec().setDeleted(true);
        });
    }

    /** 从回收站恢复（管理端）。别名若已被占用则拒绝，避免两篇已发布帖撞 slug。 */
    public Mono<BbsPost> restore(String name) {
        return updateWithRetry(name, post -> requireScope(post).flatMap(scoped -> {
            if (scoped.getSpec().getPhase() == BbsPost.Phase.DRAFT
                    || scoped.getSpec().getPhase() == BbsPost.Phase.REJECTED) {
                scoped.getSpec().setDeleted(false);
                return Mono.just(scoped);
            }
            var slug = scoped.getSpec().getSlug();
            return slugTaken(slug, name).flatMap(taken -> {
                if (Boolean.TRUE.equals(taken)) {
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "别名「" + slug + "」已被占用，请先修改后再恢复"));
                }
                scoped.getSpec().setDeleted(false);
                return Mono.just(scoped);
            });
        }));
    }

    /** 管理端彻底删除（不可恢复）：显式走版主管辖。 */
    public Mono<Void> deleteInScope(String name) {
        return getRequired(name)
                // 管理端路径同样过管辖（当前彻底删除仅完整管理角色可调，此处是纵深防御）
                .flatMap(this::requireScope)
                .flatMap(client::delete)
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

    // ---------------- 内容与快照 ----------------
    //
    // 端点形态与语义对齐官方 PostEndpoint 的 {name}/content、{name}/snapshot、
    // {name}/revert-content 子路径。可见性完全跟随帖子：Console 侧过了版主管辖校验、
    // UC 侧过了归属校验，该帖的全部快照就都可读——不按快照自身的历史分类二次过滤。

    /** Console：head 正文（编辑器载入）。 */
    public Mono<BbsContentVo> getHeadContentInScope(String name) {
        return getRequiredInScope(name)
                .flatMap(post -> contentService.getContentDetail(post, null));
    }

    /** UC：head 正文（编辑器载入）。 */
    public Mono<BbsContentVo> getHeadContentOwned(String name, String owner) {
        return getOwned(name, owner)
                .flatMap(post -> contentService.getContentDetail(post, null));
    }

    /** Console：前台发布版正文。 */
    public Mono<BbsContentVo> getReleaseContentInScope(String name) {
        return getRequiredInScope(name).flatMap(contentService::getReleaseContentDetail);
    }

    /** UC：前台发布版正文。 */
    public Mono<BbsContentVo> getReleaseContentOwned(String name, String owner) {
        return getOwned(name, owner).flatMap(contentService::getReleaseContentDetail);
    }

    /** Console：指定快照重建正文（缺省 head）。 */
    public Mono<BbsContentVo> getContentInScope(String name, String snapshotName) {
        return getRequiredInScope(name)
                .flatMap(post -> contentService.getContentDetail(post, snapshotName));
    }

    /** UC：指定快照重建正文（缺省 head）。 */
    public Mono<BbsContentVo> getContentOwned(String name, String snapshotName, String owner) {
        return getOwned(name, owner)
                .flatMap(post -> contentService.getContentDetail(post, snapshotName));
    }

    /** Console：完整快照历史。 */
    public Flux<BbsSnapshotDto> listSnapshotsInScope(String name) {
        return getRequiredInScope(name).flatMapMany(contentService::listSnapshots);
    }

    /** UC：本人帖子的完整快照历史。 */
    public Flux<BbsSnapshotDto> listSnapshotsOwned(String name, String owner) {
        return getOwned(name, owner).flatMapMany(contentService::listSnapshots);
    }

    /**
     * Console：只保存正文，不动任何元数据与状态。
     *
     * <p>{@code version} 与服务端当前 head 不一致时分叉出新快照而不是覆盖对方版本，
     * 与官方 {@code PUT /posts/{name}/content} 一致。</p>
     */
    public Mono<BbsPost> saveContentInScope(String name, ContentUpdateParam param) {
        return currentUsername().flatMap(actor -> updateWithRetry(name,
                post -> requireScope(post)
                        .flatMap(scoped -> {
                            var before = HeadState.of(scoped);
                            return contentService.prepareHead(scoped,
                                            param.resolvedContent(), actor, param.version())
                                    .doOnNext(updated -> stampEditTime(updated, before));
                        })));
    }

    /** UC：只保存正文，不动任何元数据与状态。 */
    public Mono<BbsPost> saveContentOwned(String name, ContentUpdateParam param, String owner) {
        return updateWithRetry(name, post -> {
            checkOwner(post, owner);
            requireOwnedWritable(post);
            var before = HeadState.of(post);
            return contentService.prepareHead(post, param.resolvedContent(), owner,
                            param.version())
                    .doOnNext(updated -> stampEditTime(updated, before));
        });
    }

    /**
     * 正文保存的编辑时间刷新：快照链真动了才算编辑。
     *
     * <p>已发布帖只记工作稿时间——「已编辑」跟着**发布版**走，{@code spec}
     * 的时间等工作稿被提升时由 {@link #promoteDraft} 同步。否则修改稿还在
     * 审核中、前台仍是已发布版本，列表与前台就会提前挂出「已编辑」。无草稿时
     * 补建一个（正文改动本身就构成未发布修改）。</p>
     *
     * <p>未发布帖记 {@code spec}：发布时间尚为 null，怎么记都不会误标。</p>
     */
    private static void stampEditTime(BbsPost post, HeadState before) {
        if (!before.changedIn(post)) {
            return;
        }
        var spec = post.getSpec();
        if (spec.getPhase() == BbsPost.Phase.PUBLISHED) {
            ensureDraft(spec).setLastEditTime(Instant.now());
        } else {
            spec.setLastEditTime(Instant.now());
        }
    }

    /**
     * 正文写入信号：{@code prepareHead} 真正动过快照链（分叉或原地更新）才刷新
     * 编辑时间——只改设置或无改动的保存不能把帖子误标成「已编辑」。
     */
    private record HeadState(String headName, Long headVersion) {

        static HeadState of(BbsPost post) {
            var status = post.getStatus();
            return new HeadState(post.getSpec().getHeadSnapshot(),
                    status == null ? null : status.getHeadSnapshotVersion());
        }

        boolean changedIn(BbsPost post) {
            var status = post.getStatus();
            return !Objects.equals(headName, post.getSpec().getHeadSnapshot())
                    || !Objects.equals(headVersion,
                            status == null ? null : status.getHeadSnapshotVersion());
        }
    }

    /** Console 恢复历史版本。 */
    public Mono<BbsPost> revertContentInScope(String name, String snapshotName) {
        return currentUsername().flatMap(actor -> contentPolicy().flatMap(policy ->
                updateWithRetry(name, post -> requireScope(post)
                        .flatMap(scoped -> contentService.prepareRestore(
                                scoped, snapshotName, actor))
                        .flatMap(reverted -> applyRevertPublishing(reverted, actor, policy)))
                        .flatMap(this::flushModerationRecords)));
    }

    /** UC 恢复历史版本：只能恢复本人且未锁定、未回收的帖子。 */
    public Mono<BbsPost> revertContentOwned(String name, String snapshotName, String owner) {
        return contentPolicy().flatMap(policy -> updateWithRetry(name, post -> {
                    checkOwner(post, owner);
                    requireOwnedWritable(post);
                    return contentService.prepareRestore(post, snapshotName, owner)
                            .flatMap(reverted -> applyRevertPublishing(reverted, owner, policy));
                })
                .flatMap(this::flushModerationRecords));
    }

    /**
     * 恢复后的发布分流（官方 revert 无条件重新发布，BBS 加一层审核分流）：
     *
     * <ul>
     *   <li>未发布帖：只换 head，不碰 release、不碰 phase——照搬官方会把草稿误发布；</li>
     *   <li>已发布帖 + 无需重审：{@code release = head}，旧正文立刻回到前台，等同官方行为；</li>
     *   <li>已发布帖 + 需重审：写 {@code draft.phase = PENDING}，帖子本体继续 PUBLISHED，
     *       前台仍是已发布版本，等管理员审核。</li>
     * </ul>
     *
     * <p>两条已发布分支都<b>不提升 draft</b>：恢复只作用于正文，作者尚未提交的标题 / 分类
     * 改动不该被一次「恢复正文」顺带发布出去（也就不必在这里做别名查重）。</p>
     */
    private Mono<BbsPost> applyRevertPublishing(BbsPost post, String actor,
            BbsSettings.Content policy) {
        var spec = post.getSpec();
        spec.setLastEditTime(Instant.now());
        if (spec.getPhase() != BbsPost.Phase.PUBLISHED) {
            return Mono.just(post);
        }
        if (policy.required() && policy.editNeedsReview()) {
            var draft = ensureDraft(spec);
            draft.setPhase(BbsPost.Phase.PENDING);
            draft.setRejectReason(null);
            draft.setLastEditTime(Instant.now());
            moderationRecordService.enqueue(post, BbsModerationRecord.Action.SUBMITTED,
                    actor, "恢复历史版本后提交审核", BbsPost.Phase.PUBLISHED.name(),
                    BbsPost.Phase.PENDING.name());
            return Mono.just(post);
        }
        markPublished(spec);
        spec.setReleaseSnapshot(spec.getHeadSnapshot());
        moderationRecordService.enqueue(post, BbsModerationRecord.Action.PUBLISHED,
                actor, "恢复历史版本并重新发布", null, BbsPost.Phase.PUBLISHED.name());
        return Mono.just(post);
    }

    /** Console 删除历史版本。 */
    public Mono<BbsPost> deleteContentInScope(String name, String snapshotName) {
        return getRequiredInScope(name)
                .flatMap(post -> contentService.deleteSnapshot(post, snapshotName));
    }

    /** UC 删除历史版本。 */
    public Mono<BbsPost> deleteContentOwned(String name, String snapshotName, String owner) {
        return getOwned(name, owner)
                .doOnNext(BbsPostService::requireOwnedWritable)
                .flatMap(post -> contentService.deleteSnapshot(post, snapshotName));
    }

    /**
     * Console 审核记录：跟随帖子管辖可见性。
     *
     * <p>旧实现按「可见快照名集合」反向过滤记录——那套依赖快照上的业务分类注解，随快照
     * 与审核解耦一并废除。记录本身归属帖子，帖子可管辖即可见。</p>
     */
    public Flux<BbsModerationRecord> listModerationRecordsInScope(String name) {
        return getRequiredInScope(name)
                .flatMapMany(post -> moderationRecordService.listByPost(
                        post.getMetadata().getName()));
    }

    public Flux<BbsModerationRecord> listModerationRecordsOwned(String name, String owner) {
        return getOwned(name, owner)
                .flatMapMany(post -> moderationRecordService.listByPost(
                        post.getMetadata().getName()));
    }

    private static void checkOwner(BbsPost post, String requiredOwner) {
        requireOwner(requiredOwner);
        if (!Objects.equals(post.getSpec().getOwner(), requiredOwner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作他人的帖子");
        }
    }

    /** 归属校验默认收紧：调用者缺失不能再被解释为“跳过校验”。 */
    private static void requireOwner(String owner) {
        if (StringUtils.isBlank(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少归属校验主体");
        }
    }

    /** UC 写入公共保护：锁定帖和回收站帖均不可继续修改。 */
    private static void requireOwnedWritable(BbsPost post) {
        if (Boolean.TRUE.equals(post.getSpec().getDeleted())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "帖子已在回收站，无法编辑");
        }
        if (Boolean.TRUE.equals(post.getSpec().getLocked())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "帖子已被锁定，无法编辑");
        }
    }

    /**
     * 管理端写入：先校验版主管辖范围，再改。
     *
     * <p>管理端的状态变更（发布 / 审核 / 置顶 / 锁定 / 回收站）全部收敛在这里，
     * 管辖校验只写一处，新增操作自动纳入，不会漏。用户中心路径走
     * {@link #mutateOwned}——那条线由归属校验把关，不受版主管辖限制。</p>
     */
    private Mono<BbsPost> mutate(String name, Consumer<BbsPost> mutation) {
        return updateWithRetry(name, post -> requireScope(post)
                .doOnNext(mutation)
                .thenReturn(post));
    }

    /** 用户中心写入：归属校验在公共入口强制执行，不套版主管辖。 */
    private Mono<BbsPost> mutateOwned(String name, String owner, Consumer<BbsPost> mutation) {
        requireOwner(owner);
        return updateWithRetry(name, post -> {
            checkOwner(post, owner);
            mutation.accept(post);
            return Mono.just(post);
        });
    }

    /**
     * 版主管辖校验：全站版主与管理角色直接通过，分区版主只能操作管辖分类树内的帖子。
     * 无分类归属的帖子（早期版本允许的存量公告）只有全站主体可管。
     */
    private Mono<BbsPost> requireScope(BbsPost post) {
        return requireScopeOn(post.getSpec().getCategoryName(), "无权管理该分类下的帖子")
                .thenReturn(post);
    }

    /**
     * 工作稿改了分类时，管理操作必须同时覆盖源分类与目标分类。
     * 只校验发布副本会让分区版主借“审核通过”把内容移进不归自己管理的板块。
     */
    private Mono<Void> requireDraftScopeIfPresent(BbsPost post) {
        var draft = post.getSpec().getDraft();
        if (draft == null
                || Objects.equals(post.getSpec().getCategoryName(), draft.getCategoryName())) {
            return Mono.empty();
        }
        return requireScopeOn(draft.getCategoryName(), "无权管理修改稿的目标分类");
    }

    /** 管辖校验的公共底座：分类不在管辖范围内即 403。 */
    private Mono<Void> requireScopeOn(String categoryName, String message) {
        return currentUsername()
                .flatMap(moderationScope::resolve)
                .flatMap(scope -> scope.covers(categoryName)
                        ? Mono.<Void>empty()
                        : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, message)));
    }

    /**
     * 取单篇帖子并校验管辖（Console 详情 / 编辑器载入用）。
     *
     * <p>列表已按管辖过滤，但详情接口是另一条路——不拦的话，分区版主直接调 API
     * 或访问编辑器 URL 就能读到别的板块<b>草稿与待审核</b>帖的正文（那些前台看不到），
     * 而且能打开编辑器却存不了（保存会被 403），体验也是错的。</p>
     */
    public Mono<BbsPost> getRequiredInScope(String name) {
        return getRequired(name).flatMap(this::requireScope);
    }

    /** 当前登录用户名；取不到时返回空串，由管辖校验判定为无权限。 */
    private static Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName)
                .defaultIfEmpty("");
    }

    /**
     * 乐观锁重试：必须从重新 fetch 开始（拿到新 version 再改），
     * 直接重放旧对象永远失败。对齐 {@code BbsCategoryService#updateWithRetry}。
     */
    private Mono<BbsPost> updateWithRetry(String name,
            Function<BbsPost, Mono<BbsPost>> mutation) {
        return Mono.defer(() -> getRequired(name)
                        .flatMap(mutation)
                        .flatMap(client::update))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_TIMES, Duration.ofMillis(50)));
    }

    /** 服务层尽力立即写审核记录；失败时保留 outbox，由调和器继续补偿。 */
    private Mono<BbsPost> flushModerationRecords(BbsPost post) {
        return moderationRecordService.flushPending(post)
                .onErrorResume(error -> {
                    log.warn("Moderation records for BbsPost {} will be retried by reconciler",
                            post.getMetadata().getName(), error);
                    return Mono.just(post);
                });
    }

    /** 作者订阅审核结果（幂等）；失败不挡主流程。 */
    private Mono<BbsPost> subscribeModerationNotifications(BbsPost post) {
        return moderationNotificationService.subscribe(post).thenReturn(post);
    }

    private Mono<BbsPost> notifyApproved(BbsPost post, String actor) {
        return moderationNotificationService.notifyApproved(post, actor).thenReturn(post);
    }

    private Mono<BbsPost> notifyRejected(BbsPost post, String actor, String reason) {
        return moderationNotificationService.notifyRejected(post, actor, reason).thenReturn(post);
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
        // 正文由 BbsPostContentService 写入 Halo Snapshot；BbsPost 只保存元数据与指针。
        applyExcerpt(spec, request);
        var slug = StringUtils.trimToNull(request.getSlug());
        spec.setSlug(slug != null ? slugify(slug) : slugify(spec.getTitle()));
        var type = resolveType(request, managed, existingType);
        spec.setType(type);
        // 改出问答类型时清掉已解决残留，避免改回问答后凭空「已解决」。
        // 放在服务层：PUT 与 CRUD patch 两条写入路径才会行为一致。
        if (type != BbsPost.PostType.QUESTION) {
            spec.setSolved(false);
        }
        if (managed) {
            applyManagedFields(spec, request);
        }
    }

    /**
     * 把请求写入已发布帖子的工作稿。首次写入时先从当前发布副本完整复制，确保
     * {@code content=null} 这类“只改设置”请求不会把未随请求提交的正文清空。
     *
     * <p>编辑时间不在这里盖：是否算「编辑」以正文是否真改动为准，由调用方在
     * {@code prepareHead} 之后按 {@link HeadState} 判定——只改设置的保存不算。</p>
     */
    private BbsPost.Draft applyDraftRequest(BbsPost.Spec spec, PostRequest request,
            boolean managed, BbsPost.PostType existingType) {
        var draft = ensureDraft(spec);
        draft.setTitle(StringUtils.trim(request.getTitle()));
        draft.setCategoryName(StringUtils.trimToNull(request.getCategoryName()));
        // draft 只保存工作稿元数据，正文由 spec.headSnapshot 指向。
        draft.setExcerpt(updateExcerpt(draft.getExcerpt(), request));
        var slug = StringUtils.trimToNull(request.getSlug());
        draft.setSlug(slug != null ? slugify(slug) : slugify(draft.getTitle()));
        draft.setType(resolveType(request, managed, existingType));
        if (draft.getPhase() == null || draft.getPhase() == BbsPost.Phase.PUBLISHED) {
            draft.setPhase(BbsPost.Phase.DRAFT);
        }
        if (managed) {
            // pinned / pinPriority 属于发布实体的管理属性，不是 UC 工作稿字段；
            // Console 保存时仍按原行为更新它们。
            applyManagedFields(spec, request);
        }
        spec.setDraft(draft);
        return draft;
    }

    /** 首次编辑已发布帖时，从 release 字段构造完整 head 工作稿。 */
    private static BbsPost.Draft ensureDraft(BbsPost.Spec spec) {
        if (spec.getDraft() != null) {
            return spec.getDraft();
        }
        var draft = new BbsPost.Draft();
        draft.setTitle(spec.getTitle());
        draft.setSlug(spec.getSlug());
        draft.setType(spec.getType() == null ? BbsPost.PostType.POST : spec.getType());
        draft.setCategoryName(spec.getCategoryName());
        draft.setExcerpt(copyExcerpt(spec.getExcerpt()));
        draft.setPhase(BbsPost.Phase.DRAFT);
        draft.setLastEditTime(spec.getLastEditTime());
        spec.setDraft(draft);
        return draft;
    }

    /**
     * 把 head 工作稿原子提升成 release，并清空 draft。调用方须先完成分类 / slug 校验。
     */
    private static void promoteDraft(BbsPost.Spec spec) {
        var draft = spec.getDraft();
        if (draft == null) {
            return;
        }
        spec.setTitle(draft.getTitle());
        spec.setSlug(draft.getSlug());
        spec.setType(draft.getType() == null ? BbsPost.PostType.POST : draft.getType());
        spec.setCategoryName(draft.getCategoryName());
        spec.setExcerpt(copyExcerpt(draft.getExcerpt()));
        // 工作稿没记到正文编辑时间（只改过设置的保存）就沿用现值，
        // 补 now 会让从未改过正文的帖子发布后被误标「已编辑」
        if (draft.getLastEditTime() != null) {
            spec.setLastEditTime(draft.getLastEditTime());
        }
        if (spec.getType() != BbsPost.PostType.QUESTION) {
            spec.setSolved(false);
        }
        spec.setDraft(null);
    }

    /** 发布状态公共收口：首次发布时间 / 活跃时间只补缺，不因修改发布而重置。 */
    private static void markPublished(BbsPost.Spec spec) {
        spec.setPhase(BbsPost.Phase.PUBLISHED);
        spec.setRejectReason(null);
        if (spec.getPublishTime() == null) {
            spec.setPublishTime(Instant.now());
        }
        if (spec.getLastActivityTime() == null) {
            spec.setLastActivityTime(spec.getPublishTime());
        }
    }

    private static BbsPost.PostType workingType(BbsPost.Spec spec) {
        var draft = spec.getDraft();
        return draft != null && draft.getType() != null ? draft.getType() : spec.getType();
    }

    private static String workingCategoryName(BbsPost.Spec spec) {
        return spec.getDraft() == null
                ? spec.getCategoryName() : spec.getDraft().getCategoryName();
    }

    private static String workingSlug(BbsPost.Spec spec) {
        return spec.getDraft() == null ? spec.getSlug() : spec.getDraft().getSlug();
    }

    private static void applyManagedFields(BbsPost.Spec spec, PostRequest request) {
        if (request.getPinned() != null) {
            spec.setPinned(request.getPinned());
        }
        if (request.getPinPriority() != null) {
            spec.setPinPriority(request.getPinPriority());
        }
    }

    /**
     * 摘要写入：{@code autoGenerate} 显式表达「是否自动」，不靠 excerpt 空值反推。
     *
     * <p>自动模式下 {@code raw} 存 null——展示文本由 {@link BbsExcerpts#resolve}
     * 实时截取正文，正文改则摘要跟随。</p>
     *
     * <p>请求未声明 {@code autoExcerpt} 时：若同时也没给摘要，按自动处理——
     * 覆盖新建场景的默认形态，不至于把帖子摘要清成空。已带摘要的则维持既有模式不动。</p>
     */
    private static void applyExcerpt(BbsPost.Spec spec, PostRequest request) {
        spec.setExcerpt(updateExcerpt(spec.getExcerpt(), request));
    }

    /** 摘要对象更新底座，release 与 draft 共用，避免两套语义漂移。 */
    private static BbsPost.Excerpt updateExcerpt(BbsPost.Excerpt current, PostRequest request) {
        var excerpt = current;
        if (excerpt == null) {
            excerpt = new BbsPost.Excerpt();
        }
        if (request.getAutoExcerpt() != null) {
            excerpt.setAutoGenerate(request.getAutoExcerpt());
        } else if (StringUtils.isBlank(request.getExcerpt())) {
            excerpt.setAutoGenerate(true);
        }
        excerpt.setRaw(!Boolean.FALSE.equals(excerpt.getAutoGenerate())
                ? null
                : StringUtils.trimToNull(HtmlSanitizer.plainExcerpt(
                        Objects.toString(request.getExcerpt(), ""), BbsExcerpts.MAX_LENGTH)));
        return excerpt;
    }

    private static BbsPost.Excerpt copyExcerpt(BbsPost.Excerpt source) {
        var copy = new BbsPost.Excerpt();
        if (source == null) {
            return copy;
        }
        copy.setAutoGenerate(source.getAutoGenerate());
        copy.setRaw(source.getRaw());
        return copy;
    }

    /** slug 归一：小写、空白转连字符，仅保留字母数字 / 中文 / 连字符。
     *  <p>幂等：对已存在的别名（含历史中文别名）重复 slugify 不改变其值，
     *  保证编辑已有帖子时别名稳定。别名转拼音由前端 transliteration 在提交前完成
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

    /**
     * 发布约束下的 slug 冲突处理：一律报错让调用方更换，<b>不做任何静默改写</b>。
     * 草稿可暂时重名，进入 PENDING / PUBLISHED 前必须走本方法。
     *
     * <p>帖子 slug 有意不设 unique 索引（草稿与回收站不占前台别名，见 {@code BbsPlugin}）；
     * 并发窗口下可能短暂撞车，公开路由按发布时间取确定的一条。</p>
     *
     * <p>创建期有两处改写例外，均属默认值延续而非本方法所约束的发布期冲突：
     * {@link #resolveUntitledDefaults} 的「未命名」防重顺延，以及
     * {@link #resolveSlugForCreate} 的撞名自动追加随机后缀。</p>
     */
    private Mono<String> resolveSlug(String slug, String excludeName) {
        return slugTaken(slug, excludeName).flatMap(taken -> {
            if (Boolean.TRUE.equals(taken)) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "别名「" + slug + "」已被占用，请更换"));
            }
            return Mono.just(slug);
        });
    }

    /**
     * 创建期 slug 冲突处理（对齐官方编辑器的创建路径）：撞名时不报错，在基础名后
     * 自动追加 8 位随机后缀再探测（{@code slug-3f8a1c2d} 形态）。官方文章编辑器新建
     * 文章撞别名时就是静默顺延 {@code slug-${randomUUID(8)}}，用户无感。
     *
     * <p>更新 / 发布 / 恢复路径不走这里——那些是用户显式指定别名的场景，冲突必须
     * 明确报错（见 {@link #resolveSlug}）。后缀随机，二次撞名概率可忽略，
     * 递归探测仅作防御。</p>
     */
    private Mono<String> resolveSlugForCreate(String slug) {
        return slugTaken(slug, null).flatMap(taken -> Boolean.TRUE.equals(taken)
                ? resolveSlugForCreate(slug + "-" + randomSuffix())
                : Mono.just(slug));
    }

    /** 8 位小写十六进制随机后缀（取 UUID 前 8 位，与既有 {@link #randomSlug} 同源）。 */
    private static String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 别名占用预检（对齐官方设置弹窗的 {@code slugUniqueValidation}）：供前端表单
     * 在保存前内联提示，与发布链路的 {@link #resolveSlug} 同一口径，
     * 避免用户到发布时才撞 400。
     *
     * <p>入参先走与落库一致的 {@link #slugify} 归一（小写、空白转连字符），
     * 保证校验的是最终会写进 {@code spec.slug} 的形态。</p>
     *
     * @param excludeName 排除自己（编辑既有帖子改别名时）
     */
    public Mono<Boolean> isSlugTaken(String slug, String excludeName) {
        var normalized = StringUtils.isBlank(slug) ? "" : slugify(slug);
        return slugTaken(normalized, excludeName);
    }

    /**
     * 别名是否已被别的发布中内容占用（首次发布候选、当前发布副本、已提交的修改稿；
     * 普通 DRAFT / REJECTED 与回收站不占公开链接）。
     */
    private Mono<Boolean> slugTaken(String slug, String excludeName) {
        if (StringUtils.isBlank(slug)) {
            return Mono.just(false);
        }
        var releaseOptions = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.slug", slug),
                        equal("spec.deleted", false)))
                .build();
        var submittedDraftOptions = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.draft.slug", slug),
                        equal("spec.draft.phase", BbsPost.Phase.PENDING.name()),
                        equal("spec.deleted", false)))
                .build();
        var releases = client.listAll(BbsPost.class, releaseOptions,
                        org.springframework.data.domain.Sort.unsorted())
                .filter(existing -> existing.getMetadata().getDeletionTimestamp() == null)
                .filter(existing -> existing.getSpec().getPhase() == BbsPost.Phase.PENDING
                        || existing.getSpec().getPhase() == BbsPost.Phase.PUBLISHED);
        var submittedDrafts = client.listAll(BbsPost.class, submittedDraftOptions,
                        org.springframework.data.domain.Sort.unsorted())
                .filter(existing -> existing.getMetadata().getDeletionTimestamp() == null);
        return Flux.concat(releases, submittedDrafts)
                .filter(existing -> excludeName == null
                        || !existing.getMetadata().getName().equals(excludeName))
                .hasElements();
    }
}
