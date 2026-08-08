package com.timxs.bbs.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * BBS 社区分类（如 技术分享 / 问答 / 公告），支持两级层级。
 *
 * <p>{@code spec.slug} 为自定义永久链接（前台 {@code /bbs?category={slug}}），唯一。
 * 图标与颜色来自 Console Iconify 选择器：{@code icon} 为图标名，
 * {@code color} 为选色（无图标时可为空），{@code iconSvg} 为 currentColor 离线 SVG。</p>
 *
 * <p>层级：{@code parentName} 为空即一级分类，否则为其子分类；层级封顶两级——
 * 查询层组树时对"父分类自身还有父"的脏数据按一级处理（不改数据，仅展示纠偏）。</p>
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

        @Schema(description = "图标（Iconify 名称，如 mdi:bullhorn）；空表示无图标", maxLength = 100)
        private String icon;

        @Schema(description = "图标内联 SVG（Console 保存时按 icon 解析写入，fill=currentColor；"
                + "前台离线渲染用，仅允许 <svg> 白名单内容）")
        private String iconSvg;

        @Schema(description = "分类色（HEX）；来自 Iconify 选色，无图标时为空。"
                + "用于图标着色、磁贴与前台分类标识",
                pattern = "^$|^#([a-fA-F0-9]{6}|[a-fA-F0-9]{3})$")
        private String color;

        @Schema(description = "父分类的 metadata.name；空=一级分类。仅允许两级，"
                + "父分类必须是一级分类")
        private String parentName;

        @Schema(description = "封面图 URL（分类页 hero 背景）；子分类留空表示继承父分类封面，"
                + "均为空时前台以分类色渐变兜底", maxLength = 1024)
        private String cover;

        @Schema(description = "排序优先级，值越小越靠前")
        private Integer priority = 0;

        @Schema(description = "是否启用（停用后前台不再展示）")
        private Boolean enabled = true;
    }
}
