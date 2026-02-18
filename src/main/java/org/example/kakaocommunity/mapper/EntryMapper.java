package org.example.kakaocommunity.mapper;

import org.example.kakaocommunity.dto.response.EntryResponseDto;
import org.example.kakaocommunity.entity.Entry;

public class EntryMapper {

    public static EntryResponseDto.CreateDto toCreateDto(Entry entry) {
        return EntryResponseDto.CreateDto.builder()
                .entryId(entry.getId())
                .build();
    }

    public static EntryResponseDto.EntrySummary toEntrySummary(Entry entry) {
        return EntryResponseDto.EntrySummary.builder()
                .entryId(entry.getId())
                .petName(entry.getPet().getName())
                .imageUrl(entry.getImage() != null ? entry.getImage().getUrl() : null)
                .voteCount(entry.getVoteCount())
                .createdAt(entry.getCreatedAt())
                .build();
    }

    public static EntryResponseDto.DetailDto toDetailDto(Entry entry, boolean voted) {
        return EntryResponseDto.DetailDto.builder()
                .entryId(entry.getId())
                .challengeId(entry.getChallenge().getId())
                .challengeTitle(entry.getChallenge().getTitle())
                .petId(entry.getPet().getId())
                .petName(entry.getPet().getName())
                .ownerNickname(entry.getMember().getNickname())
                .imageUrl(entry.getImage() != null ? entry.getImage().getUrl() : null)
                .caption(entry.getCaption())
                .voteCount(entry.getVoteCount())
                .voted(voted)
                .createdAt(entry.getCreatedAt())
                .build();
    }
}