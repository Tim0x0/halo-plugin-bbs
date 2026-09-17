package com.timxs.bbs.comment;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.reconciler.OptimisticUpdates;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.MetadataUtil;

/**
 * 楼号分配：帖子 {@code status.nextCommentFloor} 是只增不减的号盘，删评不回退，
 * 空号留给已删层。
 *
 * <p>官方编辑器直发的评论由评论调和器调 {@link #assignIfNew} 补号。
 * 楼号只给顶层 Comment；楼中楼 Reply 不占号。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsFloorAllocator {

    private final ExtensionClient client;

    public BbsFloorAllocator(ExtensionClient client) {
        this.client = client;
    }

    /**
     * 官方编辑器直发的评论：创建那一刻插件不在场，由调和器在此从号盘取号
     * 冻进 annotation。已有号 / 删除中不动。幂等，调和线程（阻塞）调用。
     */
    public void assignIfNew(Comment comment) {
        if (comment == null || comment.getMetadata() == null || comment.getSpec() == null) {
            return;
        }
        if (BbsFloorNumbers.read(comment) >= BbsFloorNumbers.FIRST_COMMENT_FLOOR) {
            return;
        }
        if (comment.getMetadata().getDeletionTimestamp() != null) {
            return;
        }
        var ref = comment.getSpec().getSubjectRef();
        if (ref == null
                || !"bbs.timxs.com".equals(ref.getGroup())
                || !"BbsPost".equals(ref.getKind())
                || StringUtils.isBlank(ref.getName())) {
            return;
        }
        int[] assigned = {0};
        boolean ok = OptimisticUpdates.update(client, BbsPost.class, ref.getName(),
                post -> assigned[0] = takeNext(post, ref.getName()));
        if (!ok || assigned[0] < BbsFloorNumbers.FIRST_COMMENT_FLOOR) {
            return;
        }
        int floor = assigned[0];
        OptimisticUpdates.update(client, Comment.class, comment.getMetadata().getName(), latest -> {
            if (BbsFloorNumbers.read(latest) >= BbsFloorNumbers.FIRST_COMMENT_FLOOR) {
                return;
            }
            MetadataUtil.nullSafeAnnotations(latest)
                    .put(BbsCommentAnnos.FLOOR, String.valueOf(floor));
        });
    }

    /** 号盘取号；号盘未初始化时按现有评论数起算（楼主占 1，评论从 2 起）。 */
    private int takeNext(BbsPost post, String postName) {
        var status = post.getStatus();
        if (status == null) {
            status = new BbsPost.Status();
            post.setStatus(status);
        }
        Integer next = status.getNextCommentFloor();
        /* 号盘未初始化：调和器在评论入库之后才跑，countLive 已包含本条自己，
           减 1 才是「排在我前面的条数」，否则首条评论会拿到 #3 而不是 #2。 */
        int assigned = next != null && next >= BbsFloorNumbers.FIRST_COMMENT_FLOOR
                ? next
                : BbsFloorNumbers.ofEarlierCount(Math.max(0, countLive(postName) - 1));
        status.setNextCommentFloor(assigned + 1);
        return assigned;
    }

    private int countLive(String postName) {
        return (int) client.countBy(Comment.class, liveOptions(postName));
    }

    private static ListOptions liveOptions(String postName) {
        return ListOptions.builder()
                .fieldQuery(and(
                        equal("spec.subjectRef", "bbs.timxs.com/BbsPost/" + postName),
                        equal("spec.hidden", false),
                        isNull("metadata.deletionTimestamp")))
                .build();
    }
}
