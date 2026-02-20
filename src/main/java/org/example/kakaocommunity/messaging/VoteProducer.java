package org.example.kakaocommunity.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQS 투표 메시지 Producer
 *
 * Phase 10: 투표 요청을 SQS로 전송
 * Phase 10-2b: 비동기 Fire & Forget 패턴
 *
 * 조건: SqsTemplate 빈이 있을 때만 활성화
 *
 * 최적화 전략:
 * - 동기 전송 (~300ms) → 비동기 전송 (~0ms 응답)
 * - Fire & Forget: SQS 응답 기다리지 않음
 * - 실패 시 로깅 + 정합성 배치로 복구
 *
 * 흐름:
 * 1. API 요청 수신
 * 2. Redis 즉시 업데이트 (VoteService)
 * 3. SQS 비동기 전송 (이 클래스) → 즉시 리턴
 * 4. API 응답 (50ms 이내)
 */
@Slf4j
@Component
@ConditionalOnBean(SqsTemplate.class)
public class VoteProducer {

    private final SqsTemplate sqsTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService asyncExecutor;

    // 메트릭: 비동기 전송 실패 카운트
    private final AtomicLong asyncFailureCount = new AtomicLong(0);

    @Value("${app.sqs.vote-queue-name:petstar-votes}")
    private String queueName;

    public VoteProducer(SqsTemplate sqsTemplate, ObjectMapper objectMapper) {
        this.sqsTemplate = sqsTemplate;
        this.objectMapper = objectMapper;
        // 비동기 전송용 스레드 풀 (데몬 스레드, 최대 20개)
        this.asyncExecutor = Executors.newFixedThreadPool(20, r -> {
            Thread t = new Thread(r, "sqs-async-sender");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 투표 메시지를 SQS로 비동기 전송 (Fire & Forget)
     *
     * Phase 10-2b: 응답 대기 없이 즉시 리턴
     *
     * @param message 투표 메시지
     */
    public void sendVote(VoteMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("[VoteProducer] Failed to serialize message: {}", e.getMessage());
            return; // 직렬화 실패 시 조용히 실패 (Redis에는 이미 반영됨)
        }

        // Fire & Forget: 비동기로 전송하고 즉시 리턴
        CompletableFuture.runAsync(() -> {
            try {
                sqsTemplate.send(queueName, payload);
                log.debug("[VoteProducer] Async message sent: voteId={}, entryId={}, memberId={}",
                        message.getVoteId(), message.getEntryId(), message.getMemberId());
            } catch (Exception e) {
                // 전송 실패: 로깅만 하고 정합성 배치에서 복구
                asyncFailureCount.incrementAndGet();
                log.error("[VoteProducer] Async send failed (will be recovered by consistency batch): " +
                                "voteId={}, entryId={}, memberId={}, error={}",
                        message.getVoteId(), message.getEntryId(), message.getMemberId(), e.getMessage());
            }
        }, asyncExecutor);

        log.debug("[VoteProducer] Vote queued for async send: voteId={}", message.getVoteId());
    }

    /**
     * 투표 메시지 생성 및 전송 (편의 메서드)
     */
    public void sendVote(Integer memberId, Long entryId, Long challengeId) {
        VoteMessage message = VoteMessage.create(memberId, entryId, challengeId);
        sendVote(message);
    }

    /**
     * 비동기 전송 실패 횟수 조회 (모니터링용)
     */
    public long getAsyncFailureCount() {
        return asyncFailureCount.get();
    }
}
