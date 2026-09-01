package com.timxs.bbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.timxs.bbs.extension.BbsPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class BbsPostServiceOwnerTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    BbsSettings settings;

    @Mock
    BbsModerationScope moderationScope;

    @Mock
    BbsPostContentService contentService;

    @Mock
    BbsModerationRecordService moderationRecordService;

    private BbsPostService service;

    @BeforeEach
    void setUp() {
        service = new BbsPostService(client, settings, moderationScope, contentService,
                moderationRecordService);
    }

    @Test
    void getOwnedMustFailClosedWhenOwnerIsMissing() {
        var post = new BbsPost();
        post.getSpec().setOwner("alice");
        when(client.fetch(BbsPost.class, "post-a")).thenReturn(Mono.just(post));

        var error = assertThrows(ResponseStatusException.class,
                () -> service.getOwned("post-a", null).block());

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        assertEquals("缺少归属校验主体", error.getReason());
    }
}
