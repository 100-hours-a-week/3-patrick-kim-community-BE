package org.example.kakaocommunity.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.repository.ChallengeRepository;
import org.example.kakaocommunity.repository.EntryRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis ↔ DB 투표 수 정합성 검증 스케줄러 (Phase 10-3)
 *
 * 5분 주기로 활성 챌린지의 Redis 랭킹 점수와 DB voteCount를 비교하여
 * 불일치 시 DB 기준으로 Redis를 동기화한다.
 *
 * Redis > DB인 경우는 SQS 처리 지연일 수 있으므로 30초 대기 후 재검증한다.
 *
 * Phase 11: Micrometer 메트릭 추가
 */
@Slf4j
@Component
public class VoteConsistencyScheduler {

    private final ChallengeRepository challengeRepository;
    private final EntryRepository entryRepository;
    private final RankingRedisService rankingRedisService;
    private final Counter checkedCounter;
    private final Counter fixedCounter;

    public VoteConsistencyScheduler(
            ChallengeRepository challengeRepository,
            EntryRepository entryRepository,
            RankingRedisService rankingRedisService,
            MeterRegistry meterRegistry) {
        this.challengeRepository = challengeRepository;
        this.entryRepository = entryRepository;
        this.rankingRedisService = rankingRedisService;
        this.checkedCounter = Counter.builder("vote.consistency.checked")
                .description("Number of entries checked for Redis-DB consistency")
                .register(meterRegistry);
        this.fixedCounter = Counter.builder("vote.consistency.fixed")
                .description("Number of entries where Redis was synced to DB")
                .register(meterRegistry);
    }

    @Scheduled(fixedRate = 300000) // 5분 주기
    public void verifyConsistency() {
        log.info("[Consistency] Starting Redis ↔ DB vote count verification");

        var activeChallenges = challengeRepository.findByStatus(ChallengeStatus.ACTIVE);

        int totalChecked = 0;
        int totalFixed = 0;

        for (var challenge : activeChallenges) {
            Long challengeId = challenge.getId();

            if (!rankingRedisService.hasRanking(challengeId)) {
                continue;
            }

            List<Entry> entries = entryRepository.findByChallengeId(challengeId);

            for (Entry entry : entries) {
                totalChecked++;
                int dbCount = entry.getVoteCount();
                int redisCount = rankingRedisService.getVoteCount(challengeId, entry.getId());

                if (dbCount == redisCount) {
                    continue;
                }

                if (redisCount > dbCount) {
                    // Redis > DB: SQS 처리 지연 가능성 → 30초 대기 후 재검증
                    log.info("[Consistency] Redis({}) > DB({}) for entry={}, waiting 30s for SQS lag",
                            redisCount, dbCount, entry.getId());

                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    // 재검증: DB 값 다시 조회
                    int dbCountRetry = entryRepository.getVoteCount(entry.getId());
                    int redisCountRetry = rankingRedisService.getVoteCount(challengeId, entry.getId());

                    if (dbCountRetry == redisCountRetry) {
                        log.info("[Consistency] Resolved after SQS catch-up: entry={}, count={}",
                                entry.getId(), dbCountRetry);
                        continue;
                    }

                    // 여전히 불일치 → DB 기준 동기화
                    rankingRedisService.initEntry(challengeId, entry.getId(), dbCountRetry);
                    totalFixed++;
                    log.warn("[Consistency] Fixed (post-wait): entry={}, redis {} → db {}",
                            entry.getId(), redisCountRetry, dbCountRetry);
                } else {
                    // Redis < DB: 데이터 유실 → 즉시 DB 기준 동기화
                    rankingRedisService.initEntry(challengeId, entry.getId(), dbCount);
                    totalFixed++;
                    log.warn("[Consistency] Fixed: entry={}, redis {} → db {}",
                            entry.getId(), redisCount, dbCount);
                }
            }
        }

        checkedCounter.increment(totalChecked);
        fixedCounter.increment(totalFixed);
        log.info("[Consistency] Completed: checked={}, fixed={}", totalChecked, totalFixed);
    }
}
