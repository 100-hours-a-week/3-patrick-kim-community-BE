package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.response.VoteResponseDto;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.entity.Vote;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.repository.EntryRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.VoteRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class VoteService {

    private final VoteRepository voteRepository;
    private final EntryRepository entryRepository;
    private final MemberRepository memberRepository;

    public VoteResponseDto.VoteResult vote(Long entryId, Integer memberId) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        if (entry.getChallenge().getStatus() != ChallengeStatus.ACTIVE) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        if (entry.getMember().getId().equals(memberId)) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Vote vote = Vote.builder()
                .entry(entry)
                .member(member)
                .build();

        voteRepository.save(vote);
        entry.increaseVoteCount();

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(entry.getVoteCount())
                .build();
    }

    public VoteResponseDto.VoteResult cancelVote(Long entryId, Integer memberId) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Vote vote = voteRepository.findByEntryIdAndMemberId(entryId, memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        voteRepository.delete(vote);
        entry.decreaseVoteCount();

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(entry.getVoteCount())
                .build();
    }
}