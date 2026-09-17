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

    /** 是否已通过。仅 Console 审核面下发；UC 不下发。 */
    private Boolean approved;

    /** 是否隐藏。仅 Console 审核面下发；UC 不下发。 */
    private Boolean hidden;

    private Boolean top;

    private Integer priority;

    private Instant creationTime;

    /** 通过时间。仅 Console 审核面下发；UC 不下发。 */
    private Instant approvedTime;

    /** 回复数。Console 用核心 status（含待审 / 隐藏）；UC 只计公开可见。 */
    private Integer replyCount;

    /** 删除中（已设删除时间戳、finalizer 未跑完） */
    private Boolean deleting;

    /** 评论者 IP。仅 Console 审核面下发；UC 不下发。 */
    private String ipAddress;

    /** 评论者 UA。仅 Console 审核面下发；UC 不下发。 */
    private String userAgent;
}
