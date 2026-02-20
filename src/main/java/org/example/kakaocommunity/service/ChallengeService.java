package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.dto.response.ChallengeResponseDto;
import org.example.kakaocommunity.entity.Challenge;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.mapper.ChallengeMapper;
import org.example.kakaocommunity.repository.ChallengeRepository;
import org.example.kakaocommunity.repository.EntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final EntryRepository entryRepository;
    private final RankingRedisService rankingRedisService;

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

    /**
     * 랭킹 조회 - Redis Sorted Set 활용
     *
     * 1. Redis에 랭킹 데이터 없으면 DB에서 초기화
     * 2. Redis에서 상위 N개 entryId 조회
     * 3. DB에서 Entry 상세 정보 조회 (Fetch Join)
     * 4. Redis 순서대로 정렬하여 반환
     */
    public List<ChallengeResponseDto.RankingEntry> getChallengeRanking(Long challengeId, int limit) {
        challengeRepository.findById(challengeId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        // Redis에 데이터 없으면 DB에서 초기화
        if (!rankingRedisService.hasRanking(challengeId)) {
            initRankingToRedis(challengeId);
        }

        // Redis에서 상위 N개 entryId 조회
        List<Long> topEntryIds = rankingRedisService.getTopEntryIds(challengeId, limit);

        if (topEntryIds.isEmpty()) {
            return List.of();
        }

        // DB에서 Entry 상세 정보 조회 (Fetch Join)
        List<Entry> entries = entryRepository.findEntriesByIdsWithFetchJoin(topEntryIds);

        // Redis 순서대로 정렬
        Map<Long, Entry> entryMap = entries.stream()
                .collect(Collectors.toMap(Entry::getId, Function.identity()));

        return topEntryIds.stream()
                .map(entryMap::get)
                .filter(entry -> entry != null)
                .map(entry -> {
                    // Redis에서 실시간 voteCount 가져오기
                    int voteCount = rankingRedisService.getVoteCount(challengeId, entry.getId());
                    return ChallengeMapper.toRankingEntryWithVoteCount(entry, voteCount);
                })
                .collect(Collectors.toList());
    }

    /**
     * DB 데이터를 Redis에 초기화 (캐시 워밍업)
     */
    private void initRankingToRedis(Long challengeId) {
        log.info("[Redis] Initializing ranking for challengeId={}", challengeId);
        List<Entry> allEntries = entryRepository.findByChallengeId(challengeId);

        for (Entry entry : allEntries) {
            rankingRedisService.initEntry(challengeId, entry.getId(), entry.getVoteCount());
        }
        log.info("[Redis] Initialized {} entries for challengeId={}", allEntries.size(), challengeId);
    }
}