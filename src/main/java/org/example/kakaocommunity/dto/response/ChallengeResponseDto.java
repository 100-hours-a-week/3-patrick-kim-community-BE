package org.example.kakaocommunity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;

import java.time.LocalDateTime;
import java.util.List;

public class ChallengeResponseDto {

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChallengeSummary {
        private Long challengeId;
        private String title;
        private String thumbnailUrl;
        private ChallengeStatus status;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DetailDto {
        private Long challengeId;
        private String title;
        private String description;
        private String thumbnailUrl;
        private ChallengeStatus status;
        private LocalDateTime startAt;
        private LocalDateTime endAt;
        private Integer maxEntries;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RankingEntry {
        private int rank;
        private Long entryId;
        private String petName;
        private String imageUrl;
        private int voteCount;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RankingDto {
        private Long challengeId;
        private List<RankingEntry> rankings;
    }
}
