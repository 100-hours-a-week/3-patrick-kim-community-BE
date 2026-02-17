package org.example.kakaocommunity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class EntryResponseDto {

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreateDto {
        private Long entryId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EntrySummary {
        private Long entryId;
        private String petName;
        private String imageUrl;
        private int voteCount;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DetailDto {
        private Long entryId;
        private Long challengeId;
        private String challengeTitle;
        private Long petId;
        private String petName;
        private String ownerNickname;
        private String imageUrl;
        private String caption;
        private int voteCount;
        private boolean voted;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ListDto {
        private List<EntrySummary> entries;
        private Long nextCursorId;
        private boolean hasNext;
    }
}
