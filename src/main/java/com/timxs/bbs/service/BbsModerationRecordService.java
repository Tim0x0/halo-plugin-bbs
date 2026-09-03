package com.timxs.bbs.service;

import static run.halo.app.extension.index.query.Queries.equal;

import com.timxs.bbs.extension.BbsModerationRecord;
import com.timxs.bbs.extension.BbsPost;
import com.timxs.bbs.util.ReactiveOptimisticUpdates;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

/** 低频、只追加的帖子审核审计记录服务。 */
@Component
public class BbsModerationRecordService {

    public static final String PENDING_ANNO_PREFIX =
            "bbs.timxs.com/moderation-pending-";
    private static final String PENDING_VERSION = "v1";
    private static final int RETRY_TIMES = 5;

    private final ReactiveExtensionClient client;

    public BbsModerationRecordService(ReactiveExtensionClient client) {
        this.client = client;
    }

    /**
     * 把审核事件写进 BbsPost annotation；调用方须在同一次帖子更新中调用，形成轻量 outbox。
     */
    public void enqueue(BbsPost post, BbsModerationRecord.Action action,
            String actor, String reason, String fromPhase, String toPhase) {
        enqueue(post, action, actor, reason, fromPhase, toPhase,
                post.getSpec().getHeadSnapshot());
    }

    public void enqueue(BbsPost post, BbsModerationRecord.Action action,
            String actor, String reason, String fromPhase, String toPhase,
            String snapshotName) {
        var eventId = UUID.randomUUID().toString();
        var pending = new PendingAction(action, actor, sanitizeReason(reason), fromPhase, toPhase,
                snapshotName, Instant.now());
        MetadataUtil.nullSafeAnnotations(post).put(PENDING_ANNO_PREFIX + eventId,
                encodePending(pending));
    }

