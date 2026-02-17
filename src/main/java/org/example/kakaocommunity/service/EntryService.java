package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.EntryRequestDto;
import org.example.kakaocommunity.dto.response.EntryResponseDto;
import org.example.kakaocommunity.entity.*;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.mapper.EntryMapper;
import org.example.kakaocommunity.repository.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class EntryService {

    private final EntryRepository entryRepository;
    private final ChallengeRepository challengeRepository;
    private final PetRepository petRepository;
    private final MemberRepository memberRepository;
    private final ImageRepository imageRepository;
    private final VoteRepository voteRepository;

    public EntryResponseDto.CreateDto createEntry(Long challengeId, EntryRequestDto.CreateDto request, Integer memberId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        if (challenge.getStatus() != ChallengeStatus.ACTIVE) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        Pet pet = petRepository.findByIdAndDeletedAtIsNull(request.getPetId())
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        if (!pet.getMember().getId().equals(memberId)) {
            throw new GeneralException(ErrorStatus._FORBIDDEN);
        }

        if (entryRepository.existsByChallengeIdAndPetId(challengeId, request.getPetId())) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Image image = imageRepository.findById(request.getImageId())
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Entry entry = Entry.builder()
                .challenge(challenge)
                .pet(pet)
                .member(member)
                .image(image)
                .caption(request.getCaption())
                .build();

        Entry savedEntry = entryRepository.save(entry);
        return EntryMapper.toCreateDto(savedEntry);
    }

    public EntryResponseDto.ListDto getEntryList(Long challengeId, Long cursorId, int limit) {
        List<Entry> entries = entryRepository.findEntriesByChallengeWithCursor(challengeId, cursorId, limit + 1);

        boolean hasNext = entries.size() > limit;
        if (hasNext) {
            entries = entries.subList(0, limit);
        }

        Long nextCursorId = entries.isEmpty() ? null : entries.get(entries.size() - 1).getId();

        List<EntryResponseDto.EntrySummary> entrySummaries = entries.stream()
                .map(EntryMapper::toEntrySummary)
                .collect(Collectors.toList());

        return EntryResponseDto.ListDto.builder()
                .entries(entrySummaries)
                .nextCursorId(nextCursorId)
                .hasNext(hasNext)
                .build();
    }

    public EntryResponseDto.DetailDto getEntryDetail(Long entryId, Integer memberId) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        boolean voted = voteRepository.existsByEntryIdAndMemberId(entryId, memberId);

        return EntryMapper.toDetailDto(entry, voted);
    }

    public void deleteEntry(Long entryId, Integer memberId) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        if (!entry.getMember().getId().equals(memberId)) {
            throw new GeneralException(ErrorStatus._FORBIDDEN);
        }

        entryRepository.delete(entry);
    }
}