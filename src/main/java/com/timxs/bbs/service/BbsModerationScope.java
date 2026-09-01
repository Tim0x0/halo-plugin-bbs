package com.timxs.bbs.service;

import com.timxs.bbs.extension.BbsCategory;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 版主管辖范围：回答「这个用户能管哪些分类」。
 *
 * <p>分两级主体：<b>全站</b>（直接持有 {@code bbs-view} / {@code bbs-moderate} /
 * {@code bbs-manage} / 超管）不受分类限制——其中 {@code bbs-view} 只决定「能看见
 * 哪些分类」，写接口仍靠 RBAC；<b>分区版主</b>只能管「把自己持有的角色列进
 * {@code moderatorRoles}」的一级分类及其全部子分类。对齐 Discourse 的
 * {@code is_staff? || is_category_group_moderator?(category)} —— 二者是「或」关系，
 * 全站主体并存，不因引入分区而失效。</p>
 *
 * <p><b>判定必须用直接绑定的角色，绝不能展开依赖链。</b>分区版主的角色是靠
 * {@code dependencies: ["bbs-moderate"]} 拿到接口调用权限的，一旦展开，
 * 每个分区版主都会带上 {@code bbs-moderate} 而被误判成全站版主，分区限制全线失效。
 * 依赖展开只用于回答「能不能调这个接口」，那是 Halo RBAC 层的事，业务层不碰。
 * （注意与 {@code BbsRouter#hasAdminPermission} 的区别：那里<b>故意</b>展开依赖，
 * 因为它只决定前台要不要显示后台入口，判宽无害。）</p>
 *
 * <p>管辖配置是<b>板块级</b>的：仅一级分类可设，子分类跟随父级——与 {@code pinToHome}
 * 同一套心智（见 {@link BbsCategory.Spec#getPinToHome()}）。</p>
 *
 * @author Tim0x0
 */
@Slf4j
@Component
public class BbsModerationScope {

    /**
     * 全站主体：直接持有即不加分类过滤。
     *
     * <p>{@code bbs-view} 是官方「查看」对应物，只读通看；版主 / 管理 / 超管另有写权限。
     * 分区版主靠自建角色绑定，{@code getRolesByUsername} 不会展开出这些名字。</p>
     */
    private static final Set<String> GLOBAL_ROLES =
            Set.of(BbsRoles.SUPER, BbsRoles.MANAGE, BbsRoles.MODERATE, BbsRoles.VIEW);

    private final ReactiveExtensionClient client;
    private final RoleService roleService;
    private volatile Mono<List<CategoryScopeConfig>> categoryTopology;

    public BbsModerationScope(ReactiveExtensionClient client, RoleService roleService) {
        this.client = client;
        this.roleService = roleService;
    }

    /**
     * 管辖范围。
     *
     * @param global true = 全站通管，{@code categoryNames} 无意义
     * @param categoryNames 管辖的分类名（已含子分类）；global 为 true 时为空集
     */
    public record Scope(boolean global, Set<String> categoryNames) {

        private static final Scope GLOBAL = new Scope(true, Set.of());
        private static final Scope NONE = new Scope(false, Set.of());

        /** 是否管得着这条分类；分类为空（无归属帖）仅全站主体可管。 */
        public boolean covers(String categoryName) {
            if (global) {
                return true;
            }
            return StringUtils.isNotBlank(categoryName) && categoryNames.contains(categoryName);
        }

        /** 一个分类都管不着（分区版主未被任何分类授权时的空转状态）。 */
        public boolean isEmpty() {
            return !global && categoryNames.isEmpty();
        }
    }

    /**
     * 解析用户的管辖范围。
     *
     * @param username 登录名；空 = 无任何管辖
     */
    public Mono<Scope> resolve(String username) {
        if (StringUtils.isBlank(username)) {
            return Mono.just(Scope.NONE);
        }
        return roleService.getRolesByUsername(username)
                .collect(Collectors.toSet())
                .flatMap(roles -> {
                    if (roles.stream().anyMatch(GLOBAL_ROLES::contains)) {
                        return Mono.just(Scope.GLOBAL);
                    }
                    // 打出真实角色名：若非空却没命中 GLOBAL，说明绑定/配置问题，而非解析失败
                    log.info("BBS scope for {} is non-global, roles={}", username, roles);
                    return scopedBy(roles);
                })
                .onErrorResume(e -> {
                    // 查角色失败时收紧而非放行：宁可少管，不可越权。
                    // error 级别 + 完整堆栈：否则「瞬态解析失败」会被误判成「无权限」，
                    // 列表将静默变空且不留痕迹，极难排查，故必须暴露。
                    log.error("解析 BBS 版主管辖范围失败，按无管辖处理（用户 {} 将看到空列表）",
                            username, e);
                    return Mono.just(Scope.NONE);
                });
    }

    /** 按分类配置算管辖集合：命中的一级分类 + 其全部子分类。 */
    private Mono<Scope> scopedBy(Set<String> roles) {
        return categoryTopology()
                .map(all -> {
                    var owned = all.stream()
                            // 只认一级分类的配置：子分类自身的 moderatorRoles 一律不生效，
                            // 否则「板块级授权」会被下放到叶子层
                            .filter(c -> StringUtils.isBlank(c.parentName()))
                            .filter(c -> intersects(c.moderatorRoles(), roles))
                            .map(CategoryScopeConfig::name)
                            .collect(Collectors.toSet());
                    if (owned.isEmpty()) {
                        return Scope.NONE;
                    }
                    var names = new HashSet<>(owned);
                    all.stream()
                            .filter(c -> owned.contains(c.parentName()))
                            .forEach(c -> names.add(c.name()));
                    return new Scope(false, Set.copyOf(names));
                });
    }

    /**
     * 分类授权拓扑是站点级小数据，按变更事件失效；角色绑定仍在每次 resolve 时实时查询。
     * 缓存不可变投影而非 Extension 实例，避免共享可变对象。
     */
    private Mono<List<CategoryScopeConfig>> categoryTopology() {
        var cached = categoryTopology;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = categoryTopology;
            if (cached == null) {
                cached = client.listAll(BbsCategory.class, ListOptions.builder().build(),
                                Sort.unsorted())
                        // 删除流程中的分类不应继续授予管辖权；重建拓扑时安全收紧。
                        .filter(category -> !ExtensionUtil.isDeleted(category))
                        .map(category -> new CategoryScopeConfig(
                                category.getMetadata().getName(),
                                category.getSpec().getParentName(),
                                category.getSpec().getModeratorRoles() == null
                                        ? List.of()
                                        : category.getSpec().getModeratorRoles().stream()
                                                .filter(StringUtils::isNotBlank)
                                                .toList()))
                        .collectList()
                        .map(List::copyOf)
                        .doOnError(ignored -> invalidateCategoryCache())
                        .cache();
                categoryTopology = cached;
            }
        }
        return cached;
    }

    /** 分类创建、修改、删除或层级移动后调用，使下一次解析重建拓扑。 */
    public void invalidateCategoryCache() {
        categoryTopology = null;
    }

    private record CategoryScopeConfig(String name, String parentName,
            List<String> moderatorRoles) {
    }

    private static boolean intersects(List<String> configured, Collection<String> roles) {
        return configured != null && configured.stream()
                .anyMatch(r -> StringUtils.isNotBlank(r) && roles.contains(r));
    }

    /**
     * 管辖范围内的分类名（供查询层拼 {@code in} 条件）。
     * 全站主体返回空 Optional，表示不加过滤。
     */
    public Mono<Optional<Set<String>>> visibleCategoryNames(String username) {
        return resolve(username)
                .map(scope -> scope.global()
                        ? Optional.<Set<String>>empty()
                        : Optional.of(scope.categoryNames()));
    }

    /** 分类是否配了版主角色（调和器纠偏子分类配置时用）。 */
    public static boolean hasModerators(BbsCategory category) {
        var roles = category.getSpec().getModeratorRoles();
        return roles != null && roles.stream().anyMatch(StringUtils::isNotBlank);
    }
}
