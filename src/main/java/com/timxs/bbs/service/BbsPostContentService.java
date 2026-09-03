package com.timxs.bbs.service;

import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;

import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.util.ReactiveOptimisticUpdates;
import com.timxs.bbs.vo.BbsContentVo;
import com.timxs.bbs.vo.BbsSnapshotDto;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

/**
 * BbsPost 的 Halo Snapshot 适配层，语义逐条对齐官方 {@code AbstractContentService} +
 * {@code PostServiceImpl}。
 *
 * <p><b>快照只存内容</b>：raw / content / rawType 三元组，基线存全量（{@code keep-raw}），
 * 其余相对基线存行差异。标题、分类等业务字段活在帖子本体上，不入版本链。</p>
 *
 * <p><b>与审核完全解耦</b>：保存、恢复、删除只操作快照链与三指针，不读也不写 phase /
 * draft；审核只作用于发布动作（见 {@code BbsPostService}）。</p>
 */
@Component
public class BbsPostContentService {

    public static final String RAW_TYPE = "html";
    public static final String INITIALIZING_ANNO = "bbs.timxs.com/snapshot-initializing";

    /**
     * 未发布帖删掉 head 回退到基线后，下次保存必须分叉。
     *
     * <p>基线是其它快照的 diff 基准，原地改写会让所有相对它存差异的历史版本重建出错。
     * 官方删 head 固定回退到 release、未发布时留空，撞不到这条路；BBS 回退到基线更稳，
     * 因此需要这个标记兜底。与审核无关。</p>
     */
    private static final String FORCE_HEAD_FORK_ANNO = "bbs.timxs.com/force-head-fork";

    /** 官方 AbstractContentService 的更新重试次数。 */
    private static final int RETRY_UPDATE = 5;
    /** 官方 PostServiceImpl 发布 / 恢复 / 删 head 回退的重试次数。 */
    private static final int RETRY_PUBLISH = 8;

    private final ReactiveExtensionClient client;

    public BbsPostContentService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /** 新建 BbsPost 后创建首个完整 Snapshot，并写回 base/head/release 指针。 */
    public Mono<BbsPost> initialize(BbsPost created, String requestedContent, String actor,
            boolean published) {
        var content = clean(requestedContent);
        var snapshot = newSnapshot(created, UUID.randomUUID().toString(), null,
                content, actor, true, null);
        return client.create(snapshot)
                .flatMap(base -> updatePostWithRetry(created.getMetadata().getName(), post -> {
                    var name = base.getMetadata().getName();
                    post.getSpec().setBaseSnapshot(name);
                    post.getSpec().setHeadSnapshot(name);
                    post.getSpec().setReleaseSnapshot(published ? name : null);
                    statusOf(post).setHeadSnapshotVersion(base.getMetadata().getVersion());
                    clearStagedContent(post);
                    MetadataUtil.nullSafeAnnotations(post).remove(INITIALIZING_ANNO);
                }).onErrorResume(error -> client.delete(base)
                        .onErrorResume(ignored -> Mono.empty())
                        .then(Mono.error(error))));
    }

    /** 无并发检测的保存（服务内部调用，如撤稿前的指针补齐后写入）。 */
    public Mono<BbsPost> prepareHead(BbsPost post, String requestedContent, String actor) {
        return prepareHead(post, requestedContent, actor, null);
    }

