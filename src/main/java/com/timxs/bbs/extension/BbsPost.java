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
 * BBS 社区帖子：公告与普通帖共用此模型，靠 {@link Spec#type} 区分。
 *
 * <p>正文（HTML）直接存于 {@code spec.content}，写入前经服务层白名单净化；
 * 该字段不建索引，列表查询的 VO 装配阶段会剔除正文以控制响应体积。</p>
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

    @Data
    @Schema(name = "BbsPostSpec")
    public static class Spec {

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 200, description = "标题")
        private String title;

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 200,
                description = "永久链接别名，唯一（前台 /bbs/post/{slug}）")
        private String slug;

        @Schema(requiredMode = REQUIRED, description = "类型：公告 / 普通帖子")
        private PostType type = PostType.POST;

        @Schema(description = "所属分类的 metadata.name（公告可不属于任何分类=全站公告）")
        private String categoryName;

        @Schema(description = "摘要（列表展示用，留空自动从正文截取）", maxLength = 500)
        private String excerpt;

        @Schema(description = "正文 HTML（服务层净化后存储）")
        private String content;

        @Schema(description = "发布人 User 的 metadata.name")
        private String owner;

        @Schema(description = "是否置顶")
        private Boolean pinned = false;

        @Schema(description = "置顶排序优先级，值越大越靠前")
        private Integer pinPriority = 0;

        @Schema(requiredMode = REQUIRED, description = "状态：草稿 / 待审核 / 已发布 / 已驳回")
        private Phase phase = Phase.DRAFT;

        @Schema(description = "驳回原因（审核驳回时管理员填写；重新提交或发布后清空）",
                maxLength = 500)
        private String rejectReason;

        @Schema(description = "发布时间")
        private Instant publishTime;

        @Schema(description = "最后编辑时间（编辑后更新，用于展示『已编辑』）")
        private Instant lastEditTime;
    }

    /** 帖子类型：置顶公告 / 普通帖子。 */
    public enum PostType {
        /** 公告（前台展示在顶部公告区） */
        ANNOUNCEMENT,
        /** 普通帖子 */
        POST
    }

    /** 帖子状态：草稿 / 待审核 / 已发布 / 已驳回。 */
    public enum Phase {
        DRAFT,
        PENDING,
        PUBLISHED,
        REJECTED
    }
}
