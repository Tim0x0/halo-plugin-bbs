package com.timxs.bbs.vo;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/**
 * Console 评论管理 VO：帖子列表评论列弹窗的数据形态（对齐官方 ListedComment 的管理面）。
 *
 * <p>owner 防泄露策略同 {@link RoCommentVo}：Email kind 的 name 是邮箱，不下发；
 * 昵称 / 头像以 User 表为准（owner 里的是创建时快照，改名后会过时）。</p>
 *
 * @author Tim0x0
 */
@Data
@Builder
public class BbsCommentAdminVo {

    /** Comment 的 metadata.name */
    private String name;

    private CommentOwnerVo owner;

    /** HTML（Halo / 插件写入时已净化） */
    private String content;

    private Boolean approved;

    private Boolean hidden;

    private Boolean top;

    private Integer priority;

    private Instant creationTime;

    private Instant approvedTime;

    /** 回复总数（核心维护，含未审核与隐藏） */
    private Integer replyCount;

    /** 删除中（已设删除时间戳、finalizer 未跑完） */
    private Boolean deleting;

    private String ipAddress;

    private String userAgent;
}
