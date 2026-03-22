package org.example.kakaocommunity.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * SQS 투표 메시지 Producer
 *
 * Phase 10: 투표 요청을 SQS로 전송
 * Phase 11: Fire & Forget → 동기 전송으로 전환 + Micrometer 메트릭
 *
 * 조건: SqsTemplate 빈이 있을 때만 활성화
 *
 * 전환 이유:
 * - Fire & Forget 패턴에서 SQS 전송 실패 시 VoteService의 Redis 롤백 코드가
 *   실행되지 않는 dead code 문제 발견
 * - CompletableFuture.runAsync() 내부에서 예외가 발생해도 호출자에게 전파되지 않음
 * - 동기 전송으로 전환하여 전송 실패 시 VoteService에서 보상 트랜잭션 실행 가능
 * - 같은 리전(ap-northeast-2) 내 SQS RTT ~5-20ms로 응답시간 영향 미미
 *
 * 흐름:
 * 1. API 요청 수신
 * 2. Redis 즉시 업데이트 (VoteService)
 * 3. SQS 동기 전송 (이 클래스) → 실패 시 예외 전파
 * 4. VoteService에서 SQS 실패 감지 → sync DB fallback 또는 Redis 롤백
 */
@Slf4j
@Component
@ConditionalOnBean(SqsTemplate.class)
public class VoteProducer {

    private final SqsTemplate sqsTemplate;
    private final ObjectMapper objectMapper;
    private final Counter sendSuccessCounter;
    private final Counter sendFailureCounter;
    private final Timer sendTimer;

    @Value("${app.sqs.vote-queue-name:petstar-votes}")
    private String queueName;

    public VoteProducer(SqsTemplate sqsTemplate, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.sqsTemplate = sqsTemplate;
        this.objectMapper = objectMapper;
        this.sendSuccessCounter = Counter.builder("sqs.vote.send")
                .tag("result", "success")
                .description("SQS vote message send success count")
                .register(meterRegistry);
        this.sendFailureCounter = Counter.builder("sqs.vote.send")
                .tag("result", "failure")
                .description("SQS vote message send failure count")
                .register(meterRegistry);
        this.sendTimer = Timer.builder("sqs.vote.send.duration")
                .description("SQS vote message send duration")
                .register(meterRegistry);
    }

    /**
     * 투표 메시지를 SQS로 동기 전송
     *
     * Phase 11: 동기 전송으로 전환
     * - 전송 실패 시 예외가 호출자(VoteService)에 전파됨
     * - VoteService에서 SQS 장애 감지 → sync DB fallback 처리
     *
     * @param message 투표 메시지
     * @throws RuntimeException SQS 전송 실패 또는 직렬화 실패 시
     */
    public void sendVote(VoteMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            sendFailureCounter.increment();
            log.error("[VoteProducer] Failed to serialize message: {}", e.getMessage());
            throw new RuntimeException("Vote message serialization failed", e);
        }

        try {
            sendTimer.record(() -> sqsTemplate.send(queueName, payload));
            sendSuccessCounter.increment();
            log.debug("[VoteProducer] Message sent: voteId={}, entryId={}, memberId={}",
                    message.getVoteId(), message.getEntryId(), message.getMemberId());
        } catch (Exception e) {
            sendFailureCounter.increment();
            throw e;
        }
    }

    /**
     * 투표 메시지 생성 및 전송 (편의 메서드)
     */
    public void sendVote(Integer memberId, Long entryId, Long challengeId) {
        VoteMessage message = VoteMessage.create(memberId, entryId, challengeId);
        sendVote(message);
    }
}
