package com.timxs.bbs.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timxs.bbs.extension.BbsPost;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import run.halo.app.extension.ExtensionClient;

@ExtendWith(MockitoExtension.class)
class OptimisticUpdatesTest {

    @Mock
    ExtensionClient client;

    @Test
    void doesNotBackOffAfterTheFinalFailedAttempt() {
        when(client.fetch(BbsPost.class, "post-a"))
                .thenReturn(Optional.of(new BbsPost()));
        doThrow(new OptimisticLockingFailureException("conflict"))
                .when(client).update(any(BbsPost.class));
        var backoffs = new ArrayList<Integer>();

        assertThrows(OptimisticLockingFailureException.class,
                () -> OptimisticUpdates.update(client, BbsPost.class, "post-a",
                        ignored -> { }, backoffs::add));

        assertEquals(java.util.List.of(0, 1, 2, 3), backoffs);
        verify(client, times(5)).fetch(BbsPost.class, "post-a");
    }
}
