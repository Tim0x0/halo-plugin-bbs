package com.timxs.bbs.comment;

import run.halo.app.core.extension.content.Comment;

/**
 * 锁定帖只读楼层的楼号。
 *
 * <p>楼主帖占 1，评论从 2 起。号在评论创建时由 {@link BbsFloorAllocator} 从帖子号盘
 * 取出、冻进 {@link BbsCommentAnnos#FLOOR}，读写只认这一个来源：删评不重排，
 * 空号留给已删层；待审核也占号；置顶只把该层提到列表前，号不变。
 * 楼中楼回复（Reply）不占独立楼号。</p>
 *
 * @author Tim0x0
 */
public final class BbsFloorNumbers {

    /** 第一条评论的楼号（楼主占 1）。 */
    public static final int FIRST_COMMENT_FLOOR = 2;

    private BbsFloorNumbers() {
    }

    /** {@code earlierCount} 条更早的评论 → 本层楼号。 */
    public static int ofEarlierCount(int earlierCount) {
        return FIRST_COMMENT_FLOOR + Math.max(0, earlierCount);
    }

    /**
     * 读评论上冻住的楼号。无 {@link BbsCommentAnnos#FLOOR} 返回 0——只发生在
     * 官方编辑器刚发出、调和器还没写上号的一瞬间。
     */
    public static int read(Comment comment) {
        if (comment == null || comment.getMetadata() == null) {
            return 0;
        }
        var annos = comment.getMetadata().getAnnotations();
        if (annos == null) {
            return 0;
        }
        var raw = annos.get(BbsCommentAnnos.FLOOR);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int floor = Integer.parseInt(raw.trim());
            return floor >= FIRST_COMMENT_FLOOR ? floor : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
