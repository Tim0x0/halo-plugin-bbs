package com.timxs.bbs.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BbsPostStatusJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsAndWritesCommentsCountUnderItsOfficialName() throws Exception {
        var status = mapper.readValue("{\"commentsCount\":7}", BbsPost.Status.class);

        assertEquals(7, status.getCommentsCount());
        var json = mapper.writeValueAsString(status);
        assertTrue(json.contains("\"commentsCount\":7"));
        assertFalse(json.contains("\"commentCount\""));
    }
}
