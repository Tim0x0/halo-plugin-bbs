package com.timxs.bbs.comment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.Metadata;

class BbsFloorNumbersTest {

    @Test
    void firstCommentFloorIsTwo() {
        assertEquals(2, BbsFloorNumbers.FIRST_COMMENT_FLOOR);
        assertEquals(2, BbsFloorNumbers.ofEarlierCount(0));
        assertEquals(3, BbsFloorNumbers.ofEarlierCount(1));
    }

    @Test
    void readFrozenFloor() {
        var comment = commentWithFloor("5");
        assertEquals(5, BbsFloorNumbers.read(comment));
    }

    @Test
    void readMissingOrInvalidAsZero() {
        assertEquals(0, BbsFloorNumbers.read(null));
        assertEquals(0, BbsFloorNumbers.read(new Comment()));
        assertEquals(0, BbsFloorNumbers.read(commentWithFloor("1")));
        assertEquals(0, BbsFloorNumbers.read(commentWithFloor("x")));
    }

    private static Comment commentWithFloor(String floor) {
        var comment = new Comment();
        var metadata = new Metadata();
        metadata.setName("c1");
        var annos = new HashMap<String, String>();
        annos.put(BbsCommentAnnos.FLOOR, floor);
        metadata.setAnnotations(annos);
        comment.setMetadata(metadata);
        return comment;
    }
}
