package com.timxs.bbs.vo;

import com.timxs.bbs.extension.BbsCategory;
import lombok.Builder;
import lombok.Data;

/**
 * 分类 VO（前台 Finder / 公开 API / Console 共用）。
 *
 * <p>面向主题的自包含 DTO：展示所需的名称、颜色、图标全部内联，
 * 主题拿到即可直接渲染，无需再查字典。消费方应忽略未知字段。</p>
 *
 * @author Tim0x0
 */
@Data
@Builder
public class CategoryVo {

    /** 分类的 metadata.name（作为筛选参数使用） */
    private String name;

    private String displayName;

    /** 永久链接别名（前台 /bbs?category={slug}） */
    private String slug;

    private String description;

    /** Iconify 图标名（如 mdi:bullhorn） */
    private String icon;

    /** 图标内联 SVG（离线渲染用，currentColor 填充） */
    private String iconSvg;

    /** 主题色 HEX */
    private String color;

    private Integer priority;

    private Boolean enabled;

    /** 已发布帖子数（含公告） */
    private Long postCount;

    /** 由分类扩展构建（postCount 由调用方另行填充）。 */
    public static CategoryVo from(BbsCategory category) {
        var spec = category.getSpec();
        return CategoryVo.builder()
                .name(category.getMetadata().getName())
                .displayName(spec.getDisplayName())
                .slug(spec.getSlug())
                .description(spec.getDescription())
                .icon(spec.getIcon())
                .iconSvg(spec.getIconSvg())
                .color(spec.getColor())
                .priority(spec.getPriority() == null ? 0 : spec.getPriority())
                .enabled(!Boolean.FALSE.equals(spec.getEnabled()))
                .postCount(0L)
                .build();
    }
}
