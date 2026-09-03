package com.timxs.bbs.vo;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 前台顶栏菜单项。服务层已把 Halo 菜单树拍平：顶层项带 {@code children}
 * （其后代按深度优先展开，{@code depth} 从 1 起），模板单层循环即可渲染
 * 任意层级——插件模板不支持跨文件 fragment，递归交回服务端做。
 */
@Data
@Builder(toBuilder = true)
public class BbsHeaderMenuItem {

    private String displayName;

    /** 解析后的链接（优先 status.href，否则 spec.href）；空则该项不可点 */
    private String href;

    /** HTML target，如 {@code _blank}；默认当前页 */
    private String target;

    /** 相对所属顶层项的深度；顶层项自身恒为 0 */
    @Builder.Default
    private int depth = 0;

    /** 顶层项拍平后的全部后代（深度优先）；顶层项以外恒为空 */
    @Builder.Default
    private List<BbsHeaderMenuItem> children = List.of();

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}
