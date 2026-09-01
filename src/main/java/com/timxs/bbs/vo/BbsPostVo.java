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

    /** DRAFT=未发布，PENDING=待审核，PUBLISHED=已发布，REJECTED=已驳回 */
    private String phase;

    /**
     * 已发布修改稿的流程状态（仅 Console / UC 编辑视图返回）。
     * DRAFT=有未提交修改，PENDING=修改待审核，REJECTED=修改被驳回。
     */
    private String draftPhase;

    /** 是否存在独立于当前前台发布版本的工作稿（仅 Console / UC 编辑视图有意义）。 */
    private Boolean hasDraft;

    /**
     * 快照三指针与 head 的乐观锁版本；仅鉴权后的 Console / UC 视图返回。
     *
     * <p>历史版本面板据此给每个快照打「基础 / 工作中 / 已发布」徽标——与官方 Console
     * 一致，徽标由前端拿帖子指针与快照 name 比对得出，快照 DTO 本身不带这些标记。</p>
     */
    private String baseSnapshot;

    private String headSnapshot;

    private String releaseSnapshot;

    private Long snapshotVersion;

    private Boolean pinned;

    /**
     * 在本次返回的列表视图中是否真的浮顶——渲染置顶徽标应以此为准，而非 {@link #pinned}。
     *
     * <p>{@code pinned} 是帖子固有属性，{@code pinnedInView} 是视图状态：首页仅当所属分类
     * 开启 {@code pinToHome} 时为 true（未开启的置顶帖混在普通流中，不该挂图钉）；分类页
     * 作用域内的置顶帖为 true；第 2 页起与非列表场景（详情 / Console / UC）恒为 false。</p>
     */
    private Boolean pinnedInView;

    private Integer pinPriority;

    /** 是否已锁定（禁评论、禁作者编辑；前台显示锁定标识） */
    private Boolean locked;

    /** 问答帖是否已解决（仅 QUESTION 有意义） */
    private Boolean solved;

    /** 驳回原因（帖子或已发布修改稿为 REJECTED 时有值，Console / UC 展示用） */
    private String rejectReason;

    /** 公开可见的评论数（Halo 评论体系，已通过且未隐藏；不含楼中楼回复） */
    private Long commentsCount;

    /**
     * 评论总数，官方口径（不区分审核与隐藏；不含楼中楼回复；同官方
     * Counter.totalComment）。Console/UC 列表按官方口径展示「N 条评论」。
     */
    private Long totalCommentCount;

    /**
     * 待审核评论数（totalCommentCount 减已通过）。大于 0 时 Console 列表的
     * 评论列上色并允许点击打开评论管理弹窗。
     */
    private Long pendingCommentCount;

    /** 展示摘要（已折算：自动模式为实时截取的正文，手工模式为原文；主题直接渲染） */
    private String excerpt;

    /** 摘要是否自动生成——供 Console / UC 表单回填开关，主题无需关心 */
    private Boolean autoExcerpt;

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
