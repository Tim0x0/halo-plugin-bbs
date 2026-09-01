package com.timxs.bbs.reconciler;

import com.timxs.bbs.extension.BbsPost;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.controller.ControllerBuilder;

/**
 * 评论活跃调和器：公开可见评论（已通过且未隐藏）落在 BBS 帖子上时，把帖子的
 * {@code spec.lastActivityTime} 顶到评论时间——前台「最后活跃」排序（回帖顶起）的数据来源。
 *
 * <p>口径与评论数统计一致：仅计 {@link Comment}，楼中楼回复（Reply）不触发顶帖。
 * 评论删除不回退活跃时间（幂等向前，避免删评引发的批量重算）。启动期全量调和
 * 会自动为历史帖补齐活跃时间，无需数据迁移。</p>
 *
 * <p>不挂 finalizer：Comment 是 Halo 核心资源，本调和器只读评论、按需更新帖子。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsCommentActivityReconciler implements Reconciler<Reconciler.Request> {

    private static final GroupVersionKind POST_GVK =
            GroupVersionKind.fromExtension(BbsPost.class);

    private final ExtensionClient client;
    private final BbsCountService countService;

    public BbsCommentActivityReconciler(ExtensionClient client, BbsCountService countService) {
        this.client = client;
        this.countService = countService;
    }

    @Override
    public Result reconcile(Request request) {
        client.fetch(Comment.class, request.name()).ifPresent(comment -> {
            var spec = comment.getSpec();
            var ref = spec.getSubjectRef();
            if (ref == null
                    || !Objects.equals(POST_GVK.group(), ref.getGroup())
                    || !Objects.equals(POST_GVK.kind(), ref.getKind())) {
                return;
            }
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

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
                .extension(new Comment())
                .build();
    }
}
