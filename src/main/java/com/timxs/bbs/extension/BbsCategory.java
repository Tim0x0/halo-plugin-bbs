package com.timxs.bbs.extension;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * BBS 社区分类（如 技术分享 / 问答 / 公告），支持两级层级。
 *
 * <p>{@code spec.slug} 为自定义永久链接（前台 {@code /bbs?category={slug}}），唯一。
 * 图标与颜色：{@code icon} 为 Iconify 名，{@code iconSvg} 为选择器输出的离线 SVG——
 * 选色已烤进 {@code fill}，未选色则是 {@code currentColor} 随所在位置文字色（对齐官方
 * iconify 输入的行为，前台不二次上色）；{@code color} 是独立分类色（色点 / Tag /
 * 分类 Hero，新建按显示名预填），可空可透明，清空后前台不上色。</p>
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

    /**
     * 派生状态：由调和器维护，任何写入方都不该手改。
     *
     * <p>计数存的是<b>本分类直属</b>的数量；「含子分类的合计」由查询层在装配 VO 时
     * 按树聚合（纯内存计算，不触发查询）。</p>
     */
    private Status status = new Status();

    /** 派生状态（调和器维护）。 */
    @Data
    @Schema(name = "BbsCategoryStatus")
    public static class Status {

        @Schema(description = "前台访问地址")
        private String permalink;

        @Schema(description = "本分类直属的帖子总数（含未发布 / 待审核 / 已驳回）")
        private Integer postCount = 0;

        @Schema(description = "本分类直属的已发布帖子数（前台展示口径）")
        private Integer visiblePostCount = 0;
    }

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

        @Schema(description = "图标内联 SVG（Console 保存时取 Iconify 选择器输出；选色已烤进 "
                + "fill，未选色则为 currentColor 随文字色。前台离线渲染用，仅允许 <svg> 白名单内容）")
        private String iconSvg;

        @Schema(description = "分类色（HEX，含透明通道），独立于图标：色点 / Tag / 分类 Hero。"
                + "新建时按显示名哈希预填实色；清空后前台不上色",
                pattern = "^$|^#([a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})$")
        private String color;

        @Schema(description = "父分类的 metadata.name；空=一级分类。仅允许两级，"
                + "父分类必须是一级分类")
        private String parentName;

        @Schema(description = "封面图 URL（分类页 hero 背景）；子分类留空表示继承父分类封面，"
                + "均为空时前台以分类色铺底，分类色也空则中性底", maxLength = 1024)
        private String cover;

        @Schema(description = "排序优先级，值越小越靠前")
        private Integer priority = 0;

        @Schema(description = "是否启用（停用后前台不展示）")
        private Boolean enabled = true;

        @Schema(description = "本分类树（本级 + 全部子分类）下被置顶的帖是否出现在首页列表顶部。"
                + "仅一级分类可设，子分类该值无效（由调和器抹为 false）"
                + "（仅第 1 页；普通流去重，条数不封顶）")
        private Boolean pinToHome = false;

        @Schema(description = "本分类树（本级 + 全部子分类）的版主角色（Halo 角色的 metadata.name）。"
                + "留空 = 无分区版主，仅全站版主（bbs-moderate）与管理角色可管；"
                + "非空 = 额外授权持有其中任一角色的用户管理本分类树。"
                + "与 pinToHome 同为板块级配置：仅一级分类可设，子分类该值无效")
        private List<String> moderatorRoles;
    }
}
