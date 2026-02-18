package org.example.kakaocommunity.mapper;

import org.example.kakaocommunity.dto.response.SupportMessageResponseDto;
import org.example.kakaocommunity.entity.SupportMessage;

public class SupportMessageMapper {

    public static SupportMessageResponseDto.CreateDto toCreateDto(SupportMessage message) {
        return SupportMessageResponseDto.CreateDto.builder()
                .messageId(message.getId())
                .build();
    }

    public static SupportMessageResponseDto.MessageSummary toMessageSummary(SupportMessage message) {
        return SupportMessageResponseDto.MessageSummary.builder()
                .messageId(message.getId())
                .authorNickname(message.getMember().getNickname())
                .authorProfileImageUrl(
                        message.getMember().getImage() != null
                                ? message.getMember().getImage().getUrl()
                                : null
                )
                .content(message.getContent())
                .createdAt(message.getCreatedAt())
                .build();
    }
}