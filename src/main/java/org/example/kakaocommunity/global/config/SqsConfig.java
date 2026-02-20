package org.example.kakaocommunity.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Amazon SQS 설정
 *
 * Phase 10: 비동기 투표 처리를 위한 SQS 구성
 *
 * 조건: spring.cloud.aws.sqs.enabled=true 일 때만 활성화
 * - 로컬 개발 시 SQS 없이도 애플리케이션 실행 가능
 * - VoteService에서 pessimistic 전략으로 fallback
 *
 * Spring Cloud AWS SQS 3.x 사용:
 * - SqsTemplate: 메시지 전송
 * - SqsListener: 메시지 수신 (@SqsListener 어노테이션)
 */
@Configuration
@ConditionalOnProperty(name = "spring.cloud.aws.sqs.enabled", havingValue = "true", matchIfMissing = false)
public class SqsConfig {

    /**
     * SQS 메시지 전송용 Template
     */
    @Bean
    public SqsTemplate sqsTemplate(SqsAsyncClient sqsAsyncClient) {
        return SqsTemplate.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .build();
    }

    /**
     * SQS 리스너 컨테이너 팩토리
     *
     * 설정:
     * - maxConcurrentMessages: 1 (순차 처리로 데드락 방지)
     * - maxMessagesPerPoll: 1 (한 번에 1개씩 가져옴)
     *
     * 데드락 문제 해결:
     * 같은 Entry에 동시 투표 시 UPDATE 충돌 발생
     * → 순차 처리로 Lock 경합 제거
     * → Consumer 처리량은 낮아지지만 API 응답은 이미 Redis 반영 완료
     */
    @Bean
    public SqsMessageListenerContainerFactory<Object> defaultSqsListenerContainerFactory(
            SqsAsyncClient sqsAsyncClient) {
        return SqsMessageListenerContainerFactory.builder()
                .sqsAsyncClient(sqsAsyncClient)
                .configure(options -> options
                        .maxConcurrentMessages(1)      // 동시 처리 1개 (데드락 방지)
                        .maxMessagesPerPoll(1)         // 폴링당 1개
                )
                .build();
    }

    /**
     * ObjectMapper with Java Time Module
     * (VoteMessage의 Instant 직렬화/역직렬화)
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
