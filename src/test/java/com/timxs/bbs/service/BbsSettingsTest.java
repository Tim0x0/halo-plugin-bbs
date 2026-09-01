package com.timxs.bbs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BbsSettingsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void parsesNestedGroupFields() throws Exception {
        var root = mapper.readTree("""
                {
                  "brand": { "pageTitle": "新标题", "slogan": "新副标题" },
                  "hero": { "showHero": false, "heroStyle": "image" }
                }
                """);

        var appearance = BbsSettings.parseAppearance(root);

        assertEquals("新标题", appearance.brand().pageTitle());
        assertEquals("新副标题", appearance.brand().slogan());
        assertFalse(appearance.hero().showHero());
        assertEquals("image", appearance.hero().heroStyle());
    }

    @Test
    void contentFallsBackToDefaultsWhenFieldsAreMissing() throws Exception {
        var content = BbsSettings.parseContent(mapper.readTree("{}"));

        assertEquals(100, content.titleMaxOrDefault());
        assertFalse(content.required());
        assertTrue(content.editNeedsReview());
    }
}
