package com.timxs.bbs.vo;

import com.timxs.bbs.extension.BbsCategory;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 分类 VO（前台 Finder / 公开 API / Console 共用）。
 *
 * <p>面向主题的自包含 DTO：展示所需的名称、颜色、图标全部内联，
 * 主题拿到即可直接渲染，无需再查字典。消费方应忽略未知字段。</p>
 *
 * <p>层级：{@code parent} 为父分类摘要（仅基本字段，无嵌套层级——列表双徽章渲染用），
 * {@code children} 为子分类列表（仅一级分类填充——前台树形导航用）；
 * {@code cover} 已做继承解析（子分类留空时取父分类封面）。</p>
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

    /** Iconify 图标名（如 mdi:bullhorn）；可空 */
    private String icon;

    /** 图标内联 SVG（离线渲染用，currentColor 填充） */
    private String iconSvg;

    /** 分类色 HEX（来自 Iconify 选色）；无图标时可空 */
    private String color;

    /** 父分类的 metadata.name；空=一级分类 */
    private String parentName;

    /** 封面图 URL（分类页 hero 背景；子分类已继承父分类封面，可空=用分类色渐变兜底） */
    private String cover;

    /** 父分类摘要（子分类才有；无嵌套 parent/children）——列表双徽章渲染用 */
    private CategoryVo parent;

    /** 子分类列表（仅一级分类填充；子分类为 null） */
    private List<CategoryVo> children;

    private Integer priority;

    private Boolean enabled;

    /** 本分类直属的已发布帖子数（含公告） */
    private Long postCount;

    /** 含子分类的已发布帖子总数（一级=自身+子分类合计；子分类=同 postCount） */
    private Long totalPostCount;

    /** 由分类扩展构建（postCount / parent / children / cover 继承由调用方另行填充）。 */
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
                .parentName(spec.getParentName())
                .cover(spec.getCover())
                .priority(spec.getPriority() == null ? 0 : spec.getPriority())
                .enabled(!Boolean.FALSE.equals(spec.getEnabled()))
                .postCount(0L)
                .totalPostCount(0L)
                .build();
    }
}
