package com.timxs.bbs.reconciler;

import com.timxs.bbs.event.BbsPostChangedEvent;
import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.search.BbsPostDocumentsProvider;
import com.timxs.bbs.service.BbsModerationRecordService;
import com.timxs.bbs.service.BbsPostContentService;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.search.event.HaloDocumentAddRequestEvent;
import run.halo.app.search.event.HaloDocumentDeleteRequestEvent;

/**
 * 帖子调和器：把任何路径（服务层 / 直接 CRUD patch / 批量操作）产生的帖子
 * 变更同步进 Halo 搜索索引；删除时先移除索引再释放 finalizer。
 *
 * <p>同时维护所属分类的帖子数。<b>换分类时新旧两边都要重算</b>，而 spec 里只剩新分类，
 * 所以把上一次的分类记在 annotation 里，靠它算出差集——这是官方
 * {@code CategoryPostCountUpdater} 的做法。</p>
 *
 * <p>分类计数与搜索索引各自保存输入指纹。评论只会改变
 * {@code status.commentsCount}/{@code lastActivityTime}，不会命中这两类指纹，因而不再
 * 重算分类，也不再还原整份 release Snapshot。</p>
 *
 * <p>仅需 {@code @Component}，Halo 会自动注册并随插件启停（勿手动 start/stop）。</p>
 *
 * @author Tim0x0
 */
@Component
@Slf4j
public class BbsPostReconciler implements Reconciler<Reconciler.Request> {

    public static final String FINALIZER = "bbs.timxs.com/search-index";

    /** 上一次关联的分类，用于换分类时定位需要重算的旧分类。 */
    private static final String LAST_CATEGORY_ANNO = "bbs.timxs.com/last-category";
    /** 会影响分类总数/公开数的帖子状态指纹。 */
    static final String CATEGORY_COUNT_STATE_ANNO = "bbs.timxs.com/category-count-state";
    /** 会影响 Halo 搜索文档的发布态指纹。 */
    static final String SEARCH_INDEX_STATE_ANNO = "bbs.timxs.com/search-index-state";
    private static final Duration INITIALIZATION_GRACE = Duration.ofSeconds(30);

    private final ExtensionClient client;
    private final ApplicationEventPublisher eventPublisher;
    private final BbsCountService countService;
    private final BbsPostContentService contentService;
    private final BbsModerationRecordService moderationRecordService;

    public BbsPostReconciler(ExtensionClient client, ApplicationEventPublisher eventPublisher,
            BbsCountService countService, BbsPostContentService contentService,
            BbsModerationRecordService moderationRecordService) {
        this.client = client;
        this.eventPublisher = eventPublisher;
        this.countService = countService;
        this.contentService = contentService;
        this.moderationRecordService = moderationRecordService;
    }

