package com.timxs.bbs.comment;

/**
 * 评论 annotation 键（Comment.metadata.annotations）。
 *
 * @author Tim0x0
 */
public final class BbsCommentAnnos {

    /**
     * 本层楼号（楼主占 1，评论从 2 起）。发评时从帖子 {@code status.nextCommentFloor}
     * 取出写入，之后不再改；楼号读取只认此键。
     */
    public static final String FLOOR = "bbs.timxs.com/floor";

    /**
     * 新评论通知已处理（发给作者，或确认不必发）。调和会重复进入，此键防重发。
     * 官方用评论保护锁当一次性闸门；插件不能给核心 Comment 加锁，用此键等价。
     */
    public static final String NEW_COMMENT_NOTIFIED = "bbs.timxs.com/new-comment-notified";

    private BbsCommentAnnos() {
    }
}
