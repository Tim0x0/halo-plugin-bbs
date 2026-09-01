package com.timxs.bbs.service;

/**
 * 角色模板名（extensions/roleTemplate.yaml 声明）的唯一字符串出处。
 *
 * <p>两处消费方故意不同，勿互相对齐：{@code BbsModerationScope} 用直接绑定判定
 * 管辖（绝不能展开依赖）；{@code BbsRouter#hasAdminPermission} 故意展开依赖，
 * 因为它只决定前台后台入口显隐，判宽无害。</p>
 *
 * @author Tim0x0
 */
public final class BbsRoles {

    /** Halo 内置超级管理员。 */
    public static final String SUPER = "super-role";

    /** BBS 管理（全量）。 */
    public static final String MANAGE = "bbs-manage";

    /** BBS 版主（全站级；分区版主靠自建角色绑定）。 */
    public static final String MODERATE = "bbs-moderate";

    /** BBS 查看（官方「查看」对应物，只读通看）。 */
    public static final String VIEW = "bbs-view";

    private BbsRoles() {
    }
}
