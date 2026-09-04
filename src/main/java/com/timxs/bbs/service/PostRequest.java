package com.timxs.bbs.service;

import com.timxs.bbs.extension.BbsPost;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 帖子创建 / 更新请求体（Console 与 UC 共用；UC 场景忽略管理字段）。
 *
 * @author Tim0x0
 */
@Data
@Schema(name = "BbsPostRequest")
public class PostRequest {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200,
            description = "标题（草稿为空时服务端补为“未命名”）")
    private String title;

    @Schema(description = "别名，留空自动生成", maxLength = 200)
    private String slug;

    @Schema(description = "类型（用户中心可选 POST / QUESTION；公告仅管理端可指定，"
            + "且用户侧编辑公告时类型保持不变）")
    private BbsPost.PostType type;

    @Schema(description = "所属分类的 metadata.name（草稿可空，正式提交必填）")
    private String categoryName;

    @Schema(description = "手工摘要原文（autoExcerpt=false 时生效）", maxLength = 500)
    private String excerpt;

    @Schema(description = "摘要是否自动从正文截取；null 表示不修改（新建默认 true）")
    private Boolean autoExcerpt;

    @Schema(description = "正文 HTML（服务端会做白名单净化）。创建与「元数据+正文」一次性"
            + "保存时使用，对齐官方 PostRequest；只改正文请走 /content 通道，那条路支持"
            + "并发冲突检测")
    private String content;

    @Schema(description = "提交审核时给审核人的附言（仅提交路径消费，记入 SUBMITTED "
            + "审核记录；不落在帖子本体上）", maxLength = 500)
    private String submitNote;

    @Schema(description = "是否置顶（仅管理端）")
    private Boolean pinned;

    @Schema(description = "置顶排序优先级（仅管理端）")
    private Integer pinPriority;
}