    /**
     * 保存内容到 head，判定逐条对齐官方 {@code AbstractContentService.draftContent}，
     * 另加一条「无改动不落库」：
     *
     * <ul>
     *   <li>内容与当前 head 重建结果一致 → 什么都不写（删 head 的强制分叉除外）；</li>
     *   <li>{@code expectedVersion} 与当前 head 的 {@code metadata.version} 不一致
     *       （另一个标签页已经改过）→ 新建快照，两个版本都留在历史里；</li>
     *   <li>{@code head == release}（发布后首次保存）→ 新建快照，发布版天然留档；</li>
     *   <li>否则原地更新 head。</li>
     * </ul>
     *
     * <p>相等性判断优先于分叉判断：官方在 {@code head == release} 时无条件分叉，
     * 取消发布后重新发布（编辑器发布会先保存一次）会凭空多出一条内容完全相同的
     * 版本记录，BBS 的版本链只收录真实改动。</p>
     *
     * <p>返回的帖子仍由调用方在同一次乐观锁更新中落库。{@code requestedContent} 为 null
     * 表示这次只改元数据——不碰快照链，只补齐指针，否则「只改标题」也会刷新 head 的修改
     * 时间，发布后首次改标题甚至会分叉出一个内容完全相同的空版本。</p>
     */
    public Mono<BbsPost> prepareHead(BbsPost post, String requestedContent, String actor,
            Long expectedVersion) {
        if (requestedContent == null) {
            return ensurePointers(post, actor);
        }
        return ensurePointers(post, actor).flatMap(initialized -> {
            var spec = initialized.getSpec();
            return getOwnedSnapshot(initialized, spec.getHeadSnapshot())
                    .flatMap(currentHead -> {
                        var content = clean(requestedContent);
                        boolean forcedFork = Boolean.parseBoolean(
                                MetadataUtil.nullSafeAnnotations(initialized)
                                        .get(FORCE_HEAD_FORK_ANNO));
                        // 删 head 回退到基线后的首次保存必须分叉（基线不可原地改写），
                        // 内容是否相同没有意义。
                        if (forcedFork) {
                            return forkHead(initialized, content, actor);
                        }
                        return contentEqualsHead(initialized, currentHead, content)
                                .flatMap(same -> {
                                    if (same) {
                                        return Mono.just(initialized);
                                    }
                                    boolean versionConflict = expectedVersion != null
                                            && !Objects.equals(expectedVersion,
                                                    currentHead.getMetadata().getVersion());
                                    boolean releaseFork =
                                            StringUtils.isNotBlank(spec.getReleaseSnapshot())
                                            && Objects.equals(spec.getHeadSnapshot(),
                                                    spec.getReleaseSnapshot());
                                    if (releaseFork || versionConflict) {
                                        return forkHead(initialized, content, actor);
                                    }
                                    return updateHeadWithRetry(initialized, content, actor,
                                                    currentHead.getMetadata().getVersion())
                                            .doOnNext(head -> attachHead(initialized, head))
                                            .thenReturn(initialized);
                                });
                    });
        });
    }

    /** 分叉新快照成为 head（发布版留档 / 并发冲突保留双方 共用）。 */
    private Mono<BbsPost> forkHead(BbsPost post, String content, String actor) {
        return fetchBase(post)
                .map(base -> newSnapshot(post, UUID.randomUUID().toString(),
                        post.getSpec().getHeadSnapshot(), content, actor, false, base))
                .flatMap(client::create)
                .doOnNext(head -> attachHead(post, head))
                .thenReturn(post);
    }

    /**
     * 提交内容与当前 head 重建内容是否一致。重建失败按「不同」处理——
     * 让后续写入路径顺带修复损坏的快照链。
     */
    private Mono<Boolean> contentEqualsHead(BbsPost post, Snapshot head, String content) {
        return fetchBase(post).map(base -> {
            try {
                var current = clean(ContentWrapper.patchSnapshot(head, base).getContent());
                return Objects.equals(current, content);
            } catch (RuntimeException ignored) {
                return false;
            }
        });
    }

    /** 只补齐存量指针，不创建新的编辑版本；发布/撤稿等状态操作使用。 */
    public Mono<BbsPost> ensureInitialized(BbsPost post, String actor) {
        return ensurePointers(post, actor);
    }

