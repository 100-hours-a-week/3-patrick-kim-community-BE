package org.example.kakaocommunity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Redis Sorted Set을 활용한 실시간 랭킹 서비스
 *
 * 키 구조: ranking:challenge:{challengeId}
 * 값: entryId (member), voteCount (score)
 *
 * 장점:
 * - O(log N) 시간에 랭킹 업데이트/조회
 * - DB 부하 없이 실시간 랭킹 제공
 * - 투표 시 ZINCRBY로 원자적 증가
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingRedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    private static final String RANKING_KEY_PREFIX = "ranking:challenge:";
    private static final String VOTES_KEY_PREFIX = "votes:";

    // ========== Phase 10-2: 투표 기록 (중복 체크용) ==========

    /**
     * 중복 투표 체크 (Redis SET 조회)
     *
     * Phase 10-2: DB 쿼리 대신 Redis O(1) 조회
     * - 키: votes:{entryId}
     * - 값: memberId SET
     *
     * @return true if already voted
     */
    public boolean hasVoted(Long entryId, Integer memberId) {
        String key = VOTES_KEY_PREFIX + entryId;
        Boolean result = redisTemplate.opsForSet().isMember(key, memberId.toString());
        log.debug("[Redis] SISMEMBER {} {} → {}", key, memberId, result);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 투표 기록 추가 (Redis SET)
     *
     * Phase 10-2: 투표 시 memberId를 SET에 추가
     * - incrementVote()와 함께 호출
     * - 원자성: Redis는 단일 명령 원자적
     */
    public void recordVote(Long entryId, Integer memberId) {
        String key = VOTES_KEY_PREFIX + entryId;
        redisTemplate.opsForSet().add(key, memberId.toString());
        log.debug("[Redis] SADD {} {}", key, memberId);
    }

    /**
     * 투표 기록 삭제 (투표 취소 시)
     */
    public void removeVoteRecord(Long entryId, Integer memberId) {
        String key = VOTES_KEY_PREFIX + entryId;
        redisTemplate.opsForSet().remove(key, memberId.toString());
        log.debug("[Redis] SREM {} {}", key, memberId);
    }

    /**
     * Entry의 투표자 수 조회 (검증용)
     */
    public long getVoterCount(Long entryId) {
        String key = VOTES_KEY_PREFIX + entryId;
        Long size = redisTemplate.opsForSet().size(key);
        return size != null ? size : 0;
    }

    // ========== 기존 메서드 ==========

    /**
     * 투표 시 해당 Entry의 점수를 1 증가
     */
    public void incrementVote(Long challengeId, Long entryId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        redisTemplate.opsForZSet().incrementScore(key, entryId.toString(), 1);
        log.debug("[Redis] ZINCRBY {} {} 1", key, entryId);
    }

    /**
     * 투표 취소 시 해당 Entry의 점수를 1 감소
     */
    public void decrementVote(Long challengeId, Long entryId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        redisTemplate.opsForZSet().incrementScore(key, entryId.toString(), -1);
        log.debug("[Redis] ZINCRBY {} {} -1", key, entryId);
    }

    /**
     * 상위 N개 Entry ID 조회 (점수 내림차순)
     */
    public List<Long> getTopEntryIds(Long challengeId, int limit) {
        String key = RANKING_KEY_PREFIX + challengeId;
        Set<Object> result = redisTemplate.opsForZSet()
                .reverseRange(key, 0, limit - 1);

        if (result == null || result.isEmpty()) {
            return Collections.emptyList();
        }

        return result.stream()
                .map(obj -> Long.parseLong(obj.toString()))
                .toList();
    }

    /**
     * 상위 N개 Entry와 점수 조회
     */
    public List<ZSetOperations.TypedTuple<Object>> getTopEntriesWithScores(Long challengeId, int limit) {
        String key = RANKING_KEY_PREFIX + challengeId;
        Set<ZSetOperations.TypedTuple<Object>> result = redisTemplate.opsForZSet()
                .reverseRangeWithScores(key, 0, limit - 1);

        if (result == null) {
            return Collections.emptyList();
        }

        return result.stream().toList();
    }

    /**
     * 특정 Entry의 현재 점수(투표 수) 조회
     */
    public int getVoteCount(Long challengeId, Long entryId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        Double score = redisTemplate.opsForZSet().score(key, entryId.toString());
        return score != null ? score.intValue() : 0;
    }

    /**
     * 특정 Entry의 점수 조회 (Double 반환, null 가능)
     * Phase 10: 비동기 투표에서 실시간 득표수 확인용
     */
    public Double getScore(Long challengeId, Long entryId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        return redisTemplate.opsForZSet().score(key, entryId.toString());
    }

    /**
     * 특정 Entry의 현재 순위 조회 (0-based)
     */
    public Long getRank(Long challengeId, Long entryId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        return redisTemplate.opsForZSet().reverseRank(key, entryId.toString());
    }

    /**
     * Entry 초기화 (신규 참가 시)
     */
    public void initEntry(Long challengeId, Long entryId, int initialVoteCount) {
        String key = RANKING_KEY_PREFIX + challengeId;
        redisTemplate.opsForZSet().add(key, entryId.toString(), initialVoteCount);
        log.debug("[Redis] ZADD {} {} {}", key, initialVoteCount, entryId);
    }

    /**
     * Entry 삭제 (참가 취소 시)
     */
    public void removeEntry(Long challengeId, Long entryId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        redisTemplate.opsForZSet().remove(key, entryId.toString());
        log.debug("[Redis] ZREM {} {}", key, entryId);
    }

    /**
     * 챌린지 랭킹 전체 삭제
     */
    public void clearRanking(Long challengeId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        redisTemplate.delete(key);
        log.debug("[Redis] DEL {}", key);
    }

    /**
     * 챌린지 랭킹 존재 여부 확인
     */
    public boolean hasRanking(Long challengeId) {
        String key = RANKING_KEY_PREFIX + challengeId;
        Long size = redisTemplate.opsForZSet().size(key);
        return size != null && size > 0;
    }
}
