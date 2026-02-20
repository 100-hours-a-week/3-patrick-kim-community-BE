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

    // ========== 전략 5: Async (SQS) - Phase 10-2b Hybrid ==========
    /**
     * 비동기 투표: 하이브리드 전략 (DB 중복체크 + Redis 랭킹 + SQS 비동기)
     *
     * Phase 10-2b 하이브리드 최적화:
     * - 중복 체크: DB (HikariCP 30개 커넥션 병렬 처리)
     * - 랭킹: Redis (ZINCRBY + ZSCORE = 2회 호출)
     * - SQS 전송: Fire & Forget (비동기)
     *
     * 왜 하이브리드인가?
     * - Redis 4회 호출 (hasVoted + recordVote + incrementVote + getScore)은
     *   Lettuce 단일 커넥션에서 직렬화되어 50명 동시 요청 시 1.3s 소요
     * - DB 중복 체크는 HikariCP 30개 커넥션으로 병렬 처리 가능
     * - Redis 호출을 4회 → 2회로 줄여 병목 완화
     *
     * 흐름:
     * 1. DB 중복 투표 체크 (HikariCP 병렬, ~10ms)
     * 2. Entry 조회 + 검증 (~5ms)
     * 3. Redis 랭킹 업데이트 (ZINCRBY ~5ms)
     * 4. SQS 비동기 전송 (Fire & Forget ~0ms)
     * 5. Redis 득표수 조회 (ZSCORE ~5ms)
     * 6. 즉시 응답 반환
     *
     * 예상 응답시간: p95 ~400ms (50명 동시, 기존 1.3s 대비 70% 개선)
     */
    public VoteResponseDto.VoteResult voteAsync(Long entryId, Integer memberId) {
        // SQS 미설정 시 pessimistic으로 fallback
        if (voteProducer == null) {
            log.warn("[VoteAsync] VoteProducer not configured, falling back to pessimistic");
            return self.votePessimistic(entryId, memberId);
        }

        // 1. DB 중복 투표 체크 (Phase 10-2b: HikariCP 30개 커넥션 병렬 처리)
        if (voteRepository.existsByEntryIdAndMemberId(entryId, memberId)) {
            log.debug("[VoteAsync] Duplicate vote rejected (DB): entryId={}, memberId={}",
                    entryId, memberId);
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        // 2. Entry 조회 + 검증
        Entry entry = entryRepository.findById(entryId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        validateVote(entry, memberId);

        Long challengeId = entry.getChallenge().getId();

        // 3. Redis 랭킹 업데이트 (ZINCRBY)
        rankingRedisService.incrementVote(challengeId, entryId);

        // 4. SQS 메시지 전송 (Fire & Forget - 비동기)
        VoteMessage message = VoteMessage.create(memberId, entryId, challengeId);
        voteProducer.sendVote(message);

        log.debug("[VoteAsync] Vote queued: entryId={}, memberId={}, voteId={}",
                entryId, memberId, message.getVoteId());

        // 5. Redis에서 현재 득표수 조회 (ZSCORE)
        Double redisScore = rankingRedisService.getScore(challengeId, entryId);
        int currentVoteCount = redisScore != null ? redisScore.intValue() : entry.getVoteCount() + 1;

        return VoteResponseDto.VoteResult.builder()
                .entryId(entryId)
                .voteCount(currentVoteCount)
                .build();
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

        // Redis 랭킹 업데이트
        rankingRedisService.incrementVote(entry.getChallenge().getId(), entryId);

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