    @Override
    public Result reconcile(Request request) {
        var found = client.fetch(BbsPost.class, request.name());
        if (found.isEmpty()) {
            return Result.doNotRetry();
        }
        var post = found.get();
        var metadata = post.getMetadata();
        var annotations = MetadataUtil.nullSafeAnnotations(post);
        var lastCategory = annotations.get(LAST_CATEGORY_ANNO);
        var currentCategory = post.getSpec().getCategoryName();

        if (ExtensionUtil.isDeleted(post)) {
            try {
                Mono.when(
                                contentService.deleteAll(post),
                                moderationRecordService.deleteAll(post))
                        .block(Duration.ofSeconds(15));
            } catch (RuntimeException error) {
                log.warn("Failed to clean snapshots or moderation records for BbsPost {}",
                        request.name(), error);
                return Result.requeue(Duration.ofSeconds(1));
            }
            eventPublisher.publishEvent(new HaloDocumentDeleteRequestEvent(this,
                    List.of(BbsPostDocumentsProvider.docId(metadata.getName()))));
            if (metadata.getFinalizers() != null
                    && metadata.getFinalizers().contains(FINALIZER)) {
                try {
                    OptimisticUpdates.update(client, BbsPost.class, request.name(), latest -> {
                        var finals = latest.getMetadata().getFinalizers();
                        if (finals != null) {
                            finals.remove(FINALIZER);
                        }
                    });
                } catch (OptimisticLockingFailureException e) {
                    return Result.requeue(Duration.ofMillis(200));
                }
            }
            countService.recalculateCategoryCounts(Arrays.asList(lastCategory, currentCategory));
            eventPublisher.publishEvent(new BbsPostChangedEvent(this));
            return Result.doNotRetry();
        }

        // 分类删除 finalizer 会主动清理帖子；这里再做最终一致性兜底，覆盖删除扫描后
        // 发生的直接 CRUD 写入，以及历史 Snapshot 恢复出的旧分类引用。
        if (hasInvalidCategoryReference(post)) {
            try {
                OptimisticUpdates.update(client, BbsPost.class, request.name(),
                        this::clearInvalidCategoryReferences);
                return Result.requeue(Duration.ofMillis(100));
            } catch (OptimisticLockingFailureException e) {
                return Result.requeue(Duration.ofMillis(200));
            }
        }

        boolean initializing = Boolean.parseBoolean(annotations.get(
                BbsPostContentService.INITIALIZING_ANNO));
        boolean missingPointers = StringUtils.isBlank(post.getSpec().getBaseSnapshot())
                || StringUtils.isBlank(post.getSpec().getHeadSnapshot());
        if (missingPointers) {
            var createdAt = metadata.getCreationTimestamp();
            if (initializing && createdAt != null
                    && Instant.now().isBefore(createdAt.plus(INITIALIZATION_GRACE))) {
                return Result.requeue(Duration.ofSeconds(1));
            }
            try {
                if (initializing) {
                    log.warn("Snapshot initialization for BbsPost {} exceeded grace period; "
                            + "starting idempotent recovery", request.name());
                }
                contentService.initializePending(request.name()).block(Duration.ofSeconds(15));
                return Result.requeue(Duration.ofMillis(100));
            } catch (RuntimeException error) {
                log.warn("Failed to initialize pending BbsPost {} content to Snapshot",
                        request.name(), error);
                return Result.requeue(Duration.ofSeconds(30));
            }
        }

        if (hasPendingModerationRecord(annotations)) {
            try {
                moderationRecordService.flushPending(post).block(Duration.ofSeconds(15));
                // flush 会清理 outbox annotation；用最新版本重新进入，避免拿旧 resourceVersion
                // 继续写派生状态。
                return Result.requeue(Duration.ofMillis(100));
            } catch (RuntimeException error) {
                log.warn("Failed to flush pending moderation records for BbsPost {}",
                        request.name(), error);
                return Result.requeue(Duration.ofSeconds(30));
            }
        }

        boolean needsFinalizer = metadata.getFinalizers() == null
                || !metadata.getFinalizers().contains(FINALIZER);
        // Snapshot 指针就绪后，初始化中断的正文暂存只会形成第二数据源；消费掉即清。
        boolean stagedContentPresent = post.getSpec().getContent() != null;
        if (needsFinalizer || stagedContentPresent || initializing) {
            try {
                OptimisticUpdates.update(client, BbsPost.class, request.name(), latest -> {
                    var latestMeta = latest.getMetadata();
                    var latestAnnos = MetadataUtil.nullSafeAnnotations(latest);
                    if (latestMeta.getFinalizers() == null
                            || !latestMeta.getFinalizers().contains(FINALIZER)) {
                        ExtensionUtil.addFinalizers(latestMeta, Set.of(FINALIZER));
                    }
                    latest.getSpec().setContent(null);
                    latestAnnos.remove(BbsPostContentService.INITIALIZING_ANNO);
                });
                return Result.requeue(Duration.ofMillis(100));
            } catch (OptimisticLockingFailureException e) {
                return Result.requeue(Duration.ofMillis(200));
            }
        }

        var desiredCategoryState = categoryCountState(post);
        var desiredSearchState = searchIndexState(post);
        boolean categoryWork = !Objects.equals(
                annotations.get(CATEGORY_COUNT_STATE_ANNO), desiredCategoryState)
                || !Objects.equals(lastCategory, currentCategory);
        boolean searchWork = !Objects.equals(
                annotations.get(SEARCH_INDEX_STATE_ANNO), desiredSearchState);

        if (categoryWork) {
            try {
                countService.recalculateCategoryCounts(
                        Arrays.asList(lastCategory, currentCategory));
            } catch (RuntimeException error) {
                log.warn("Failed to recalculate category counts for BbsPost {}",
                        request.name(), error);
                return Result.requeue(Duration.ofSeconds(1));
            }
        }

        if (searchWork) {
            if (isIndexable(post)) {
                if (StringUtils.isBlank(post.getSpec().getReleaseSnapshot())) {
                    return repairReleasePointer(post, null);
                }
                try {
                    var content = contentService.getReleaseContent(post)
                            .block(Duration.ofSeconds(10));
                    if (content == null) {
                        return repairReleasePointer(post, null);
                    }
                    eventPublisher.publishEvent(new HaloDocumentAddRequestEvent(this,
                            List.of(BbsPostDocumentsProvider.convert(post,
                                    content.getContent()))));
                } catch (ResponseStatusException error) {
                    log.warn("Broken release pointer for BbsPost {}; attempting recovery",
                            request.name(), error);
                    return repairReleasePointer(post, post.getSpec().getReleaseSnapshot());
                } catch (RuntimeException error) {
                    log.warn("Failed to restore release snapshot for search indexing: {}",
                            request.name(), error);
                    return Result.requeue(Duration.ofSeconds(1));
                }
            } else {
                eventPublisher.publishEvent(new HaloDocumentDeleteRequestEvent(this,
                        List.of(BbsPostDocumentsProvider.docId(metadata.getName()))));
            }
        }

        eventPublisher.publishEvent(new BbsPostChangedEvent(this));
        if (categoryWork || searchWork) {
            try {
                OptimisticUpdates.update(client, BbsPost.class, request.name(), latest -> {
                    var latestAnnotations = MetadataUtil.nullSafeAnnotations(latest);
                    if (categoryWork) {
                        latestAnnotations.put(CATEGORY_COUNT_STATE_ANNO,
                                desiredCategoryState);
                        if (currentCategory == null) {
                            latestAnnotations.remove(LAST_CATEGORY_ANNO);
                        } else {
                            latestAnnotations.put(LAST_CATEGORY_ANNO, currentCategory);
                        }
                    }
                    if (searchWork) {
                        latestAnnotations.put(SEARCH_INDEX_STATE_ANNO, desiredSearchState);
                    }
                });
            } catch (OptimisticLockingFailureException error) {
                return Result.requeue(Duration.ofMillis(200));
            }
        }
        return Result.doNotRetry();
    }

