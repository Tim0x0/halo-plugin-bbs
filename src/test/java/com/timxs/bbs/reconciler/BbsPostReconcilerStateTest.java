package com.timxs.bbs.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.timxs.bbs.extension.BbsPost;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BbsPostReconcilerStateTest {

    @Test
    void commentDerivedChangesDoNotInvalidateCategoryOrSearchWork() {
        var post = publishedPost();
        var categoryState = BbsPostReconciler.categoryCountState(post);
        var searchState = BbsPostReconciler.searchIndexState(post);

        post.getStatus().setCommentsCount(42);
        post.getSpec().setLastActivityTime(Instant.parse("2026-08-30T12:00:00Z"));

        assertEquals(categoryState, BbsPostReconciler.categoryCountState(post));
        assertEquals(searchState, BbsPostReconciler.searchIndexState(post));
    }

    @Test
    void onlyRelevantChangesInvalidateTheirDerivedWork() {
        var post = publishedPost();
        var categoryState = BbsPostReconciler.categoryCountState(post);
        var searchState = BbsPostReconciler.searchIndexState(post);

        post.getSpec().setTitle("修改后的标题");
        assertEquals(categoryState, BbsPostReconciler.categoryCountState(post));
        assertNotEquals(searchState, BbsPostReconciler.searchIndexState(post));

        post.getSpec().setPhase(BbsPost.Phase.DRAFT);
        assertNotEquals(categoryState, BbsPostReconciler.categoryCountState(post));
        assertEquals("v1:absent", BbsPostReconciler.searchIndexState(post));
    }

    private static BbsPost publishedPost() {
        var post = new BbsPost();
        var spec = post.getSpec();
        spec.setPhase(BbsPost.Phase.PUBLISHED);
        spec.setDeleted(false);
        spec.setTitle("标题");
        spec.setSlug("slug");
        spec.setOwner("alice");
        spec.setCategoryName("category-a");
        spec.setBaseSnapshot("snapshot-base");
        spec.setReleaseSnapshot("snapshot-release");
        spec.setPublishTime(Instant.parse("2026-08-30T10:00:00Z"));
        return post;
    }
}
