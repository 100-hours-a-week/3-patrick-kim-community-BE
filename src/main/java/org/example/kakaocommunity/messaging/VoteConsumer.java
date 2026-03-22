package org.example.kakaocommunity.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.entity.Vote;
import org.example.kakaocommunity.repository.EntryRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.VoteRepository;
import org.example.kakaocommunity.service.RankingRedisService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SQS 투표 메시지 Consumer
 *
 * Phase 10: SQS에서 투표 메시지를 소비하여 DB에 저장
 * Phase 11: Micrometer 메트릭 추가
 *
 * 조건: SqsTemplate 빈이 있을 때만 활성화
 *
 * 특징:
 * - 비동기 처리: API 응답과 분리
 * - Idempotency: 중복 메시지 안전하게 처리 (DB UK 제약)
 * - DB Lock 없음: 순차 처리로 경합 제거
 * - 검증 실패 시 Redis 랭킹 롤백 (투표 기록은 DB에서 관리)
 */
@Slf4j
@Component
@ConditionalOnBean(SqsTemplate.class)
public class VoteConsumer {

    private final VoteRepository voteRepository;
    private final EntryRepository entryRepository;
    private final MemberRepository memberRepository;
    private final RankingRedisService rankingRedisService;
    private final ObjectMapper objectMapper;
    private final Counter consumeSuccessCounter;
    private final Counter consumeDuplicateCounter;
    private final Counter consumeErrorCounter;
    private final Counter consumeRollbackCounter;
    private final Timer consumeTimer;

    public VoteConsumer(
            VoteRepository voteRepository,
            EntryRepository entryRepository,
            MemberRepository memberRepository,
            RankingRedisService rankingRedisService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.voteRepository = voteRepository;
        this.entryRepository = entryRepository;
        this.memberRepository = memberRepository;
        this.rankingRedisService = rankingRedisService;
        this.objectMapper = objectMapper;
        this.consumeSuccessCounter = Counter.builder("sqs.vote.consume")
                .tag("result", "success")
                .description("Vote messages successfully processed")
                .register(meterRegistry);
        this.consumeDuplicateCounter = Counter.builder("sqs.vote.consume")
                .tag("result", "duplicate")
                .description("Duplicate vote messages ignored")
                .register(meterRegistry);
        this.consumeErrorCounter = Counter.builder("sqs.vote.consume")
                .tag("result", "error")
                .description("Vote messages failed to process")
                .register(meterRegistry);
        this.consumeRollbackCounter = Counter.builder("sqs.vote.consume")
                .tag("result", "rollback")
                .description("Vote messages that required Redis rollback")
                .register(meterRegistry);
        this.consumeTimer = Timer.builder("sqs.vote.consume.duration")
                .description("Vote message processing duration")
                .register(meterRegistry);
    }

    /**
     * SQS 메시지 리스너
     *
     * @SqsListener 특징:
     * - 자동 메시지 삭제 (성공 시)
     * - 예외 발생 시 visibility timeout 후 재시도
     * - maxReceiveCount 초과 시 DLQ로 이동
     */
    @SqsListener("${app.sqs.vote-queue-name:petstar-votes}")
    @Transactional
    public void handleVote(String payload) {
        Timer.Sample sample = Timer.start();

        VoteMessage message;
        try {
            message = objectMapper.readValue(payload, VoteMessage.class);
        } catch (Exception e) {
            consumeErrorCounter.increment();
            log.error("[VoteConsumer] Failed to deserialize message: {}", payload, e);
            return;
        }

        log.debug("[VoteConsumer] Processing vote: voteId={}, entryId={}, memberId={}",
                message.getVoteId(), message.getEntryId(), message.getMemberId());

        // Idempotency: 이미 투표했으면 무시
        if (voteRepository.existsByEntryIdAndMemberId(message.getEntryId(), message.getMemberId())) {
            consumeDuplicateCounter.increment();
            log.info("[VoteConsumer] Duplicate vote ignored: entryId={}, memberId={}",
                    message.getEntryId(), message.getMemberId());
            sample.stop(consumeTimer);
            return;
        }

        // Entry, Member 조회 + 검증 실패 시 Redis 롤백
        Entry entry = entryRepository.findById(message.getEntryId()).orElse(null);
        if (entry == null) {
            consumeRollbackCounter.increment();
            log.warn("[VoteConsumer] Entry not found, rolling back Redis: entryId={}", message.getEntryId());
            rollbackRedis(message);
            sample.stop(consumeTimer);
            return;
        }

        Member member = memberRepository.findById(message.getMemberId()).orElse(null);
        if (member == null) {
            consumeRollbackCounter.increment();
            log.warn("[VoteConsumer] Member not found, rolling back Redis: memberId={}", message.getMemberId());
            rollbackRedis(message);
            sample.stop(consumeTimer);
            return;
        }

        // Vote 저장
        try {
            Vote vote = Vote.builder()
                    .entry(entry)
                    .member(member)
                    .build();

            voteRepository.save(vote);

            // Atomic Update: vote_count 증가
            entryRepository.incrementVoteCount(message.getEntryId());

            consumeSuccessCounter.increment();
            log.info("[VoteConsumer] Vote saved: voteId={}, entryId={}, memberId={}",
                    message.getVoteId(), message.getEntryId(), message.getMemberId());

        } catch (DataIntegrityViolationException e) {
            // UK 위반 = 중복 투표 (Race condition에서 발생 가능)
            consumeDuplicateCounter.increment();
            log.info("[VoteConsumer] Duplicate vote (UK violation): entryId={}, memberId={}",
                    message.getEntryId(), message.getMemberId());
        } catch (CannotAcquireLockException e) {
            consumeErrorCounter.increment();
            // 데드락 발생 시 예외를 던져서 SQS 재시도 유도
            log.warn("[VoteConsumer] Deadlock detected, will retry: entryId={}, memberId={}",
                    message.getEntryId(), message.getMemberId());
            throw e;  // SQS visibility timeout 후 재시도
        } finally {
            sample.stop(consumeTimer);
        }
    }

    /**
     * 검증 실패 시 Redis 랭킹 롤백
     */
    private void rollbackRedis(VoteMessage message) {
        try {
            rankingRedisService.decrementVote(message.getChallengeId(), message.getEntryId());
            log.info("[VoteConsumer] Redis rollback completed: entryId={}, challengeId={}",
                    message.getEntryId(), message.getChallengeId());
        } catch (Exception e) {
            log.error("[VoteConsumer] Redis rollback failed: entryId={}, challengeId={}, error={}",
                    message.getEntryId(), message.getChallengeId(), e.getMessage());
        }
    }
}
