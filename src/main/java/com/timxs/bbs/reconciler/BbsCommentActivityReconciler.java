package com.timxs.bbs.reconciler;

import com.timxs.bbs.comment.BbsCommentAnnos;
import com.timxs.bbs.comment.BbsFloorAllocator;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.service.BbsCommentNotificationService;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.controller.ControllerBuilder;

/**
 * 评论活跃调和器：公开可见评论（已通过且未隐藏）落在 BBS 帖子上时，把帖子的
 * {@code spec.lastActivityTime} 顶到评论时间——前台「最后活跃」排序（回帖顶起）的数据来源。
 *
 * <p>口径与评论数统计一致：仅计 {@link Comment}，楼中楼回复（Reply）不触发顶帖。
 * 评论删除不回退活跃时间（幂等向前，避免删评引发的批量重算）。启动期全量调和
 * 保证每篇帖子都有活跃时间。</p>
 *
 * <p>不挂 finalizer：Comment 是 Halo 核心资源，本调和器只读评论、按需更新帖子。
 * 顺带补楼号（官方编辑器直发时插件不在场）和新评论通知（官方只给核心 Post /
 * SinglePage 发，BBS 帖子由此补位）。官方靠评论保护锁只在第一次调和发事件；
 * 该事件未标跨插件共享，插件听不到。本调和器用评论 annotation 当一次性闸门：
 * 有标不再发，失败不打标下次重试。升级时无标的存量评论会发一次，之后打标。</p>
 *
 * @author Tim0x0
 */
@Component
@Slf4j
public class BbsCommentActivityReconciler implements Reconciler<Reconciler.Request> {

    private static final GroupVersionKind POST_GVK =
            GroupVersionKind.fromExtension(BbsPost.class);

    private final ExtensionClient client;
    private final BbsCountService countService;
    private final BbsFloorAllocator floorAllocator;
    private final BbsCommentNotificationService commentNotificationService;

    public BbsCommentActivityReconciler(ExtensionClient client, BbsCountService countService,
            BbsFloorAllocator floorAllocator,
            BbsCommentNotificationService commentNotificationService) {
        this.client = client;
        this.countService = countService;
        this.floorAllocator = floorAllocator;
        this.commentNotificationService = commentNotificationService;
    }

    @Override
    public Result reconcile(Request request) {
        client.fetch(Comment.class, request.name()).ifPresent(comment -> {
            var spec = comment.getSpec();
            if (spec == null) {
                return;
            }
            var ref = spec.getSubjectRef();
            if (ref == null
                    || !Objects.equals(POST_GVK.group(), ref.getGroup())
                    || !Objects.equals(POST_GVK.kind(), ref.getKind())) {
                return;
            }
            floorAllocator.assignIfNew(comment);
            notifyNewCommentIfNeeded(comment, ref.getName());
            // 只在可见的新评论上顶活跃时间；删除 / 撤回审核 / 隐藏都不回退时间，
            // 但都要重算计数——两者合并成对帖子的一次写入
            Instant activityTime = null;
            if (!ExtensionUtil.isDeleted(comment)
                    && Boolean.TRUE.equals(spec.getApproved())
                    && !Boolean.TRUE.equals(spec.getHidden())) {
                activityTime = spec.getCreationTime() != null
                        ? spec.getCreationTime()
                        : comment.getMetadata().getCreationTimestamp();
            }
            countService.syncPostComment(ref.getName(), activityTime);
        });
        return Result.doNotRetry();
    }

    /**
     * 新评论通知帖子作者（对齐官方「创建即发」：不看 approved / hidden）。
     * 以 {@code NEW_COMMENT_NOTIFIED} 标记防重；通知失败不打标记，下次调和重试。
     */
    private void notifyNewCommentIfNeeded(Comment comment, String postName) {
        if (ExtensionUtil.isDeleted(comment)
                || MetadataUtil.nullSafeAnnotations(comment)
                        .containsKey(BbsCommentAnnos.NEW_COMMENT_NOTIFIED)) {
            return;
        }
        boolean done = false;
        try {
            var post = client.fetch(BbsPost.class, postName);
            // 帖子不存在也打标记：调和会对已删帖子反复进入，白 fetch 无意义
            if (post.isEmpty()) {
                done = true;
            } else {
                commentNotificationService
                        .notifyNewComment(post.get(), comment)
                        .block(Duration.ofSeconds(5));
                done = true;
            }
        } catch (RuntimeException error) {
            log.warn("发送 BBS 新评论通知失败：post={} comment={}",
                    postName, comment.getMetadata().getName(), error);
        }
        if (done) {
            OptimisticUpdates.update(client, Comment.class, comment.getMetadata().getName(),
                    latest -> MetadataUtil.nullSafeAnnotations(latest)
                            .put(BbsCommentAnnos.NEW_COMMENT_NOTIFIED, "true"));
        }
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new Comment())
                .build();
    }
}
