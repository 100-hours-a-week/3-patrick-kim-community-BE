package org.example.kakaocommunity.mapper;

import org.example.kakaocommunity.dto.response.ChallengeResponseDto;
import org.example.kakaocommunity.entity.Challenge;
import org.example.kakaocommunity.entity.Entry;

import java.util.concurrent.atomic.AtomicInteger;

public class ChallengeMapper {

    public static ChallengeResponseDto.ChallengeSummary toChallengeSummary(Challenge challenge) {
        return ChallengeResponseDto.ChallengeSummary.builder()
                .challengeId(challenge.getId())
                .title(challenge.getTitle())
                .thumbnailUrl(challenge.getThumbnail() != null ? challenge.getThumbnail().getUrl() : null)
                .status(challenge.getStatus())
                .startAt(challenge.getStartAt())
                .endAt(challenge.getEndAt())
                .build();
    }

    public static ChallengeResponseDto.DetailDto toDetailDto(Challenge challenge) {
        return ChallengeResponseDto.DetailDto.builder()
                .challengeId(challenge.getId())
                .title(challenge.getTitle())
                .description(challenge.getDescription())
                .thumbnailUrl(challenge.getThumbnail() != null ? challenge.getThumbnail().getUrl() : null)
                .status(challenge.getStatus())
                .startAt(challenge.getStartAt())
                .endAt(challenge.getEndAt())
                .maxEntries(challenge.getMaxEntries())
                .build();
    }

    private static final AtomicInteger rankCounter = new AtomicInteger(0);

    public static ChallengeResponseDto.RankingEntry toRankingEntry(Entry entry) {
        return ChallengeResponseDto.RankingEntry.builder()
                .rank(rankCounter.incrementAndGet())
                .entryId(entry.getId())
                .petName(entry.getPet().getName())
                .imageUrl(entry.getImage() != null ? entry.getImage().getUrl() : null)
                .voteCount(entry.getVoteCount())
                .build();
    }
}