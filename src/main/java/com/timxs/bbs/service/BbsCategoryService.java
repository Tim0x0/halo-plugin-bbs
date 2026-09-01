package com.timxs.bbs.service;

import com.timxs.bbs.extension.BbsCategory;
import com.timxs.bbs.util.ReactiveOptimisticUpdates;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 分类核心业务：层级移动与排序。
 *
 * <p><b>priority 语义 = 「同级序」</b>：同一父级下从 0 起连续编号，一级分类与子分类
 * 不共享数字空间。位置变更一律走 {@link #move}——前端只提交「移到谁的下面、排在谁之前」
 * 这一个意图，由服务端重排整个受影响的同级序列，避免前端逐个回写 priority 时
 * 部分失败留下的顺序空洞。</p>
 *
 * <p>层级封顶两级：目标父级必须是一级分类，且自身有子分类时不可再挂到别人下面。</p>
 *
 * @author Tim0x0
 */
@Component
public class BbsCategoryService {

    /** 排序写回的乐观锁重试次数（重排会连写多条，需从重新读取开始重试）。 */
    private static final int RETRY_TIMES = 5;

    private static final Comparator<BbsCategory> BY_PRIORITY = Comparator
            .comparingInt((BbsCategory c) -> c.getSpec().getPriority() == null
                    ? 0 : c.getSpec().getPriority())
            .thenComparing(c -> c.getMetadata().getCreationTimestamp(),
                    Comparator.nullsLast(Comparator.naturalOrder()));

    private final ReactiveExtensionClient client;
    private final BbsModerationScope moderationScope;

    public BbsCategoryService(ReactiveExtensionClient client,
            BbsModerationScope moderationScope) {
        this.client = client;
        this.moderationScope = moderationScope;
    }

    /**
     * 移动分类到指定位置（对齐官方 CategoryPositionRequest 的意图式语义）。
     *
     * @param name 被移动分类的 metadata.name
     * @param parentName 目标父级；空表示移到根（成为一级分类）
     * @param beforeName 目标位置的后继兄弟；空表示追加到同级末尾
     */
    public Mono<Void> move(String name, String parentName, String beforeName) {
        var targetParent = StringUtils.trimToNull(parentName);
        var before = StringUtils.trimToNull(beforeName);
        return listAll().flatMap(all -> {
            var self = all.stream()
                    .filter(c -> c.getMetadata().getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "分类不存在"));
            if (ExtensionUtil.isDeleted(self)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "分类正在删除");
            }
            validateHierarchy(all, name, targetParent);

            var oldParent = StringUtils.trimToNull(self.getSpec().getParentName());
            // 目标同级序列（已排除自身），按 beforeName 插回去再统一重编号
            var siblings = siblingsOf(all, targetParent, name);
            int index = before == null ? siblings.size() : indexOf(siblings, before);
            siblings.add(index, self);

            // name -> 新 priority；仅收集与当前值不同的，避免无谓写入
            var changes = new LinkedHashMap<String, Integer>();
            reindex(siblings, changes);
            if (!Objects.equals(oldParent, targetParent)) {
                // 换了父级：老父下留出的空洞也要补齐，保持同级序连续
                reindex(siblingsOf(all, oldParent, name), changes);
            }
            boolean parentChanged = !Objects.equals(oldParent, targetParent);
            if (changes.isEmpty() && !parentChanged) {
                return Mono.empty();
            }
            // 写前先撤销旧拓扑，避免移动窗口里继续按旧父子关系授权；写后再撤销一次，
            // 防止并发请求在重排过程中缓存了中间态。
            moderationScope.invalidateCategoryCache();
            return applyChanges(changes, name, targetParent, parentChanged)
                    .doFinally(ignored -> moderationScope.invalidateCategoryCache());
        });
    }

    /** 两级封顶校验：父级必须是一级分类；自身有子分类时不可再挂到别人下面；不可自挂自身。 */
    private static void validateHierarchy(List<BbsCategory> all, String name, String targetParent) {
        if (targetParent == null) {
            return;
        }
        if (targetParent.equals(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能将分类移动到自身之下");
        }
        var parent = all.stream()
                .filter(c -> c.getMetadata().getName().equals(targetParent)
                        && !ExtensionUtil.isDeleted(c))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "目标父分类不存在或正在删除"));
        if (StringUtils.isNotBlank(parent.getSpec().getParentName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "父级必须是一级分类（仅支持两级）");
        }
        boolean hasChildren = all.stream()
                .anyMatch(c -> name.equals(c.getSpec().getParentName()));
        if (hasChildren) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该分类下已有子分类，不能再移动到其他分类之下（仅支持两级）");
        }
    }

    /** 某父级下的同级列表（priority 升序），可排除指定成员。 */
    private static List<BbsCategory> siblingsOf(List<BbsCategory> all, String parent,
            String excludeName) {
        return all.stream()
                .filter(c -> !ExtensionUtil.isDeleted(c))
                .filter(c -> Objects.equals(
                        StringUtils.trimToNull(c.getSpec().getParentName()), parent))
                .filter(c -> excludeName == null
                        || !c.getMetadata().getName().equals(excludeName))
                .sorted(BY_PRIORITY)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** 找不到后继兄弟时按「追加到末尾」处理，不报错（前端树与服务端可能有短暂不一致）。 */
    private static int indexOf(List<BbsCategory> siblings, String beforeName) {
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getMetadata().getName().equals(beforeName)) {
                return i;
            }
        }
        return siblings.size();
    }

    /** 按列表顺序重编号为 0..n-1，只把与当前值不同的记入 changes。 */
    private static void reindex(List<BbsCategory> siblings, Map<String, Integer> changes) {
        for (int i = 0; i < siblings.size(); i++) {
            var category = siblings.get(i);
            var current = category.getSpec().getPriority();
            if (current == null || current != i) {
                changes.put(category.getMetadata().getName(), i);
            }
        }
    }

    /**
     * 串行写回（{@code concatMap} 而非并发）：重排会连写多条同级记录，
     * 并发写更易撞乐观锁，串行反而更快收敛。
     */
    private Mono<Void> applyChanges(Map<String, Integer> changes, String movedName,
            String targetParent, boolean parentChanged) {
        var names = new ArrayList<>(changes.keySet());
        if (parentChanged && !names.contains(movedName)) {
            // 只换父、priority 恰好不变时也必须写一次
            names.add(movedName);
        }
        return Flux.fromIterable(names)
                .concatMap(name -> updateWithRetry(name, category -> {
                    var priority = changes.get(name);
                    if (priority != null) {
                        category.getSpec().setPriority(priority);
                    }
                    if (name.equals(movedName)) {
                        category.getSpec().setParentName(targetParent);
                    }
                }))
                .then();
    }

    /**
     * 乐观锁重试：必须从重新 fetch 开始（拿到新 version 再改），
     * 直接重放旧对象永远失败。
     */
    private Mono<BbsCategory> updateWithRetry(String name, Consumer<BbsCategory> mutation) {
        return Mono.defer(() -> client.fetch(BbsCategory.class, name)
                        .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "分类不存在：" + name)))
                        .flatMap(category -> {
                            mutation.accept(category);
                            return client.update(category);
                        }))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_TIMES, Duration.ofMillis(50)));
    }

    private Mono<List<BbsCategory>> listAll() {
        return client.listAll(BbsCategory.class, ListOptions.builder().build(), Sort.unsorted())
                .collectList();
    }
}
