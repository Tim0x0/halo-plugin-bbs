package com.timxs.bbs.reconciler;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.extension.BbsPost;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Ref;

/**
 * 派生计数的重算逻辑（分类帖子数 / 帖子评论数），供各调和器复用。
 *
 * <p><b>为什么要存计数</b>：原本每次列表查询都对每条记录实时 count——一页 20 条帖子
 * 就是 20 次统计查询，且前台每次访问都在付这个代价。计数缓存是论坛的通行做法
 * （Discourse 的 {@code topics.posts_count}、Discuz 的 {@code forum.threads} 皆然）。</p>
 *
 * <p><b>一致性</b>：每次都按当前条件重新数一遍（而非增减计数器），因此不会累积漂移；
 * 调和器的最终一致模型会在启动期全量补齐历史数据，无需迁移脚本。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsCountService {

    private static final GroupVersionKind POST_GVK = GroupVersionKind.fromExtension(BbsPost.class);

    private final ExtensionClient client;

    public BbsCountService(ExtensionClient client) {
        this.client = client;
    }

    /** 重算若干分类的帖子数；只在数值真变了才写回，避免调和器互相触发空转。 */
    public void recalculateCategoryCounts(Collection<String> categoryNames) {
        categoryNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .forEach(this::recalculateCategoryCount);
    }

    private void recalculateCategoryCount(String categoryName) {
        // 回收站里的帖子不计入任何口径——它对用户而言已经"不存在"了
        int total = countPosts(and(
                equal("spec.categoryName", categoryName),
                equal("spec.deleted", false)));
        int visible = countPosts(and(
                equal("spec.categoryName", categoryName),
                equal("spec.deleted", false),
                equal("spec.phase", BbsPost.Phase.PUBLISHED.name())));
        var found = client.fetch(BbsCategory.class, categoryName);
        if (found.isEmpty()) {
            return;
        }
        var current = found.get().getStatus();
        if (current != null
                && Objects.equals(current.getPostCount(), total)
                && Objects.equals(current.getVisiblePostCount(), visible)) {
            return;
        }
        OptimisticUpdates.update(client, BbsCategory.class, categoryName, category -> {
            var status = category.getStatus();
            if (status == null) {
                status = new BbsCategory.Status();
                category.setStatus(status);
            }
            status.setPostCount(total);
            status.setVisiblePostCount(visible);
        });
    }

    /**
     * 同步帖子的评论派生数据：重算可见评论数，并按需前推最后活跃时间。
     *
     * <p>两件事合并成对帖子的<b>一次</b>写入——分开写会让每条评论触发两轮帖子调和；
     * 即便重型派生工作已有状态指纹保护，也不应制造多余事件。</p>
     *
     * @param activityTime 可见新评论的时间；null 表示本次变更不该顶起活跃时间
     *                     （删除 / 撤回审核 / 隐藏——删条评论不该把「最后活跃」拽回去）
     */
    public void syncPostComment(String postName, Instant activityTime) {
        if (postName == null || postName.isBlank()) {
            return;
        }
        int visible = countVisibleComments(postName);
        int total = countTotalComments(postName);
        int pending = Math.max(0, total - countApprovedComments(postName));
        var found = client.fetch(BbsPost.class, postName);
        if (found.isEmpty()) {
            return;
        }
        var post = found.get();
        var status = post.getStatus();
        // 三个计数全比：新增「未审核」评论不改变可见数，只比可见数会让
        // total/pending 的变更事件被当成无变化而静默丢写
        boolean countChanged = status == null
                || !Objects.equals(status.getCommentsCount(), visible)
                || !Objects.equals(status.getTotalCommentCount(), total)
                || !Objects.equals(status.getPendingCommentCount(), pending);
        var currentActivity = post.getSpec().getLastActivityTime();
        boolean activityChanged = activityTime != null
                && (currentActivity == null || currentActivity.isBefore(activityTime));
        if (!countChanged && !activityChanged) {
            return;
        }
        OptimisticUpdates.update(client, BbsPost.class, postName, latest -> {
            var latestStatus = latest.getStatus();
            if (latestStatus == null) {
                latestStatus = new BbsPost.Status();
                latest.setStatus(latestStatus);
            }
            latestStatus.setCommentsCount(visible);
            latestStatus.setTotalCommentCount(total);
            latestStatus.setPendingCommentCount(pending);
            var latestActivity = latest.getSpec().getLastActivityTime();
            if (activityTime != null
                    && (latestActivity == null || latestActivity.isBefore(activityTime))) {
                latest.getSpec().setLastActivityTime(activityTime);
            }
        });
    }

    /** 前台口径：已通过且未隐藏的顶层评论（楼中楼回复不计入）。 */
    private int countVisibleComments(String postName) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.subjectRef", postSubjectRefKey(postName)),
                        equal("spec.approved", true),
                        equal("spec.hidden", false),
                        // 删除中的评论（已设删除时间戳、finalizer 未跑完）不能再计入
                        isNull("metadata.deletionTimestamp")))
                .build();
        return (int) client.countBy(Comment.class, options);
    }

    /**
     * 官方口径（对齐核心 CommentReconciler 维护的 Counter.totalComment）：
     * subjectRef 匹配且未删除的顶层评论，不区分审核与隐藏。
     */
    private int countTotalComments(String postName) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.subjectRef", postSubjectRefKey(postName)),
                        isNull("metadata.deletionTimestamp")))
                .build();
        return (int) client.countBy(Comment.class, options);
    }

    /** 官方口径 approvedComment：total 基础上加 spec.approved=true。 */
    private int countApprovedComments(String postName) {
        var options = ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.subjectRef", postSubjectRefKey(postName)),
                        equal("spec.approved", true),
                        isNull("metadata.deletionTimestamp")))
                .build();
        return (int) client.countBy(Comment.class, options);
    }

    private String postSubjectRefKey(String postName) {
        var ref = new Ref();
        ref.setGroup(POST_GVK.group());
        ref.setKind(POST_GVK.kind());
        ref.setName(postName);
        return Comment.toSubjectRefKey(ref);
    }

    private int countPosts(run.halo.app.extension.index.query.Condition condition) {
        // isNull(deletionTimestamp)：彻底删除会先设删除时间戳、等 finalizer 跑完才真正消失，
        // 这段窗口里的帖子已经不该算数了（对齐官方 CategoryPostCountUpdater 的 basePostQuery）
        var options = ListOptions.builder()
                .fieldQuery(and(isNull("metadata.deletionTimestamp"), condition))
                .build();
        return (int) client.countBy(BbsPost.class, options);
    }
}