    /**
     * 幂等落盘帖子上的所有待写审核事件；全部成功后才清理对应 annotation。
     */
    public Mono<BbsPost> flushPending(BbsPost observed) {
        List<Map.Entry<String, String>> pending = MetadataUtil.nullSafeAnnotations(observed)
                .entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(PENDING_ANNO_PREFIX))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
        if (pending.isEmpty()) {
            return Mono.just(observed);
        }
        return Flux.fromIterable(pending)
                .concatMap(entry -> persistPending(observed, entry))
                .then(clearPending(observed.getMetadata().getName(), pending));
    }

    private Mono<BbsModerationRecord> persistPending(BbsPost post,
            Map.Entry<String, String> entry) {
        return Mono.defer(() -> {
            var event = decodePending(entry.getValue());
            var snapshotName = StringUtils.defaultIfBlank(event.snapshotName(),
                    post.getSpec().getHeadSnapshot());
            var resolved = new PendingAction(event.action(), event.actor(), event.reason(),
                    event.fromPhase(), event.toPhase(), snapshotName, event.createdAt());
            var eventId = entry.getKey().substring(PENDING_ANNO_PREFIX.length());
            return persistRecord(post, "bbs-moderation-" + eventId, resolved);
        });
    }

    private Mono<BbsModerationRecord> persistRecord(BbsPost post, String name,
            PendingAction event) {
        var record = new BbsModerationRecord();
        var metadata = new Metadata();
        metadata.setName(name);
        record.setMetadata(metadata);
        var spec = record.getSpec();
        spec.setPostName(post.getMetadata().getName());
        spec.setAction(event.action());
        spec.setActor(StringUtils.defaultIfBlank(event.actor(), "system"));
        spec.setSnapshotName(StringUtils.trimToNull(event.snapshotName()));
        spec.setFromPhase(StringUtils.trimToNull(event.fromPhase()));
        spec.setToPhase(StringUtils.trimToNull(event.toPhase()));
        spec.setReason(StringUtils.abbreviate(StringUtils.trimToNull(event.reason()), 500));
        spec.setCreatedAt(event.createdAt());

        // 快照与审核解耦：记录里的 snapshotName 只是展示参考，不给快照打删除保护注解。
        // 审核轨迹由记录本身承载，历史版本按官方规则（基线 / 发布版不可删）自行管理。
        return client.fetch(BbsModerationRecord.class, name)
                .switchIfEmpty(Mono.defer(() -> client.create(record)
                        // 服务层与调和器可能同时补偿；创建冲突后读回即视为成功。
                        .onErrorResume(error -> client.fetch(
                                        BbsModerationRecord.class, name)
                                .switchIfEmpty(Mono.error(error)))));
    }

    public Flux<BbsModerationRecord> listByPost(String postName) {
        var options = ListOptions.builder()
                .fieldQuery(equal("spec.postName", postName))
                .build();
        return client.listAll(BbsModerationRecord.class, options,
                        Sort.by(Sort.Order.desc("spec.createdAt"),
                                Sort.Order.desc("metadata.name")))
                .filter(record -> record.getMetadata().getDeletionTimestamp() == null);
    }

    /** 最近一次成功发布/审核通过所引用的 Snapshot，可用于修复丢失的 release 指针。 */
    public Mono<String> latestPublishedSnapshotName(String postName) {
        return listByPost(postName)
                .filter(record -> record.getSpec().getAction()
                        == BbsModerationRecord.Action.PUBLISHED
                        || record.getSpec().getAction()
                        == BbsModerationRecord.Action.APPROVED)
                .map(record -> record.getSpec().getSnapshotName())
                .filter(StringUtils::isNotBlank)
                .next();
    }

    public Mono<Void> deleteAll(BbsPost post) {
        return listByPost(post.getMetadata().getName())
                .flatMap(client::delete)
                .then();
    }

    private Mono<BbsPost> clearPending(String postName,
            List<Map.Entry<String, String>> processed) {
        return Mono.defer(() -> client.get(BbsPost.class, postName).flatMap(latest -> {
            var annotations = MetadataUtil.nullSafeAnnotations(latest);
            boolean changed = false;
            for (var entry : processed) {
                if (Objects.equals(annotations.get(entry.getKey()), entry.getValue())) {
                    annotations.remove(entry.getKey());
                    changed = true;
                }
            }
            return changed ? client.update(latest) : Mono.just(latest);
        })).retryWhen(ReactiveOptimisticUpdates.conflictRetry(
                RETRY_TIMES, Duration.ofMillis(100)));
    }

    private static String encodePending(PendingAction event) {
        return String.join("|", PENDING_VERSION, event.action().name(),
                encodePart(event.actor()), encodePart(event.reason()),
                encodePart(event.fromPhase()), encodePart(event.toPhase()),
                encodePart(event.snapshotName()),
                Long.toString(event.createdAt().toEpochMilli()));
    }

    private static PendingAction decodePending(String encoded) {
        var parts = StringUtils.defaultString(encoded).split("\\|", -1);
        if (parts.length != 8 || !PENDING_VERSION.equals(parts[0])) {
            throw new IllegalStateException("Unsupported moderation pending event");
        }
        try {
            return new PendingAction(BbsModerationRecord.Action.valueOf(parts[1]),
                    decodePart(parts[2]), decodePart(parts[3]), decodePart(parts[4]),
                    decodePart(parts[5]), decodePart(parts[6]),
                    Instant.ofEpochMilli(Long.parseLong(parts[7])));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Invalid moderation pending event", error);
        }
    }

    private static String encodePart(String value) {
        if (value == null) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePart(String value) {
        if (value.isEmpty()) {
            return null;
        }
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String sanitizeReason(String reason) {
        return StringUtils.abbreviate(StringUtils.trimToNull(reason), 500);
    }

    private record PendingAction(BbsModerationRecord.Action action, String actor,
                                 String reason, String fromPhase, String toPhase,
                                 String snapshotName, Instant createdAt) {
    }
}
