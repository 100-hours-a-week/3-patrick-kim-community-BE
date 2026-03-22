package org.example.kakaocommunity.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.entity.Entry;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.entity.Vote;
import org.example.kakaocommunity.repository.EntryRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.VoteRepository;
import org.example.kakaocommunity.service.RankingRedisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

/**
 * DLQ (Dead Letter Queue) 재처리 스케줄러
 *
 * Phase 11: 외부 서비스 장애 대응
 *
 * 동작:
 * - 30분 주기로 DLQ를 폴링하여 실패 메시지를 재처리
 * - 재큐잉하지 않고 직접 DB에 저장 (순환 방지)
 * - 최대 10건/회 처리 (시스템 부하 방지)
 *
 * DLQ에 메시지가 도달하는 경우:
 * 1. Consumer에서 maxReceiveCount(3회) 초과 실패
 * 2. 역직렬화 불가능한 메시지
 * 3. 지속적인 DB 오류 (연결 불가 등)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.cloud.aws.sqs.enabled", havingValue = "true", matchIfMissing = false)
public class DlqReprocessor {

    private final SqsAsyncClient sqsAsyncClient;
    private final ObjectMapper objectMapper;
    private final VoteRepository voteRepository;
    private final EntryRepository entryRepository;
    private final MemberRepository memberRepository;
    private final RankingRedisService rankingRedisService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.sqs.dlq-name:petstar-votes-dlq}")
    private String dlqName;

    private static final int MAX_MESSAGES_PER_CYCLE = 10;

    // 마지막 재처리 결과 (모니터링용)
    private volatile long lastReprocessTime;
    private volatile int lastReprocessedCount;
    private volatile int lastFailedCount;

    public DlqReprocessor(
            SqsAsyncClient sqsAsyncClient,
            ObjectMapper objectMapper,
            VoteRepository voteRepository,
            EntryRepository entryRepository,
            MemberRepository memberRepository,
            RankingRedisService rankingRedisService,
            TransactionTemplate transactionTemplate) {
        this.sqsAsyncClient = sqsAsyncClient;
        this.objectMapper = objectMapper;
        this.voteRepository = voteRepository;
        this.entryRepository = entryRepository;
        this.memberRepository = memberRepository;
        this.rankingRedisService = rankingRedisService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 30분 주기로 DLQ 재처리
     */
    @Scheduled(fixedRate = 1800000)
    public void reprocessDlq() {
        int processed = 0;
        int failed = 0;

        try {
            String queueUrl = sqsAsyncClient.getQueueUrl(
                    GetQueueUrlRequest.builder().queueName(dlqName).build()
            ).join().queueUrl();

            List<Message> messages = sqsAsyncClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .maxNumberOfMessages(MAX_MESSAGES_PER_CYCLE)
                            .waitTimeSeconds(5)
                            .build()
            ).join().messages();

            if (messages.isEmpty()) {
                log.debug("[DLQ] No messages in DLQ");
                return;
            }

            log.info("[DLQ] Found {} messages to reprocess", messages.size());

            for (Message message : messages) {
                try {
                    boolean success = processMessage(message.body());
                    if (success) {
                        // 성공 시 DLQ에서 삭제
                        sqsAsyncClient.deleteMessage(
                                DeleteMessageRequest.builder()
                                        .queueUrl(queueUrl)
                                        .receiptHandle(message.receiptHandle())
                                        .build()
                        ).join();
                        processed++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("[DLQ] Failed to reprocess message: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[DLQ] Failed to poll DLQ: {}", e.getMessage());
        } finally {
            lastReprocessTime = System.currentTimeMillis();
            lastReprocessedCount = processed;
            lastFailedCount = failed;

            if (processed > 0 || failed > 0) {
                log.info("[DLQ] Reprocess completed: processed={}, failed={}", processed, failed);
            }
        }
    }

    /**
     * DLQ 메시지를 직접 DB에 저장 (재큐잉 없이)
     *
     * @return true: 처리 성공 (DLQ에서 삭제 가능), false: 처리 실패 (DLQ에 유지)
     */
    private boolean processMessage(String payload) {
        VoteMessage message;
        try {
            message = objectMapper.readValue(payload, VoteMessage.class);
        } catch (Exception e) {
            log.error("[DLQ] Cannot deserialize message, skipping: {}", payload);
            return true; // 역직렬화 불가 → 재시도해도 무의미 → 삭제
        }

        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (voteRepository.existsByEntryIdAndMemberId(message.getEntryId(), message.getMemberId())) {
                    log.debug("[DLQ] Already processed, skipping: entryId={}, memberId={}",
                            message.getEntryId(), message.getMemberId());
                    return;
                }

                Entry entry = entryRepository.findById(message.getEntryId()).orElse(null);
                Member member = memberRepository.findById(message.getMemberId()).orElse(null);

                if (entry == null || member == null) {
                    log.warn("[DLQ] Entry or Member not found: entryId={}, memberId={}",
                            message.getEntryId(), message.getMemberId());
                    return;
                }

                Vote vote = Vote.builder()
                        .entry(entry)
                        .member(member)
                        .build();
                voteRepository.save(vote);
                entryRepository.incrementVoteCount(message.getEntryId());

                log.info("[DLQ] Successfully reprocessed: entryId={}, memberId={}",
                        message.getEntryId(), message.getMemberId());
            });
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("[DLQ] Duplicate vote (UK), skipping: entryId={}, memberId={}",
                    message.getEntryId(), message.getMemberId());
            return true; // 중복 → 삭제
        } catch (Exception e) {
            log.error("[DLQ] DB write failed: entryId={}, memberId={}, error={}",
                    message.getEntryId(), message.getMemberId(), e.getMessage());
            return false; // DB 오류 → DLQ에 유지, 다음 주기에 재시도
        }
    }

    // 모니터링용 getter
    public long getLastReprocessTime() { return lastReprocessTime; }
    public int getLastReprocessedCount() { return lastReprocessedCount; }
    public int getLastFailedCount() { return lastFailedCount; }
}
