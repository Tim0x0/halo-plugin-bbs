package com.timxs.bbs.vo;

import java.time.Instant;
import lombok.Builder;
import lombok.Data;

/**
 * 帖子 VO（前台 Finder / 公开 API / Console / UC 共用）。
 *
 * <p>面向主题的自包含 DTO：分类与作者的展示属性全部内联。列表场景不含
 * {@code content}（为 null），详情场景才填充。消费方应忽略未知字段，
 * 后续演进只增不改。</p>
 *
 * @author Tim0x0
 */
@Data
@Builder
public class BbsPostVo {

    /** 帖子的 metadata.name */
    private String name;

    private String title;

    private String slug;

    /** ANNOUNCEMENT=公告，POST=普通帖子，QUESTION=问答帖 */
    private String type;

    /** DRAFT=草稿，PUBLISHED=已发布 */
    private String phase;

    private Boolean pinned;

    private Integer pinPriority;

    /** 是否允许评论（false 时前台不渲染评论区） */
    private Boolean allowComment;

    /** 是否已锁定（禁评论、禁作者编辑；前台显示锁定标识） */
    private Boolean locked;

    /** 问答帖是否已解决（仅 QUESTION 有意义） */
    private Boolean solved;

    /** 驳回原因（仅 REJECTED 状态有值，Console / UC 展示用） */
    private String rejectReason;

    /** 公开可见的评论数（Halo 评论体系，已通过且未隐藏；不含楼中楼回复） */
    private Long commentCount;

    private String excerpt;

    /** 净化后的正文 HTML；仅详情接口返回，列表为 null */
    private String content;

    /** 前台访问地址（本插件路由 /bbs/post/{slug}） */
    private String permalink;

    /** 所属分类（内联展示属性；无分类时为 null） */
    private CategoryVo category;

    /** 作者（内联展示属性） */
    private OwnerVo owner;

    private Instant publishTime;

    /** 最后活跃时间（发布或收到公开评论时更新；「最后活跃」排序依据） */
    private Instant lastActivityTime;

    private Instant lastEditTime;

    private Instant creationTimestamp;
}
