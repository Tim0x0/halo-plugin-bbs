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

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200, description = "标题")
    private String title;

    @Schema(description = "别名，留空自动生成", maxLength = 200)
    private String slug;

    @Schema(description = "类型（仅管理端可指定；用户中心固定为 POST）")
    private BbsPost.PostType type;

    @Schema(description = "所属分类的 metadata.name")
    private String categoryName;

    @Schema(description = "摘要，留空自动从正文截取", maxLength = 500)
    private String excerpt;

    @Schema(description = "正文 HTML（服务端会做白名单净化）")
    private String content;

    @Schema(description = "是否置顶（仅管理端）")
    private Boolean pinned;

    @Schema(description = "置顶排序优先级（仅管理端）")
    private Integer pinPriority;
}
