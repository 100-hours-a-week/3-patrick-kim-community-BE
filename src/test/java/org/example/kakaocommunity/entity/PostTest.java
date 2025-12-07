package org.example.kakaocommunity.entity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostTest {

    private Post p;

    @BeforeEach
    void init() {
        p = Post.builder()
                .id(1L)
                .title("제목")
                .content("내용")
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();
    }

    @Test
    void 게시글_조회수_증가() {
        // given
        assertEquals(0, p.getViewCount());

        // when
        p.increaseViewCount();

        // then
        assertEquals(1, p.getViewCount());
    }

}