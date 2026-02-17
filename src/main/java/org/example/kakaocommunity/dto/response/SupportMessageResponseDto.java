package org.example.kakaocommunity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class SupportMessageResponseDto {

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreateDto {
        private Long messageId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MessageSummary {
        private Long messageId;
        private String authorNickname;
        private String authorProfileImageUrl;
        private String content;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ListDto {
        private List<MessageSummary> messages;
        private Long nextCursorId;
        private boolean hasNext;
    }
}