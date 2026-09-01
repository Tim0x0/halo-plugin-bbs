package com.timxs.bbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timxs.bbs.extension.BbsPost;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;

/**
 * prepareHead 的「无改动不落库」语义测试：内容相同跳过快照写入，
 * 强制分叉（删 head 回退基线）不受相等性判断影响。
 */
@ExtendWith(MockitoExtension.class)
class BbsPostContentServiceTest {

    static final String CONTENT = "<p>hello</p>";

    @Mock
    ReactiveExtensionClient client;

    BbsPostContentService contentService;

    BbsPost post;
    Snapshot base;
    Snapshot head;

    @BeforeEach
    void setUp() {
        contentService = new BbsPostContentService(client);
        post = new BbsPost();
        post.setMetadata(new Metadata());
        post.getMetadata().setName("post-a");
        post.getSpec().setBaseSnapshot("base-1");
        post.getSpec().setHeadSnapshot("head-1");
        post.getStatus().setHeadSnapshotVersion(3L);

        base = newSnapshot("base-1", null, CONTENT, true);
        head = newSnapshot("head-1", "base-1", CONTENT, false);
    }

    @Test
    void identicalContentSkipsSnapshotWrite() {
        when(client.fetch(Snapshot.class, "head-1")).thenReturn(Mono.just(head));
        when(client.fetch(Snapshot.class, "base-1")).thenReturn(Mono.just(base));

        var result = contentService.prepareHead(post, CONTENT, "alice", null).block();

        assertEquals("head-1", result.getSpec().getHeadSnapshot());
        verify(client, never()).create(any(Snapshot.class));
        verify(client, never()).update(any(Snapshot.class));
    }

    @Test
    void identicalContentStillSkipsWhenHeadIsRelease() {
        // 取消发布后重新发布的场景：release 指针仍在且 head == release，
        // 内容没变就不应分叉出空版本
        post.getSpec().setReleaseSnapshot("head-1");
        when(client.fetch(Snapshot.class, "head-1")).thenReturn(Mono.just(head));
        when(client.fetch(Snapshot.class, "base-1")).thenReturn(Mono.just(base));

        var result = contentService.prepareHead(post, CONTENT, "alice", null).block();

        assertEquals("head-1", result.getSpec().getHeadSnapshot());
        verify(client, never()).create(any(Snapshot.class));
        verify(client, never()).update(any(Snapshot.class));
    }

    @Test
    void changedContentForksWhenHeadIsRelease() {
        post.getSpec().setReleaseSnapshot("head-1");
        when(client.fetch(Snapshot.class, "head-1")).thenReturn(Mono.just(head));
        when(client.fetch(Snapshot.class, "base-1")).thenReturn(Mono.just(base));
        when(client.create(any(Snapshot.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var result = contentService.prepareHead(post, "<p>changed</p>", "alice", null).block();

        assertNotEquals("head-1", result.getSpec().getHeadSnapshot());
        verify(client).create(any(Snapshot.class));
    }

    @Test
    void forcedForkIgnoresContentEquality() {
        // 删 head 回退到基线后，基线不可原地改写：即使内容相同也必须分叉
        MetadataUtil.nullSafeAnnotations(post)
                .put("bbs.timxs.com/force-head-fork", "true");
        post.getSpec().setHeadSnapshot("base-1");
        when(client.fetch(Snapshot.class, "base-1")).thenReturn(Mono.just(base));
        when(client.create(any(Snapshot.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        var result = contentService.prepareHead(post, CONTENT, "alice", null).block();

        assertNotEquals("base-1", result.getSpec().getHeadSnapshot());
        verify(client).create(any(Snapshot.class));
    }

    private Snapshot newSnapshot(String name, String parentName, String content, boolean base) {
        var snapshot = new Snapshot();
        var metadata = new Metadata();
        metadata.setName(name);
        snapshot.setMetadata(metadata);
        var spec = new Snapshot.SnapShotSpec();
        snapshot.setSpec(spec);
        spec.setSubjectRef(Ref.of(post));
        spec.setRawType(BbsPostContentService.RAW_TYPE);
        spec.setParentSnapshotName(parentName);
        spec.setOwner("alice");
        spec.setContributors(new LinkedHashSet<>(java.util.Set.of("alice")));
        if (base) {
            spec.setRawPatch(content);
            spec.setContentPatch(content);
            MetadataUtil.nullSafeAnnotations(snapshot).put(Snapshot.KEEP_RAW_ANNO, "true");
        } else {
            spec.setRawPatch(PatchUtils.diffToJsonPatch(CONTENT, content));
            spec.setContentPatch(PatchUtils.diffToJsonPatch(CONTENT, content));
        }
        return snapshot;
    }
}
