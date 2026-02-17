package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.response.ChallengeResponseDto;
import org.example.kakaocommunity.entity.Challenge;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.mapper.ChallengeMapper;
import org.example.kakaocommunity.repository.ChallengeRepository;
import org.example.kakaocommunity.repository.EntryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final EntryRepository entryRepository;

    public List<ChallengeResponseDto.ChallengeSummary> getChallengeList(ChallengeStatus status) {
        List<Challenge> challenges;
        if (status != null) {
            challenges = challengeRepository.findByStatus(status);
        } else {
            challenges = challengeRepository.findAllByOrderByCreatedAtDesc();
        }

        return challenges.stream()
                .map(ChallengeMapper::toChallengeSummary)
                .collect(Collectors.toList());
    }

    public ChallengeResponseDto.DetailDto getChallengeDetail(Long challengeId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
        return ChallengeMapper.toDetailDto(challenge);
    }

    public List<ChallengeResponseDto.RankingEntry> getChallengeRanking(Long challengeId, int limit) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        List<Entry> entries = entryRepository.findByChallengeIdOrderByVoteCountDesc(challengeId);

        return entries.stream()
                .limit(limit)
                .map(ChallengeMapper::toRankingEntry)
                .collect(Collectors.toList());
    }
}