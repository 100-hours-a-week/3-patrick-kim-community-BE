package org.example.kakaocommunity.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.messaging.DlqReprocessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * SQS 모니터링 엔드포인트
 *
 * Phase 11: Observability
 *
 * 제공 정보:
 * - 메인 큐 깊이 (대기 중인 메시지 수)
 * - DLQ 깊이 (실패 메시지 수)
 * - 마지막 DLQ 재처리 결과
 */
@Slf4j
@RestController
@RequestMapping("/admin/sqs")
@ConditionalOnProperty(name = "spring.cloud.aws.sqs.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class AdminMonitoringController {

    private final SqsAsyncClient sqsAsyncClient;
    private final DlqReprocessor dlqReprocessor;

    @Value("${app.sqs.vote-queue-name:petstar-votes}")
    private String mainQueueName;

    @Value("${app.sqs.dlq-name:petstar-votes-dlq}")
    private String dlqName;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getSqsStatus() {
        Map<String, Object> status = new HashMap<>();

        // 메인 큐 깊이
        status.put("mainQueueDepth", getQueueDepth(mainQueueName));

        // DLQ 깊이
        status.put("dlqDepth", getQueueDepth(dlqName));

        // DLQ 재처리 정보
        long lastTime = dlqReprocessor.getLastReprocessTime();
        status.put("lastDlqReprocessTime", lastTime > 0
                ? Instant.ofEpochMilli(lastTime).toString()
                : "never");
        status.put("lastDlqReprocessedCount", dlqReprocessor.getLastReprocessedCount());
        status.put("lastDlqFailedCount", dlqReprocessor.getLastFailedCount());

        return ApiResponse.onSuccess(status);
    }

    private int getQueueDepth(String queueName) {
        try {
            String queueUrl = sqsAsyncClient.getQueueUrl(
                    GetQueueUrlRequest.builder().queueName(queueName).build()
            ).join().queueUrl();

            Map<QueueAttributeName, String> attributes = sqsAsyncClient.getQueueAttributes(
                    GetQueueAttributesRequest.builder()
                            .queueUrl(queueUrl)
                            .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                            .build()
            ).join().attributes();

            return Integer.parseInt(
                    attributes.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0")
            );
        } catch (Exception e) {
            log.warn("[AdminMonitoring] Failed to get queue depth for {}: {}", queueName, e.getMessage());
            return -1;
        }
    }
}