    private static boolean hasPendingModerationRecord(java.util.Map<String, String> annotations) {
        return annotations.keySet().stream()
                .anyMatch(key -> key.startsWith(BbsModerationRecordService.PENDING_ANNO_PREFIX));
    }

    private static boolean isIndexable(BbsPost post) {
        return post.getSpec().getPhase() == BbsPost.Phase.PUBLISHED
                && !Boolean.TRUE.equals(post.getSpec().getDeleted());
    }

    /** 评论数、最后活跃时间等变化不会改变分类计数，因此不进入指纹。 */
    static String categoryCountState(BbsPost post) {
        var spec = post.getSpec();
        return "v1:" + fingerprint(
                spec.getCategoryName(),
                spec.getPhase() == null ? null : spec.getPhase().name(),
                String.valueOf(Boolean.TRUE.equals(spec.getDeleted())));
    }

    /**
     * 只纳入 {@link BbsPostDocumentsProvider#convert} 及正文还原真正依赖的字段。
     * 评论数、最后活跃时间、锁定/已解决等业务状态均不影响搜索文档。
     */
    static String searchIndexState(BbsPost post) {
        if (!isIndexable(post)) {
            return "v1:absent";
        }
        var spec = post.getSpec();
        var excerpt = spec.getExcerpt();
        return "v1:" + fingerprint(
                spec.getTitle(), spec.getSlug(), spec.getOwner(), spec.getCategoryName(),
                spec.getBaseSnapshot(), spec.getReleaseSnapshot(),
                spec.getPublishTime() == null ? null : spec.getPublishTime().toString(),
                spec.getLastEditTime() == null ? null : spec.getLastEditTime().toString(),
                excerpt == null || excerpt.getAutoGenerate() == null
                        ? null : excerpt.getAutoGenerate().toString(),
                excerpt == null ? null : excerpt.getRaw());
    }

    /** 长度前缀避免 null、空串及字段边界碰撞。 */
    private static String fingerprint(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var value : values) {
                if (value == null) {
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
                    continue;
                }
                var bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private boolean hasInvalidCategoryReference(BbsPost post) {
        var spec = post.getSpec();
        var releaseCategory = spec.getCategoryName();
        if (isMissingOrDeletingCategory(releaseCategory)) {
            return true;
        }
        var draft = spec.getDraft();
        var draftCategory = draft == null ? null : draft.getCategoryName();
        return !Objects.equals(releaseCategory, draftCategory)
                && isMissingOrDeletingCategory(draftCategory);
    }

    private void clearInvalidCategoryReferences(BbsPost post) {
        var spec = post.getSpec();
        if (isMissingOrDeletingCategory(spec.getCategoryName())) {
            spec.setCategoryName(null);
        }
        var draft = spec.getDraft();
        if (draft != null && isMissingOrDeletingCategory(draft.getCategoryName())) {
            draft.setCategoryName(null);
        }
    }

    private boolean isMissingOrDeletingCategory(String categoryName) {
        if (StringUtils.isBlank(categoryName)) {
            return false;
        }
        return client.fetch(BbsCategory.class, categoryName)
                .filter(category -> !ExtensionUtil.isDeleted(category))
                .isEmpty();
    }

    private Result repairReleasePointer(BbsPost post, String invalidSnapshot) {
        var name = post.getMetadata().getName();
        try {
            var preferred = moderationRecordService.latestPublishedSnapshotName(name)
                    .block(Duration.ofSeconds(5));
            contentService.repairReleasePointer(name, preferred, invalidSnapshot)
                    .block(Duration.ofSeconds(10));
            return Result.requeue(Duration.ofMillis(100));
        } catch (RuntimeException error) {
            log.warn("Failed to repair release pointer for BbsPost {}", name, error);
            // 无法自愈的数据降频重试，避免 500ms 永久忙等拖垮控制器。
            return Result.requeue(Duration.ofSeconds(30));
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new BbsPost())
                .build();
    }
}
