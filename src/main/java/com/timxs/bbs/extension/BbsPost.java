package com.timxs.bbs.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * BBS 社区帖子：公告 / 讨论 / 问答共用此模型，靠 {@link Spec#type} 区分。
 *
 * <p>正文完整复用 Halo 核心 {@code content.halo.run/v1alpha1/Snapshot}：
 * {@code baseSnapshot} 是差异基线，{@code headSnapshot} 是编辑器工作版本，
 * {@code releaseSnapshot} 是前台发布版本。帖子本体只保存业务元数据与快照指针。</p>
 *
 * @author Tim0x0
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "bbs.timxs.com",
        version = "v1alpha1",
        kind = "BbsPost",
        plural = "bbsposts",
        singular = "bbspost")
public class BbsPost extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private Spec spec = new Spec();

    /**
     * 派生状态：由调和器维护，任何写入方都不该手改。
     *
     * <p>存在的意义是把「每次列表查询都实时 count」变成「预先算好存着」——
     * 一页 20 条帖子原本要发 20 次统计查询，前台每次访问也在付这个代价。</p>
     */
    private Status status = new Status();

    /** 派生状态（调和器维护）。 */
    @Data
    @Schema(name = "BbsPostStatus")
    public static class Status {

        @Schema(description = "公开可见的评论数（已通过且未隐藏；楼中楼回复不计入）")
        private Integer commentsCount = 0;

        @Schema(description = "评论总数（不区分审核与隐藏；楼中楼回复不计入；"
                + "口径同官方 Counter.totalComment）")
        private Integer totalCommentCount = 0;

        @Schema(description = "待审核评论数（总数减已通过；口径同官方 "
                + "totalComment - approvedComment；Console/UC 列表据此给评论列上色")
        private Integer pendingCommentCount = 0;

        @Schema(description = "当前 head Snapshot 的乐观锁版本，供多标签编辑冲突检测")
        private Long headSnapshotVersion;
    }

    @Data
    @Schema(name = "BbsPostSpec")
    public static class Spec {

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 200, description = "标题")
        private String title;

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 200,
                description = "永久链接别名（草稿可暂时重名；提交后唯一，前台 /bbs/post/{slug}）")
        private String slug;

        @Schema(requiredMode = REQUIRED, description = "类型：公告 / 普通帖子 / 问答帖")
        private PostType type = PostType.POST;

        @Schema(description = "所属分类的 metadata.name（草稿可暂缺；提交时各类型均必选）")
        private String categoryName;

        @Schema(description = "摘要：autoGenerate=true 时不存 raw，展示时实时截取正文")
        private Excerpt excerpt = new Excerpt();

        @Schema(description = "首个内容快照 metadata.name（所有后续快照都相对它存差异）")
        private String baseSnapshot;

        @Schema(description = "编辑器当前工作快照 metadata.name")
        private String headSnapshot;

        @Schema(description = "前台当前发布快照 metadata.name；未发布时可为空")
        private String releaseSnapshot;

        /**
         * 初始化中断暂存：BbsPost 创建成功但首个 Snapshot 尚未建立的窗口期兜底，
         * 调和器据此幂等补齐指针后清空。正常运行时本字段恒为 null。
         */
        @Schema(description = "初始化中断暂存的正文（调和器消费后清空）")
        private String content;

        /**
         * 已发布帖子的可编辑工作副本，对应 Halo 文章的 head snapshot。
         *
         * <p>本字段非空时，标题、别名、类型、分类、摘要与正文都只代表 UC / Console
         * 编辑器里的最新草稿元数据；正文由 {@link Spec#getHeadSnapshot()} 指向。
         * 公开 API、Finder、RSS、搜索与主题始终读取发布元数据和
         * {@link Spec#getReleaseSnapshot()}。</p>
         */
        @Schema(description = "已发布帖子的完整工作草稿（前台不读取；正式发布后清空）")
        private Draft draft;

        @Schema(description = "发布人 User 的 metadata.name")
        private String owner;

        @Schema(description = "是否置顶：分类页第 1 页顶部；若所属分类树的一级分类开启 "
                + "pinToHome，则同时出现在首页第 1 页顶部")
        private Boolean pinned = false;

        @Schema(description = "置顶排序优先级，值越大越靠前")
        private Integer pinPriority = 0;

        @Schema(description = "是否锁定（管理员/版主操作：禁评论且禁作者编辑，前台显示锁定标识）")
        private Boolean locked = false;

        @Schema(description = "问答帖是否已解决（发帖人与管理员/版主可切换；仅 QUESTION 有意义）")
        private Boolean solved = false;

        @Schema(requiredMode = REQUIRED, description = "状态：未发布 / 待审核 / 已发布 / 已驳回")
        private Phase phase = Phase.DRAFT;

        @Schema(description = "驳回原因（审核驳回时管理员填写；重新提交或发布后清空）",
                maxLength = 500)
        private String rejectReason;

        /*
         * 软删除标记。与 metadata.deletionTimestamp（真删）分开：
         * 删帖先置此标记进回收站，前台与列表立即不可见，但内容还在、可恢复。
         * 老数据没有该键，反序列化后保持默认 false——「没有这个字段」的语义本就是「未删除」。
         */
        @Schema(description = "是否已移入回收站（软删除）；彻底删除才会真正移除数据")
        private Boolean deleted = false;

        @Schema(description = "发布时间")
        private Instant publishTime;

        @Schema(description = "最后活跃时间（发布时=发布时间，收到公开评论时更新；"
                + "「最后活跃」排序依据，由评论调和器维护）")
        private Instant lastActivityTime;

        @Schema(description = "最后编辑时间（编辑后更新，用于展示『已编辑』）")
        private Instant lastEditTime;
    }

    /**
     * 已发布帖子的完整工作副本（Halo head snapshot 的插件内等价模型）。
     *
     * <p>{@link #phase} 只描述这份修改稿的流程状态；帖子本身仍保持
     * {@link Phase#PUBLISHED}，因此静默保存、提交审核和审核驳回都不会改变当前前台版本。
     * 审核通过或无需审核的显式提交会把本对象整体提升到 {@link Spec} 并清空本字段。</p>
     */
    @Data
    @Schema(name = "BbsPostDraft")
    public static class Draft {

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 200,
                description = "工作草稿标题")
        private String title;

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 200,
                description = "工作草稿别名（DRAFT / REJECTED 可暂时重名，PENDING 时唯一）")
        private String slug;

        @Schema(requiredMode = REQUIRED, description = "工作草稿类型")
        private PostType type = PostType.POST;

        @Schema(description = "工作草稿所属分类的 metadata.name")
        private String categoryName;

        @Schema(description = "工作草稿摘要")
        private Excerpt excerpt = new Excerpt();

        @Schema(requiredMode = REQUIRED,
                description = "修改稿状态：DRAFT / PENDING / REJECTED（不会使用 PUBLISHED）")
        private Phase phase = Phase.DRAFT;

        @Schema(description = "修改稿驳回原因", maxLength = 500)
        private String rejectReason;

        @Schema(description = "工作草稿最后编辑时间")
        private Instant lastEditTime;
    }

    /**
     * 摘要（对齐官方 Post.spec.excerpt 结构）。
     *
     * <p>{@code autoGenerate=true} 时 {@code raw} 不存值——展示文本由
     * {@code BbsExcerpts.resolve} 实时截取正文，正文改则摘要跟随。</p>
     */
    @Data
    @Schema(name = "BbsPostExcerpt")
    public static class Excerpt {

        @Schema(description = "是否自动从正文截取")
        private Boolean autoGenerate = true;

        @Schema(description = "手工摘要原文（autoGenerate=false 时生效）", maxLength = 500)
        private String raw;
    }

    /** 帖子类型：公告 / 普通帖子 / 问答帖（与置顶正交：置顶是独立开关，各类帖子均可置顶）。 */
    public enum PostType {
        /** 公告（管理员发布的官方帖子：列表混排、带「公告」标识，作用域=所发分类及其子分类） */
        ANNOUNCEMENT,
        /** 普通帖子 */
        POST,
        /** 问答帖（配合 solved 标记构成提问-解答闭环） */
        QUESTION
    }

    /** 帖子状态：未发布 / 待审核 / 已发布 / 已驳回。 */
    public enum Phase {
        DRAFT,
        PENDING,
        PUBLISHED,
        REJECTED
    }
}
