package com.timxs.bbs.service;

import com.timxs.bbs.vo.BbsHeaderMenuItem;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.util.comparator.Comparators;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.Menu;
import run.halo.app.core.extension.MenuItem;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 把 Halo 菜单组展开成顶栏项列表。
 *
 * <p>层级存储新旧两代都要兼容：2.25 及以前父级在 {@code MenuItem.spec.children}、
 * 根由 {@code Menu.spec.menuItems} 指定；2.26 起迁移到 {@code spec.menuName} +
 * {@code spec.parent}（前两者废弃）。新字段经反射读取——基线 2.25 的 API 没有
 * 它们，字段不存在时返回 null 自动回退旧存法。插件模板不支持跨文件 fragment，
 * 树在这里拍平：顶层项带全量后代（深度优先，{@code depth} 标层级），模板单层
 * 循环渲染。缺菜单 / 读失败返回空列表，不挡前台。</p>
 */
@Component
@Slf4j
public class BbsHeaderMenuService {

    private static final int MAX_DEPTH = 6;

    /** 2.26 新增字段；2.25 的 MenuItemSpec 没有，解析为 null。 */
    private static final Method SPEC_GET_PARENT = specMethod("getParent");
    private static final Method SPEC_GET_MENU_NAME = specMethod("getMenuName");

    private final ReactiveExtensionClient client;

    public BbsHeaderMenuService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<List<BbsHeaderMenuItem>> load(String menuName) {
        if (StringUtils.isBlank(menuName)) {
            return Mono.just(List.of());
        }
        return client.fetch(Menu.class, menuName.strip())
                .flatMap(this::flatten)
                .defaultIfEmpty(List.of())
                .onErrorResume(error -> {
                    log.warn("读取顶栏菜单失败：{}", menuName, error);
                    return Mono.just(List.of());
                });
    }

    private Mono<List<BbsHeaderMenuItem>> flatten(Menu menu) {
        return client.listAll(MenuItem.class, ListOptions.builder().build(), Sort.unsorted())
                .collectList()
                .map(items -> expand(menu, items));
    }

    private static List<BbsHeaderMenuItem> expand(Menu menu, List<MenuItem> items) {
        var menuName = menu.getMetadata().getName();
        Map<String, MenuItem> byName = new HashMap<>();
        for (var item : items) {
            if (item.getMetadata() != null && item.getMetadata().getName() != null) {
                byName.put(item.getMetadata().getName(), item);
            }
        }

        // 父级关系：优先新版 spec.parent，回退旧版父项的 children 集合
        Map<String, String> parentOf = new HashMap<>();
        for (var item : items) {
            var parent = invokeString(SPEC_GET_PARENT, item.getSpec());
            if (StringUtils.isNotBlank(parent) && byName.containsKey(parent)) {
                parentOf.put(item.getMetadata().getName(), parent);
            }
        }
        for (var item : items) {
            var children = item.getSpec() == null ? null : item.getSpec().getChildren();
            if (children == null) {
                continue;
            }
            for (var child : children) {
                if (byName.containsKey(child)) {
                    parentOf.putIfAbsent(child, item.getMetadata().getName());
                }
            }
        }

        Map<String, List<MenuItem>> childrenOf = new HashMap<>();
        for (var entry : parentOf.entrySet()) {
            var child = byName.get(entry.getKey());
            var parent = byName.get(entry.getValue());
            if (child == null || parent == null) {
                continue;
            }
            childrenOf.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(child);
        }
        childrenOf.values().forEach(list -> list.sort(itemComparator()));

        // 成员 = 旧版声明闭包 + 新版 menuName 命中。注意旧版 Console 把**全部成员**
        // （含子项）都存进 Menu.spec.menuItems、不只是根（官方迁移同款口径），
        // 故声明项要连带子树闭包收进成员，再按「不是其他成员的子项」筛根，
        // 否则子项会同时出现在顶层与下拉里，或者新旧混存时整组丢失。
        Set<String> members = new LinkedHashSet<>();
        var declared = menu.getSpec() == null ? null : menu.getSpec().getMenuItems();
        if (declared != null) {
            for (var name : declared) {
                if (byName.containsKey(name)) {
                    collectMember(name, childrenOf, members);
                }
            }
        }
        for (var item : items) {
            if (menuName.equals(invokeString(SPEC_GET_MENU_NAME, item.getSpec()))) {
                members.add(item.getMetadata().getName());
            }
        }

        // 顶层排序：声明过的根保持菜单配置顺序（旧版 / 迁移数据）；
        // 纯新版（无声明）的根按官方口径排（priority / 创建时间 / 名称），
        // 否则顺序跟着 listAll 返回漂，刷新页面菜单会换序
        var declaredSet = declared == null ? Set.<String>of()
                : declared.stream().filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var rootNames = new ArrayList<String>();
        var modernRoots = new ArrayList<String>();
        for (var name : members) {
            var parent = parentOf.get(name);
            if (parent != null && !parent.equals(name) && members.contains(parent)) {
                continue;
            }
            if (declaredSet.contains(name)) {
                rootNames.add(name);
            } else {
                modernRoots.add(name);
            }
        }
        modernRoots.sort(Comparator.comparing(byName::get, itemComparator()));
        rootNames.addAll(modernRoots);

        var result = new ArrayList<BbsHeaderMenuItem>();
        for (var name : rootNames) {
            var root = byName.get(name);
            var node = toItem(root, 0);
            if (node == null) {
                continue;
            }
            var descendants = new ArrayList<BbsHeaderMenuItem>();
            var visiting = new HashSet<String>();
            visiting.add(name);
            collectDescendants(name, childrenOf, visiting, 1, descendants);
            result.add(node.toBuilder().children(descendants).build());
        }
        return result;
    }

