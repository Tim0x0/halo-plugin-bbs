package com.timxs.bbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.timxs.bbs.extension.BbsCategory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class BbsModerationScopeTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    RoleService roleService;

    @Test
    void cachesOnlyCategoryTopologyAndCanInvalidateIt() {
        var root = category("root", null, List.of("region-a"));
        var child = category("child", "root", List.of());
        when(roleService.getRolesByUsername("alice")).thenReturn(Flux.just("region-a"));
        when(client.listAll(eq(BbsCategory.class), any(), any()))
                .thenReturn(Flux.just(root, child));
        var scopeService = new BbsModerationScope(client, roleService);

        var first = scopeService.resolve("alice").block();
        var second = scopeService.resolve("alice").block();

        assertFalse(first.global());
        assertEquals(first, second);
        assertEquals(java.util.Set.of("root", "child"), first.categoryNames());
        verify(roleService, times(2)).getRolesByUsername("alice");
        verify(client, times(1)).listAll(eq(BbsCategory.class), any(), any());

        scopeService.invalidateCategoryCache();
        assertTrue(scopeService.resolve("alice").block().covers("child"));
        verify(client, times(2)).listAll(eq(BbsCategory.class), any(), any());
    }

    @Test
    void globalRoleDoesNotScanCategories() {
        when(roleService.getRolesByUsername("admin")).thenReturn(Flux.just("bbs-view"));
        var scopeService = new BbsModerationScope(client, roleService);

        assertTrue(scopeService.resolve("admin").block().global());

        verify(client, never()).listAll(eq(BbsCategory.class), any(), any());
    }

    private static BbsCategory category(String name, String parentName, List<String> roles) {
        var category = new BbsCategory();
        var metadata = new Metadata();
        metadata.setName(name);
        category.setMetadata(metadata);
        category.getSpec().setParentName(parentName);
        category.getSpec().setModeratorRoles(roles);
        return category;
    }
}
