package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.SupportMessageRequestDto;
import org.example.kakaocommunity.dto.response.SupportMessageResponseDto;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.entity.SupportMessage;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.mapper.SupportMessageMapper;
import org.example.kakaocommunity.repository.EntryRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.SupportMessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class SupportMessageService {

    private final SupportMessageRepository supportMessageRepository;
    private final EntryRepository entryRepository;
    private final MemberRepository memberRepository;

    public SupportMessageResponseDto.CreateDto createSupportMessage(
            Long entryId,
            SupportMessageRequestDto.CreateDto request,
            Integer memberId
    ) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        SupportMessage supportMessage = SupportMessage.builder()
                .entry(entry)
                .member(member)
                .content(request.getContent())
                .build();

        SupportMessage saved = supportMessageRepository.save(supportMessage);
        return SupportMessageMapper.toCreateDto(saved);
    }

    public SupportMessageResponseDto.ListDto getSupportMessages(Long entryId, Long cursorId, int limit) {
        List<SupportMessage> messages = supportMessageRepository
                .findSupportMessagesByEntryWithCursor(entryId, cursorId, limit + 1);

        boolean hasNext = messages.size() > limit;
        if (hasNext) {
            messages = messages.subList(0, limit);
        }

        Long nextCursorId = messages.isEmpty() ? null : messages.get(messages.size() - 1).getId();

        List<SupportMessageResponseDto.MessageSummary> summaries = messages.stream()
                .map(SupportMessageMapper::toMessageSummary)
                .collect(Collectors.toList());

        return SupportMessageResponseDto.ListDto.builder()
                .messages(summaries)
                .nextCursorId(nextCursorId)
                .hasNext(hasNext)
                .build();
    }

    public void deleteSupportMessage(Long messageId, Integer memberId) {
        SupportMessage message = supportMessageRepository.findById(messageId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        if (!message.getMember().getId().equals(memberId)) {
            throw new GeneralException(ErrorStatus._FORBIDDEN);
        }

        supportMessageRepository.delete(message);
    }
}