package org.example.kakaocommunity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.dto.response.VoteResponseDto;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.entity.Vote;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.messaging.VoteMessage;
import org.example.kakaocommunity.messaging.VoteProducer;
import org.example.kakaocommunity.repository.EntryRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.VoteRepository;
import java.util.List;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 투표 서비스 - 동시성 제어 전략 비교
 *
 * 다섯 가지 전략:
 * 1. Pessimistic Lock: SELECT FOR UPDATE로 행 락
 * 2. Optimistic Lock: @Version으로 충돌 감지 + 재시도
 * 3. Atomic: 원자적 UPDATE + UK 예외 처리 (재시도 없음)
 * 4. Atomic + Retry: 원자적 UPDATE + 데드락 재시도
 * 5. Async (SQS): Redis 즉시 + SQS 비동기 DB 저장 (Phase 10)
 */
@Slf4j
@Service
public class VoteService {

    private final VoteRepository voteRepository;
    private final EntryRepository entryRepository;
    private final MemberRepository memberRepository;
    private final TransactionTemplate transactionTemplate;
    private final RankingRedisService rankingRedisService;
    private final VoteProducer voteProducer;

    // Self-injection to enable @Transactional on internal method calls
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private VoteService self;

    public VoteService(
            VoteRepository voteRepository,
            EntryRepository entryRepository,
            MemberRepository memberRepository,
            TransactionTemplate transactionTemplate,
            RankingRedisService rankingRedisService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) VoteProducer voteProducer) {
        this.voteRepository = voteRepository;
        this.entryRepository = entryRepository;
        this.memberRepository = memberRepository;
        this.transactionTemplate = transactionTemplate;
        this.rankingRedisService = rankingRedisService;
        this.voteProducer = voteProducer;
    }

    private static final int MAX_RETRY_COUNT = 5;
    private static final long INITIAL_BACKOFF_MS = 100;
    private static final double JITTER_FACTOR = 0.5;  // ±50% 랜덤 지터

    /**
     * 전략에 따른 투표 처리
     *
     * self를 통해 호출하여 @Transactional이 적용되도록 함 (self-invocation 문제 해결)
     */
    public VoteResponseDto.VoteResult vote(Long entryId, Integer memberId, String strategy) {
        return switch (strategy.toLowerCase()) {
            case "pessimistic" -> self.votePessimistic(entryId, memberId);
            case "optimistic" -> self.voteOptimistic(entryId, memberId);
            case "atomic" -> self.voteAtomicSimple(entryId, memberId);
            case "atomic_retry" -> self.voteAtomicWithRetry(entryId, memberId);
            case "async" -> self.voteAsync(entryId, memberId);  // Phase 10: SQS 비동기
            default -> self.voteAsync(entryId, memberId);  // 기본값: async (Phase 10)
        };
    }

    /**
     * 기존 메서드 호환성 유지
     *
     * Phase 10: SQS 설정되어 있으면 async, 아니면 pessimistic
     * - async: Redis 즉시 반영 + SQS 비동기 DB 저장 (응답시간 50ms)
     * - pessimistic: 동기 DB 저장 (응답시간 1.73s, SQS 없을 때 fallback)
     */
    public VoteResponseDto.VoteResult vote(Long entryId, Integer memberId) {
        String strategy = (voteProducer != null) ? "async" : "pessimistic";
        return vote(entryId, memberId, strategy);
    }

    // ========== 전략 5: Async (SQS) + Circuit Breaker ==========
    /**
     * 비동기 투표: Redis 중복체크 + 랭킹 + SQS 비동기
     *
     * Circuit Breaker:
     * - CLOSED: Redis + SQS 비동기 투표 (정상)
     * - OPEN: Redis 장애 감지 → 자동으로 Pessimistic Lock Fallback
     * - HALF_OPEN: Redis 복구 확인 후 비동기 복귀
     *
     * Graceful Degradation (Phase 11):
     * - SQS 전송 실패 시 → sync DB fallback (Redis 롤백하지 않고 DB 직접 저장)
     * - Redis 장애 시 → Circuit Breaker → votePessimistic()
     * - 둘 다 장애 시 → DB-only 모드 (votePessimistic + Redis best-effort)
     *
     * Pipeline (1 DB + 1 Redis RTT):
     * - Entry 조회: DB findById
     * - 중복체크 + 랭킹: Redis Pipeline (SADD + ZINCRBY + ZSCORE = 1 RTT)
     * - SQS 전송: 실패 시 sync DB fallback
     */
    @CircuitBreaker(name = "redisVote", fallbackMethod = "voteFallback")
    public VoteResponseDto.VoteResult voteAsync(Long entryId, Integer memberId) {
        // SQS 미설정 시 pessimistic으로 fallback
        if (voteProducer == null) {
            log.warn("[VoteAsync] VoteProducer not configured, falling back to pessimistic");
            return self.votePessimistic(entryId, memberId);
        }

        // 1. Entry 조회 + 검증 (DB 1회 — 유일한 DB 호출)
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
        validateVote(entry, memberId);

        Long challengeId = entry.getChallenge().getId();

        // 2. Redis Pipeline: SADD(중복체크) + ZINCRBY(랭킹) + ZSCORE(점수) = 1 RTT
        List<Object> results = rankingRedisService.recordAndIncrementPipelined(entryId, memberId, challengeId);

        // results[0] = SADD 결과 (Long): 1=신규, 0=중복
        // results[1] = ZINCRBY 결과 (Double)
        // results[2] = ZSCORE 결과 (Double)
        Long added = (Long) results.get(0);
        if (added == 0L) {
            // 중복 투표 — Pipeline에서 이미 ZINCRBY한 것을 롤백
            rankingRedisService.decrementVote(challengeId, entryId);
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        Double score = (Double) results.get(2);
        int currentVoteCount = score != null ? score.intValue() : 0;

        // 3. SQS 동기 전송 + 실패 시 sync DB fallback
        VoteMessage message = VoteMessage.create(memberId, entryId, challengeId);
        try {
            voteProducer.sendVote(message);
        } catch (Exception e) {
            // SQS 장애 → Redis는 이미 반영됨, DB에 직접 동기 저장으로 fallback
            log.warn("[VoteAsync] SQS unavailable, falling back to sync DB write: entryId={}, memberId={}, error={}",
                    entryId, memberId, e.getMessage());
            syncWriteToDb(entryId, memberId);
        }

        log.debug("[VoteAsync] Vote processed: entryId={}, memberId={}, voteId={}",
                entryId, memberId, message.getVoteId());

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(currentVoteCount)
                .build();
    }

    /**
     * Circuit Breaker Fallback: Redis 장애 시 Pessimistic Lock으로 자동 전환
     *
     * OPEN 상태에서 호출됨:
     * - 느리지만(p95 1.73s) 서비스 중단 없이 투표 처리
     * - DB Lock 기반이므로 Redis 없이도 정합성 보장
     * - Redis 복구 후 HALF_OPEN → CLOSED로 자동 복귀
     */
    public VoteResponseDto.VoteResult voteFallback(Long entryId, Integer memberId, Exception e) {
        log.warn("[CircuitBreaker] Redis unavailable, falling back to pessimistic lock: entryId={}, cause={}",
                entryId, e.getClass().getSimpleName());
        return self.votePessimistic(entryId, memberId);
    }

    // ========== 전략 1: Pessimistic Lock ==========
    /**
     * 비관적 락: SELECT FOR UPDATE로 행을 락한 후 처리
     *
     * 장점: 확실한 동시성 보장
     * 단점: 락 대기 시간, Hot Spot에서 병목
     */
    @Transactional
    public VoteResponseDto.VoteResult votePessimistic(Long entryId, Integer memberId) {
        // SELECT FOR UPDATE - 행 락 획득
        Entry entry = entryRepository.findByIdWithPessimisticLock(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        validateVote(entry, memberId);

        // 중복 투표 체크 (락 내에서 안전하게)
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

        // Redis 랭킹 업데이트 — best-effort (Redis 장애 시에도 DB 저장은 유지)
        // Circuit Breaker fallback으로 이 메서드가 호출될 때 Redis가 아직 죽어있을 수 있음
        // Redis 실패 시 정합성 스케줄러(5분 주기)가 DB 기준으로 동기화
        try {
            rankingRedisService.incrementVote(entry.getChallenge().getId(), entryId);
        } catch (Exception ex) {
            log.warn("[VotePessimistic] Redis update failed, will be synced by consistency scheduler: entryId={}", entryId);
        }

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(entry.getVoteCount())
                .build();
    }

    // ========== 전략 2: Optimistic Lock ==========
    /**
     * 낙관적 락: @Version으로 충돌 감지, 실패 시 재시도
     *
     * 장점: 락 없이 동작
     * 단점: 충돌 시 재시도 필요, Hot Spot에서 재시도 폭증
     */
    public VoteResponseDto.VoteResult voteOptimistic(Long entryId, Integer memberId) {
        int retryCount = 0;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                return self.doVoteOptimistic(entryId, memberId);
            } catch (ObjectOptimisticLockingFailureException e) {
                retryCount++;
                log.warn("[Optimistic] Version conflict, retry {}/{} for entryId={}",
                        retryCount, MAX_RETRY_COUNT, entryId);

                if (retryCount >= MAX_RETRY_COUNT) {
                    log.error("[Optimistic] Max retries exceeded for entryId={}", entryId);
                    throw new GeneralException(ErrorStatus._INTERNAL_SERVER_ERROR);
                }

                sleep(calculateBackoff(retryCount));
            }
        }

        throw new GeneralException(ErrorStatus._INTERNAL_SERVER_ERROR);
    }

    @Transactional
    public VoteResponseDto.VoteResult doVoteOptimistic(Long entryId, Integer memberId) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        validateVote(entry, memberId);

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
        entry.increaseVoteCount();  // @Version이 충돌 감지

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(entry.getVoteCount())
                .build();
    }

    // ========== 전략 3: Atomic (재시도 없음) ==========
    /**
     * 원자적 쿼리 + UK 예외 처리 (재시도 없음)
     *
     * 장점: 락 없이 빠름, DB 레벨 보장
     * 단점: 데드락 시 실패, 재시도 없음
     */
    @Transactional
    public VoteResponseDto.VoteResult voteAtomicSimple(Long entryId, Integer memberId) {
        return doVoteAtomic(entryId, memberId);
    }

    // ========== 전략 4: Atomic + Retry (권장) ==========
    /**
     * 원자적 쿼리 + UK 예외 처리 + 데드락 재시도
     *
     * 장점: 락 없이 빠름, DB 레벨 보장, 데드락 복구
     * 단점: 재시도 로직 복잡성
     *
     * 데드락 재시도 전략:
     * - 최대 3회 재시도
     * - 지수 백오프: 50ms → 100ms → 200ms
     */
    public VoteResponseDto.VoteResult voteAtomicWithRetry(Long entryId, Integer memberId) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRY_COUNT) {
            try {
                // TransactionTemplate으로 새 트랜잭션에서 실행
                VoteResponseDto.VoteResult result = transactionTemplate.execute(status -> {
                    return doVoteAtomic(entryId, memberId);
                });

                if (retryCount > 0) {
                    log.info("[Atomic+Retry] Succeeded after {} retries for entryId={}",
                            retryCount, entryId);
                }

                return result;

            } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                retryCount++;
                lastException = e;

                log.warn("[Atomic+Retry] Deadlock detected, retry {}/{} for entryId={}",
                        retryCount, MAX_RETRY_COUNT, entryId);

                if (retryCount >= MAX_RETRY_COUNT) {
                    log.error("[Atomic+Retry] Max retries exceeded for entryId={}", entryId);
                    throw new GeneralException(ErrorStatus._INTERNAL_SERVER_ERROR);
                }

                // 지수 백오프
                sleep(calculateBackoff(retryCount));

            } catch (GeneralException e) {
                // 비즈니스 예외는 재시도 없이 그대로 throw
                throw e;
            }
        }

        log.error("[Atomic+Retry] Unexpected state after retries", lastException);
        throw new GeneralException(ErrorStatus._INTERNAL_SERVER_ERROR);
    }

    /**
     * 실제 투표 로직 (Atomic 방식)
     */
    private VoteResponseDto.VoteResult doVoteAtomic(Long entryId, Integer memberId) {
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        validateVote(entry, memberId);

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Vote vote = Vote.builder()
                .entry(entry)
                .member(member)
                .build();

        // UK 제약조건으로 중복 투표 방지 (DB 레벨)
        try {
            voteRepository.saveAndFlush(vote);
        } catch (DataIntegrityViolationException e) {
            // UK 위반 = 이미 투표함
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        // 원자적 voteCount 증가 (Lost Update 방지)
        entryRepository.incrementVoteCount(entryId);

        // 응답용 최신 voteCount 조회
        int currentVoteCount = entryRepository.getVoteCount(entryId);

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(currentVoteCount)
                .build();
    }

    // ========== SQS 장애 시 동기 DB 저장 ==========

    /**
     * SQS 전송 실패 시 DB에 직접 동기 저장
     *
     * Phase 11: Graceful Degradation
     * - Redis에는 이미 투표가 반영된 상태
     * - SQS를 거치지 않고 Consumer와 동일한 로직으로 DB에 직접 저장
     * - UK 제약조건으로 중복 방지
     */
    private void syncWriteToDb(Long entryId, Integer memberId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 이미 DB에 저장되어 있으면 무시 (idempotency)
                if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
                    return;
                }

                Entry entry = entryRepository.findById(entryId).orElse(null);
                Member member = memberRepository.findById(memberId).orElse(null);
                if (entry == null || member == null) {
                    log.warn("[SyncWrite] Entry or Member not found: entryId={}, memberId={}", entryId, memberId);
                    return;
                }

                Vote vote = Vote.builder()
                        .entry(entry)
                        .member(member)
                        .build();
                voteRepository.save(vote);
                entryRepository.incrementVoteCount(entryId);
            });
            log.info("[SyncWrite] Vote saved directly to DB: entryId={}, memberId={}", entryId, memberId);
        } catch (DataIntegrityViolationException e) {
            // UK 위반 = 이미 저장됨, 무시
            log.debug("[SyncWrite] Duplicate vote ignored: entryId={}, memberId={}", entryId, memberId);
        } catch (Exception e) {
            log.error("[SyncWrite] Failed to save vote to DB: entryId={}, memberId={}, error={}",
                    entryId, memberId, e.getMessage());
        }
    }

    // ========== 공통 ==========

    private void validateVote(Entry entry, Integer memberId) {
        if (entry.getChallenge().getStatus() != ChallengeStatus.ACTIVE) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        if (entry.getMember().getId().equals(memberId)) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }
    }

    /**
     * 지수 백오프 + 지터 계산
     * 동시에 재시도하는 것을 방지하기 위해 랜덤 지터 추가
     *
     * retry 1: 100ms ± 50ms (50~150ms)
     * retry 2: 200ms ± 100ms (100~300ms)
     * retry 3: 400ms ± 200ms (200~600ms)
     * retry 4: 800ms ± 400ms (400~1200ms)
     * retry 5: 1600ms ± 800ms (800~2400ms)
     */
    private long calculateBackoff(int retryCount) {
        long baseBackoff = INITIAL_BACKOFF_MS * (long) Math.pow(2, retryCount - 1);
        // 지터: ±JITTER_FACTOR 범위의 랜덤 값
        double jitter = 1.0 + (Math.random() * 2 - 1) * JITTER_FACTOR;
        return (long) (baseBackoff * jitter);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Transactional
    public VoteResponseDto.VoteResult cancelVote(Long entryId, Integer memberId) {
        Vote vote = voteRepository.findByEntryIdAndMemberId(entryId, memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Long challengeId = vote.getEntry().getChallenge().getId();

        voteRepository.delete(vote);
        entryRepository.decrementVoteCount(entryId);

        // Redis 랭킹 감소 (Phase 10-2b: 투표 기록은 DB에서 관리)
        rankingRedisService.decrementVote(challengeId, entryId);

        int currentVoteCount = entryRepository.getVoteCount(entryId);

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(currentVoteCount)
                .build();
    }
}
