package com.timxs.bbs.vo;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.service.HtmlSanitizer;
import com.timxs.bbs.util.BbsColors;
import com.timxs.bbs.util.BbsUrls;
import java.time.Instant;
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

    /** 图标内联 SVG（离线渲染用；选色已烤进 fill，未选色为 currentColor 随文字色） */
    private String iconSvg;

    /** 分类色 HEX（含透明；空=不上色） */
    private String color;

    /** 父分类的 metadata.name；空=一级分类 */
    private String parentName;

    /** 封面图 URL（分类页 hero 背景；子分类已继承父分类封面，可空=用分类色，分类色也空则中性底） */
    private String cover;

    /** 前台访问地址（调和器写入 status.permalink；缺省按 slug 拼） */
    private String permalink;

    /** 父分类摘要（子分类才有；无嵌套 parent/children）——列表双徽章渲染用 */
    private CategoryVo parent;

    /** 子分类列表（仅一级分类填充；子分类为 null） */
    private List<CategoryVo> children;

    private Integer priority;

    private Boolean enabled;

    /** 本分类树（本级 + 子分类）下置顶帖是否出现在首页顶部；仅一级分类有意义 */
    private Boolean pinToHome;

    /** 本分类直属的已发布帖子数（含公告） */
    private Long postCount;

    /** 含子分类的已发布帖子总数（一级=自身+子分类合计；子分类=同 postCount） */
    private Long totalPostCount;

    /** 创建时间（metadata.creationTimestamp；Console 分类列表对齐官方显示绝对时间） */
    private Instant creationTimestamp;

    /** 由分类扩展构建（postCount / parent / children / cover 继承由调用方另行填充）。 */
    public static CategoryVo from(BbsCategory category) {
        var spec = category.getSpec();
        var metadata = category.getMetadata();
        return CategoryVo.builder()
                .name(metadata.getName())
                .displayName(spec.getDisplayName())
                .slug(spec.getSlug())
                .description(spec.getDescription())
                .icon(spec.getIcon())
                .iconSvg(HtmlSanitizer.cleanSvg(spec.getIconSvg()))
                .color(BbsColors.sanitize(spec.getColor()))
                .parentName(spec.getParentName())
                .cover(BbsUrls.sanitize(spec.getCover()))
                .permalink(permalinkOf(category))
                .priority(spec.getPriority() == null ? 0 : spec.getPriority())
                .enabled(!Boolean.FALSE.equals(spec.getEnabled()))
                .pinToHome(Boolean.TRUE.equals(spec.getPinToHome()))
                .postCount(0L)
                .totalPostCount(0L)
                .creationTimestamp(metadata.getCreationTimestamp())
                .build();
    }

    private static String permalinkOf(BbsCategory category) {
        var status = category.getStatus();
        if (status != null && org.apache.commons.lang3.StringUtils.isNotBlank(status.getPermalink())) {
            return status.getPermalink();
        }
        return BbsUrls.categoryPermalink(category.getSpec().getSlug());
    }
}