    /**
     * 恢复历史版本（官方 {@code revertToSpecifiedSnapshot} 语义）：用旧快照重建出的内容
     * <b>新建一个快照</b>成为 head，历史链只增不减，旧快照永不改写。
     *
     * <p>官方在这之后无条件重新发布；BBS 的发布/审核分流由调用方
     * （{@code BbsPostService}）决定，本方法只负责换 head——未发布草稿因此不会被误发布。
     * 标题、分类等业务字段不入版本链，恢复也就不回滚它们。</p>
     */
    public Mono<BbsPost> prepareRestore(BbsPost post, String snapshotName, String actor) {
        return ensurePointers(post, actor)
                .flatMap(initialized -> {
                    if (Objects.equals(snapshotName, initialized.getSpec().getHeadSnapshot())) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "该快照已经是当前工作版本"));
                    }
                    return getContent(initialized, snapshotName)
                            .flatMap(restored -> fetchBase(initialized).flatMap(base -> {
                                var snapshot = newSnapshot(initialized,
                                        UUID.randomUUID().toString(),
                                        initialized.getSpec().getHeadSnapshot(),
                                        clean(restored.getContent()), actor, false, base);
                                return client.create(snapshot).doOnNext(head -> {
                                    attachHead(initialized, head);
                                    clearStagedContent(initialized);
                                }).thenReturn(initialized);
                            }));
                })
                .thenReturn(post);
    }

    /** 读取前台 release 正文；未发布内容返回空 Mono。 */
    public Mono<ContentWrapper> getReleaseContent(BbsPost post) {
        if (StringUtils.isBlank(post.getSpec().getReleaseSnapshot())) {
            if (StringUtils.isBlank(post.getSpec().getBaseSnapshot())) {
                return Mono.just(emptyContent());
            }
            return Mono.empty();
        }
        return getContent(post, post.getSpec().getReleaseSnapshot());
    }

    /**
     * 重建指定快照的内容并附带该快照的乐观锁版本（官方
     * {@code GET /posts/{name}/content?snapshotName=}；快照名为空时取 head，缺省行为一致）。
     *
     * <p>官方的 {@code Content} 不带版本，官方前端另调核心 {@code snapshots} CRD 端点去读
     * {@code metadata.version} 作并发检测基准。BBS 不走那条路——那需要给用户授予核心
     * snapshots 资源的读权限，与「快照不设独立权限、可见性跟着帖子走」冲突。版本号随内容
     * 一起下发，前端保存时原样回传即可。</p>
     */
    public Mono<BbsContentVo> getContentDetail(BbsPost post, String snapshotName) {
        var target = StringUtils.defaultIfBlank(snapshotName, post.getSpec().getHeadSnapshot());
        return withVersion(post, getContent(post, target));
    }

    /** 发布版内容；未发布时返回空内容而不是空 Mono，端点无需再分支。 */
    public Mono<BbsContentVo> getReleaseContentDetail(BbsPost post) {
        return withVersion(post, getReleaseContent(post).defaultIfEmpty(emptyContent()));
    }

    private Mono<BbsContentVo> withVersion(BbsPost post, Mono<ContentWrapper> content) {
        return content.flatMap(wrapper -> {
            if (StringUtils.isBlank(wrapper.getSnapshotName())) {
                return Mono.just(BbsContentVo.from(wrapper, null));
            }
            return getOwnedSnapshot(post, wrapper.getSnapshotName())
                    .map(snapshot -> BbsContentVo.from(wrapper,
                            snapshot.getMetadata().getVersion()));
        });
    }

    /**
     * 调和器使用：发布态 release 指针丢失或指向损坏快照时，从安全回退项中选择一个仍可
     * 归属于该帖的 Snapshot，原子修复 release 指针。
     */
    public Mono<BbsPost> repairReleasePointer(String postName, String preferredSnapshot,
            String invalidSnapshot) {
        return Mono.defer(() -> client.get(BbsPost.class, postName).flatMap(post -> {
            if (post.getSpec().getPhase() != BbsPost.Phase.PUBLISHED) {
                return Mono.just(post);
            }
            var spec = post.getSpec();
            Set<String> candidates = new LinkedHashSet<>();
            addCandidate(candidates, preferredSnapshot, invalidSnapshot);
            // 有未提交改动时不能把 head 暴露到前台；优先回到 base。
            if (spec.getDraft() == null) {
                addCandidate(candidates, spec.getHeadSnapshot(), invalidSnapshot);
                addCandidate(candidates, spec.getBaseSnapshot(), invalidSnapshot);
            } else {
                addCandidate(candidates, spec.getBaseSnapshot(), invalidSnapshot);
            }
            return Flux.fromIterable(candidates)
                    .concatMap(name -> client.fetch(Snapshot.class, name)
                            .filter(snapshot -> belongsTo(snapshot, post)))
                    .next()
                    .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "发布快照指针损坏且没有可恢复版本")))
                    .flatMap(snapshot -> {
                        spec.setReleaseSnapshot(snapshot.getMetadata().getName());
                        return client.update(post);
                    });
        })).retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                RETRY_UPDATE, Duration.ofMillis(100)));
    }

    /** Console / UC 列表和公开列表批量还原正文，避免一页帖子产生逐条 Snapshot 查询。 */
    public Mono<Map<String, String>> resolveContents(List<BbsPost> posts, boolean editing) {
        if (posts.isEmpty()) {
            return Mono.just(Map.of());
        }
        Set<String> names = new LinkedHashSet<>();
        for (var post : posts) {
            var spec = post.getSpec();
            var target = editing ? spec.getHeadSnapshot() : spec.getReleaseSnapshot();
            if (StringUtils.isNotBlank(target)) {
                names.add(target);
            }
            if (StringUtils.isNotBlank(spec.getBaseSnapshot())) {
                names.add(spec.getBaseSnapshot());
            }
        }
        if (names.isEmpty()) {
            // 整页都处于初始化窗口：返回空映射，由调用方按缺省正文处理。
            return Mono.just(Map.of());
        }
        var options = ListOptions.builder().fieldQuery(in("metadata.name", names)).build();
        return client.listAll(Snapshot.class, options, Sort.unsorted())
                .collectMap(snapshot -> snapshot.getMetadata().getName())
                .map(snapshots -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    for (var post : posts) {
                        var spec = post.getSpec();
                        var targetName = editing
                                ? spec.getHeadSnapshot() : spec.getReleaseSnapshot();
                        var target = snapshots.get(targetName);
                        var base = snapshots.get(spec.getBaseSnapshot());
                        String content = "";
                        if (target != null && base != null
                                && belongsTo(target, post) && belongsTo(base, post)) {
                            try {
                                content = ContentWrapper.patchSnapshot(target, base).getContent();
                            } catch (RuntimeException ignored) {
                                // 损坏的历史差异不应拖垮整页；详情接口仍会明确报错供排查。
                            }
                        }
                        result.put(post.getMetadata().getName(), clean(content));
                    }
                    return result;
                });
    }

    /**
     * 预览用：按指定快照还原单帖正文（snapshotName 空 = headSnapshot），对齐官方
     * {@code PreviewRouterFunction} 的 snapshotName 参数。快照缺失 / 不属于本帖 /
     * 差异损坏一律返回空串——预览不该因历史数据损坏而 500。
     */
    public Mono<String> resolveContent(BbsPost post, String snapshotName) {
        var spec = post.getSpec();
        String targetName = StringUtils.isNotBlank(snapshotName)
                ? snapshotName : spec.getHeadSnapshot();
        if (StringUtils.isBlank(targetName) || StringUtils.isBlank(spec.getBaseSnapshot())) {
            return Mono.just("");
        }
        return Mono.zip(client.fetch(Snapshot.class, targetName),
                        client.fetch(Snapshot.class, spec.getBaseSnapshot()))
                .filter(tuple -> belongsTo(tuple.getT1(), post)
                        && belongsTo(tuple.getT2(), post))
                .map(tuple -> {
                    try {
                        return clean(ContentWrapper
                                .patchSnapshot(tuple.getT1(), tuple.getT2()).getContent());
                    } catch (RuntimeException ignored) {
                        return "";
                    }
                })
                .defaultIfEmpty("");
    }

    /**
     * 列出一个 BbsPost 的全部 Snapshot（官方语义：subjectRef 精确匹配 + 排除删除中，
     * 创建时间降序）。可见性完全跟随帖子——调用方校验过帖子可读，快照就全量可读。
     */
    public Flux<BbsSnapshotDto> listSnapshots(BbsPost post) {
        var ref = Ref.of(post);
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.subjectRef", Snapshot.toSubjectRefKey(ref)))
                .build();
        var sort = Sort.by(Sort.Order.desc("metadata.creationTimestamp"),
                Sort.Order.desc("metadata.name"));
        return client.listAll(Snapshot.class, options, sort)
                .filter(snapshot -> snapshot.getMetadata().getDeletionTimestamp() == null)
                .filter(snapshot -> belongsTo(snapshot, post))
                .map(BbsSnapshotDto::from);
    }

    /**
     * 删除历史版本（官方 {@code deleteContent} 的两条规则）：
     *
     * <ul>
     *   <li>基线不可删——"The first snapshot cannot be deleted."；</li>
     *   <li>发布中的快照不可删——需先恢复到其它版本；</li>
     *   <li>删的是 head 时，先把 head 回退到 release（未发布帖回退到基线）再删。</li>
     * </ul>
     */
    public Mono<BbsPost> deleteSnapshot(BbsPost authorizedPost, String snapshotName) {
        var name = authorizedPost.getMetadata().getName();
        // authorizedPost 已由 Console 管辖或 UC owner 校验；这里先校验传入快照，再在每次
        // 乐观锁重试及真正 delete 前重新读取帖子和快照，避免“检查时是普通历史，删除时
        // 已被另一请求提升为 head/release”的 TOCTOU 悬空指针。
        return getOwnedSnapshot(authorizedPost, snapshotName)
                .doOnNext(snapshot -> requireSnapshotDeletable(authorizedPost, snapshotName))
                .then(detachHeadWithRetry(name, snapshotName))
                .flatMap(ignored -> client.get(BbsPost.class, name))
                .flatMap(latest -> getOwnedSnapshot(latest, snapshotName)
                        .flatMap(snapshot -> {
                            requireSnapshotDeletable(latest, snapshotName);
                            if (Objects.equals(latest.getSpec().getHeadSnapshot(),
                                    snapshotName)) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "快照在删除前重新成为工作版本，请重试"));
                            }
                            return client.delete(snapshot).thenReturn(latest);
                        }));
    }

    /** 当前 head 可删除时先回退到 release/base；每次冲突都从最新帖子重新计算回退目标。 */
    private Mono<BbsPost> detachHeadWithRetry(String postName, String snapshotName) {
        return Mono.defer(() -> client.get(BbsPost.class, postName)
                        .flatMap(latest -> getOwnedSnapshot(latest, snapshotName)
                                .flatMap(snapshot -> {
                                    requireSnapshotDeletable(latest, snapshotName);
                                    if (!Objects.equals(latest.getSpec().getHeadSnapshot(),
                                            snapshotName)) {
                                        return Mono.just(latest);
                                    }
                                    var fallback = StringUtils.defaultIfBlank(
                                            latest.getSpec().getReleaseSnapshot(),
                                            latest.getSpec().getBaseSnapshot());
                                    return getOwnedSnapshot(latest, fallback)
                                            .flatMap(fallbackSnapshot -> {
                                                latest.getSpec().setHeadSnapshot(fallback);
                                                var annotations = MetadataUtil
                                                        .nullSafeAnnotations(latest);
                                                if (Objects.equals(fallback,
                                                        latest.getSpec().getBaseSnapshot())) {
                                                    annotations.put(FORCE_HEAD_FORK_ANNO, "true");
                                                } else {
                                                    annotations.remove(FORCE_HEAD_FORK_ANNO);
                                                }
                                                statusOf(latest).setHeadSnapshotVersion(
                                                        fallbackSnapshot.getMetadata()
                                                                .getVersion());
                                                return client.update(latest);
                                            });
                                })))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_PUBLISH, Duration.ofMillis(100)));
    }

    private static void requireSnapshotDeletable(BbsPost post, String snapshotName) {
        var spec = post.getSpec();
        if (Objects.equals(spec.getBaseSnapshot(), snapshotName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "首个基础快照不能删除");
        }
        if (Objects.equals(spec.getReleaseSnapshot(), snapshotName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "发布中的快照不能删除，请先恢复到其他版本");
        }
    }

    /** 彻底删除帖子前清理其全部 Snapshot。 */
    public Mono<Void> deleteAll(BbsPost post) {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.subjectRef", Snapshot.toSubjectRefKey(Ref.of(post))))
                .build();
        return client.listAll(Snapshot.class, options, Sort.unsorted())
                .filter(snapshot -> snapshot.getMetadata().getDeletionTimestamp() == null)
                .flatMap(client::delete)
                .then();
    }

    /** 调和器使用：为初始化中断（有暂存无指针）的帖子幂等补齐 Snapshot 体系。 */
    public Mono<BbsPost> initializePending(String postName) {
        return Mono.defer(() -> client.fetch(BbsPost.class, postName)
                        .switchIfEmpty(Mono.empty())
                        .flatMap(post -> hasPointers(post)
                                ? Mono.just(post)
                                : ensurePointers(post, migrationActor(post))
                                        .flatMap(client::update)))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_UPDATE, Duration.ofMillis(100)));
    }

    private Mono<BbsPost> ensurePointers(BbsPost post, String actor) {
        if (hasPointers(post)) {
            return Mono.just(post);
        }
        var spec = post.getSpec();
        var releaseContent = clean(spec.getContent());
        var baseName = deterministicName("bbs-base", post.getMetadata().getName());
        var base = newSnapshot(post, baseName, null, releaseContent, actor, true, null);
        return createOrGet(base).flatMap(createdBase -> {
            spec.setBaseSnapshot(createdBase.getMetadata().getName());
            boolean published = spec.getPhase() == BbsPost.Phase.PUBLISHED;
            spec.setReleaseSnapshot(published ? createdBase.getMetadata().getName() : null);
            var draft = published ? spec.getDraft() : null;
            if (draft == null) {
                spec.setHeadSnapshot(createdBase.getMetadata().getName());
                statusOf(post).setHeadSnapshotVersion(createdBase.getMetadata().getVersion());
                clearStagedContent(post);
                MetadataUtil.nullSafeAnnotations(post).remove(INITIALIZING_ANNO);
                return Mono.just(post);
            }
            // 有未提交改动的已发布帖：head 必须独立于 release，否则保存会改写前台版本。
            var headName = deterministicName("bbs-head", post.getMetadata().getName());
            var head = newSnapshot(post, headName, createdBase.getMetadata().getName(),
                    releaseContent, actor, false, createdBase);
            return createOrGet(head).doOnNext(createdHead -> {
                spec.setHeadSnapshot(createdHead.getMetadata().getName());
                statusOf(post).setHeadSnapshotVersion(createdHead.getMetadata().getVersion());
                clearStagedContent(post);
                MetadataUtil.nullSafeAnnotations(post).remove(INITIALIZING_ANNO);
            }).thenReturn(post);
        });
    }

    private Mono<Snapshot> updateHeadWithRetry(BbsPost post, String content, String actor,
            Long observedVersion) {
        var baseName = post.getSpec().getBaseSnapshot();
        var headName = post.getSpec().getHeadSnapshot();
        return Mono.defer(() -> Mono.zip(client.get(Snapshot.class, baseName),
                                client.get(Snapshot.class, headName))
                        .doOnNext(tuple -> {
                            requireBelongsTo(tuple.getT1(), post);
                            requireBelongsTo(tuple.getT2(), post);
                        })
                        .flatMap(tuple -> {
                            var base = tuple.getT1();
                            var head = tuple.getT2();
                            if (!Objects.equals(observedVersion,
                                    head.getMetadata().getVersion())) {
                                return client.create(newSnapshot(post,
                                        UUID.randomUUID().toString(), headName, content,
                                        actor, false, base));
                            }
                            return client.update(patchSnapshot(head, base, content, actor));
                        }))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_UPDATE, Duration.ofMillis(100)));
    }

    private Snapshot patchSnapshot(Snapshot head, Snapshot base, String content, String actor) {
        var spec = head.getSpec();
        spec.setRawType(RAW_TYPE);
        if (Objects.equals(head.getMetadata().getName(), base.getMetadata().getName())) {
            spec.setRawPatch(content);
            spec.setContentPatch(content);
            MetadataUtil.nullSafeAnnotations(head).put(Snapshot.KEEP_RAW_ANNO, "true");
        } else {
            spec.setRawPatch(PatchUtils.diffToJsonPatch(
                    nullToEmpty(base.getSpec().getRawPatch()), content));
            spec.setContentPatch(PatchUtils.diffToJsonPatch(
                    nullToEmpty(base.getSpec().getContentPatch()), content));
        }
        spec.setLastModifyTime(Instant.now());
        Snapshot.addContributor(head, safeActor(actor));
        return head;
    }

    private Snapshot newSnapshot(BbsPost post, String name, String parentName, String content,
            String actor, boolean base, Snapshot baseSnapshot) {
        var snapshot = new Snapshot();
        var metadata = new Metadata();
        metadata.setName(name);
        snapshot.setMetadata(metadata);
        var spec = new Snapshot.SnapShotSpec();
        snapshot.setSpec(spec);
        spec.setSubjectRef(Ref.of(post));
        spec.setRawType(RAW_TYPE);
        spec.setParentSnapshotName(parentName);
        spec.setLastModifyTime(Instant.now());
        spec.setOwner(safeActor(actor));
        spec.setContributors(new LinkedHashSet<>(Set.of(safeActor(actor))));
        if (base) {
            spec.setRawPatch(content);
            spec.setContentPatch(content);
            MetadataUtil.nullSafeAnnotations(snapshot).put(Snapshot.KEEP_RAW_ANNO, "true");
        } else {
            spec.setRawPatch(PatchUtils.diffToJsonPatch(
                    nullToEmpty(baseSnapshot.getSpec().getRawPatch()), content));
            spec.setContentPatch(PatchUtils.diffToJsonPatch(
                    nullToEmpty(baseSnapshot.getSpec().getContentPatch()), content));
        }
        return snapshot;
    }

    /**
     * 重建指定快照的内容。
     *
     * <p>出参统一净化：即使高权限旁路直接改了核心 Snapshot，历史预览的 v-html 与恢复路径
     * 也不会把未清洗内容带回编辑器。官方 {@code /content} 原样返回，但官方场景的正文由
     * 站长 / 编辑产出，BBS 是普通用户产出，这条纵深防御不跟随官方放宽。</p>
     */
    private Mono<ContentWrapper> getContent(BbsPost post, String snapshotName) {
        if (StringUtils.isBlank(snapshotName)
                || StringUtils.isBlank(post.getSpec().getBaseSnapshot())) {
            // 无指针 / 无基线：Snapshot 体系尚未初始化的窗口，按空内容返回，
            // 调和器补齐指针后即恢复正常。
            return Mono.just(emptyContent());
        }
        return Mono.zip(fetchBase(post), getOwnedSnapshot(post, snapshotName))
                .map(tuple -> {
                    try {
                        var wrapper = ContentWrapper.patchSnapshot(tuple.getT2(), tuple.getT1());
                        return ContentWrapper.builder()
                                .snapshotName(wrapper.getSnapshotName())
                                .raw(clean(wrapper.getRaw()))
                                .content(clean(wrapper.getContent()))
                                .rawType(StringUtils.defaultIfBlank(wrapper.getRawType(),
                                        RAW_TYPE))
                                .build();
                    } catch (RuntimeException error) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                                "快照差异已损坏，无法还原", error);
                    }
                });
    }

    private Mono<Snapshot> fetchBase(BbsPost post) {
        var name = post.getSpec().getBaseSnapshot();
        if (StringUtils.isBlank(name)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "帖子缺少基础快照"));
        }
        return client.fetch(Snapshot.class, name)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT, "基础快照不存在")))
                .doOnNext(snapshot -> {
                    requireBelongsTo(snapshot, post);
                    if (!Snapshot.isBaseSnapshot(snapshot)) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                                "帖子基础快照标记无效");
                    }
                });
    }

    private Mono<Snapshot> getOwnedSnapshot(BbsPost post, String name) {
        return client.fetch(Snapshot.class, name)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "快照不存在")))
                .doOnNext(snapshot -> requireBelongsTo(snapshot, post));
    }

    /** 新 head 落位：写指针、同步版本号、解除强制分叉标记。 */
    private static void attachHead(BbsPost post, Snapshot head) {
        post.getSpec().setHeadSnapshot(head.getMetadata().getName());
        statusOf(post).setHeadSnapshotVersion(head.getMetadata().getVersion());
        if (!Objects.equals(head.getMetadata().getName(), post.getSpec().getBaseSnapshot())) {
            MetadataUtil.nullSafeAnnotations(post).remove(FORCE_HEAD_FORK_ANNO);
        }
    }

    private static boolean belongsTo(Snapshot snapshot, BbsPost post) {
        return snapshot.getSpec() != null
                && snapshot.getSpec().getSubjectRef() != null
                && Ref.equals(snapshot.getSpec().getSubjectRef(), post);
    }

    private static void addCandidate(Set<String> candidates, String candidate,
            String invalidSnapshot) {
        if (StringUtils.isNotBlank(candidate)
                && !Objects.equals(candidate, invalidSnapshot)) {
            candidates.add(candidate);
        }
    }

    private static void requireBelongsTo(Snapshot snapshot, BbsPost post) {
        if (!belongsTo(snapshot, post)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "快照不属于该帖子");
        }
    }

    private Mono<Snapshot> createOrGet(Snapshot snapshot) {
        return client.fetch(Snapshot.class, snapshot.getMetadata().getName())
                .switchIfEmpty(client.create(snapshot));
    }

    private Mono<BbsPost> updatePostWithRetry(String name, Consumer<BbsPost> mutation) {
        return Mono.defer(() -> client.get(BbsPost.class, name)
                        .doOnNext(mutation)
                        .flatMap(client::update))
                .retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                        RETRY_UPDATE, Duration.ofMillis(100)));
    }

    private static boolean hasPointers(BbsPost post) {
        return StringUtils.isNotBlank(post.getSpec().getBaseSnapshot())
                && StringUtils.isNotBlank(post.getSpec().getHeadSnapshot());
    }

    private static BbsPost.Status statusOf(BbsPost post) {
        var status = post.getStatus();
        if (status == null) {
            status = new BbsPost.Status();
            post.setStatus(status);
        }
        return status;
    }

    /** 消费掉初始化中断的正文暂存（见 {@code BbsPost.Spec#content}）。 */
    private static void clearStagedContent(BbsPost post) {
        post.getSpec().setContent(null);
    }

    private static ContentWrapper emptyContent() {
        return ContentWrapper.builder().raw("").content("").rawType(RAW_TYPE).build();
    }

    private static String deterministicName(String namespace, String postName) {
        return UUID.nameUUIDFromBytes((namespace + ":" + postName)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String migrationActor(BbsPost post) {
        return safeActor(post.getSpec().getOwner());
    }

    private static String safeActor(String actor) {
        return StringUtils.defaultIfBlank(actor, "system");
    }

    private static String clean(String content) {
        return HtmlSanitizer.clean(nullToEmpty(content));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
