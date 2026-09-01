package com.timxs.bbs.vo;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/**
 * Console 回复管理 VO（楼中楼管理面；回复无子回复，无 replyCount）。
 *
 * @author Tim0x0
 */
@Data
@Builder
public class BbsReplyAdminVo {

    /** Reply 的 metadata.name */
    private String name;

    private CommentOwnerVo owner;

    /** HTML（写入时已净化） */
    private String content;

    private Boolean approved;

    private Boolean hidden;

    private Instant creationTime;

    private Instant approvedTime;

    /** 删除中（已设删除时间戳、finalizer 未跑完） */
    private Boolean deleting;

    /** 所属评论 metadata.name */
    private String commentName;

    /** 被引用的回复（楼中楼 @），可空 */
    private String quoteReply;
}
