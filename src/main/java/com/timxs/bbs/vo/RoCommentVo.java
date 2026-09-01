package com.timxs.bbs.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 只读评论 VO（锁定帖的历史评论，bbs.js roItem 消费）。
 *
 * <p>与 Halo 公开评论 API 的差别：{@code owner.name} 仅对 User kind 返回（username），
 * 供 hip-user-avatar 拉装扮；Email kind（匿名评论）不返回 name（防 email 泄露），
 * 前端字母兜底。{@code content} 为 Halo 写入时已净化的 HTML。</p>
 *
 * <p>无头像字母 / 底色由前台按 {@code displayName} 哈希派生，本 VO 不下发。</p>
 *
 * @author Tim0x0
 */
@Data
@Builder
public class RoCommentVo {

    /** Comment 的 metadata.name */
    private String name;

    private CommentOwnerVo owner;

    /** 净化后的 HTML（Halo Comment.spec.content 写入时已净化） */
    private String content;

    /** ISO-8601 创建时间 */
    private String creationTime;

    /** 赞数（Counter metrics.halo.run） */
    private Integer upvote;

    /** 可见回复数（已审核 + 非私密，取 CommentStatus.visibleReplyCount） */
    private Integer replyCount;

    /**
     * 列表内预取的前若干条回复（评论专用，回复自身恒为 null）。
     * 前端直接渲染，超出 {@code replyCount} 的部分再走 /comments/{name}/replies 分页。
     */
    private List<RoCommentVo> replies;

    /** 是否置顶 */
    private Boolean top;

    /**
     * 被回复者（{@code Reply.spec.quoteReply} 指向的那条回复的作者），前端渲染「回复 @昵称」。
     * 直接回复评论、引用目标已删除 / 未审核 / 私密时为 {@code null}。评论自身恒为 null。
     */
    private CommentOwnerVo quote;

    /** 排序优先级 */
    private Integer priority;
}