    /** 成员闭包：声明项本身 + 其全部后代。 */
    private static void collectMember(String name, Map<String, List<MenuItem>> childrenOf,
            Set<String> members) {
        if (!members.add(name)) {
            return;
        }
        for (var child : childrenOf.getOrDefault(name, List.of())) {
            collectMember(child.getMetadata().getName(), childrenOf, members);
        }
    }

    /** 深度优先收集全部后代；环引用 / 超深直接丢弃。 */
    private static void collectDescendants(String parentName,
            Map<String, List<MenuItem>> childrenOf, Set<String> visiting, int depth,
            List<BbsHeaderMenuItem> out) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (var child : childrenOf.getOrDefault(parentName, List.of())) {
            var name = child.getMetadata().getName();
            if (!visiting.add(name)) {
                continue;
            }
            var node = toItem(child, depth);
            if (node != null) {
                out.add(node);
                collectDescendants(name, childrenOf, visiting, depth + 1, out);
            }
            visiting.remove(name);
        }
    }

    private static BbsHeaderMenuItem toItem(MenuItem item, int depth) {
        var name = item.getMetadata() == null ? null : item.getMetadata().getName();
        if (StringUtils.isBlank(name) || item.getSpec() == null) {
            return null;
        }
        var spec = item.getSpec();
        var status = item.getStatus();
        var display = status != null && StringUtils.isNotBlank(status.getDisplayName())
                ? status.getDisplayName() : spec.getDisplayName();
        if (StringUtils.isBlank(display)) {
            return null;
        }
        var href = status != null && StringUtils.isNotBlank(status.getHref())
                ? status.getHref() : spec.getHref();
        var target = spec.getTarget() == null ? null : spec.getTarget().getValue();
        return BbsHeaderMenuItem.builder()
                .displayName(display.strip())
                .href(StringUtils.trimToNull(href))
                .target(StringUtils.trimToNull(target))
                .depth(depth)
                .build();
    }

    private static Method specMethod(String name) {
        try {
            return MenuItem.MenuItemSpec.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            // 2.25 API 没有该字段；运行时按实际核心版本再解析（见类注释）
            return null;
        }
    }

    private static String invokeString(Method method, Object target) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return (String) method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Comparator<MenuItem> itemComparator() {
        return Comparator
                .comparing((MenuItem item) -> item.getSpec() == null
                        || item.getSpec().getPriority() == null
                        ? 0 : item.getSpec().getPriority())
                .thenComparing(item -> item.getMetadata() == null
                        ? null : item.getMetadata().getCreationTimestamp(),
                        Comparators.nullsLow())
                .thenComparing(item -> item.getMetadata() == null
                        ? "" : Objects.toString(item.getMetadata().getName(), ""));
    }
}
