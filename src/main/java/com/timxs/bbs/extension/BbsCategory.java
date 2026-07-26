package com.timxs.bbs.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * BBS 社区分类（如 CS1.6 / DNF / 插件专区）。
 *
 * <p>{@code spec.slug} 为自定义永久链接（前台 {@code /bbs?category={slug}}），
 * 唯一；{@code spec.icon} 为 emoji 或短文本，前台与 Console 均以彩色磁贴呈现。</p>
 *
 * @author Tim0x0
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@GVK(
        group = "bbs.timxs.com",
        version = "v1alpha1",
        kind = "BbsCategory",
        plural = "bbscategories",
        singular = "bbscategory")
public class BbsCategory extends AbstractExtension {

    @Schema(requiredMode = REQUIRED)
    private Spec spec = new Spec();

    @Data
    @Schema(name = "BbsCategorySpec")
    public static class Spec {

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 100, description = "分类名称")
        private String displayName;

        @Schema(requiredMode = REQUIRED, minLength = 1, maxLength = 100,
                description = "永久链接别名，唯一")
        private String slug;

        @Schema(description = "分类描述", maxLength = 500)
        private String description;

        @Schema(description = "图标（Iconify 名称，如 mdi:bullhorn）", maxLength = 100)
        private String icon;

        @Schema(description = "图标内联 SVG（Console 保存时按 icon 解析写入；前台离线渲染用，"
                + "仅允许 <svg> 白名单内容）")
        private String iconSvg;

        @Schema(description = "主题色（HEX，如 #6366f1）",
                pattern = "^#([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$")
        private String color;

        @Schema(description = "排序优先级，值越小越靠前")
        private Integer priority = 0;

        @Schema(description = "是否启用（停用后前台不再展示）")
        private Boolean enabled = true;
    }
}
