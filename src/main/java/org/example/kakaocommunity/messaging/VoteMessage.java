package org.example.kakaocommunity.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * SQS 투표 메시지
 *
 * Phase 10: 비동기 투표 처리를 위한 메시지 스키마
 *
 * Idempotency 보장:
 * - voteId를 고유 키로 사용
 * - Consumer에서 (memberId, entryId) 조합으로 중복 체크
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoteMessage {

    /**
     * 메시지 고유 ID (Idempotency Key)
     */
    private String voteId;

    /**
     * 투표자 ID
     */
    private Integer memberId;

    /**
     * 투표 대상 Entry ID
     */
    private Long entryId;

    /**
     * 챌린지 ID (Redis 랭킹 키에 사용)
     */
    private Long challengeId;

    /**
     * 투표 시간
     */
    private Instant timestamp;

    /**
     * 팩토리 메서드: 새 투표 메시지 생성
     */
    public static VoteMessage create(Integer memberId, Long entryId, Long challengeId) {
        return VoteMessage.builder()
                .voteId(UUID.randomUUID().toString())
                .memberId(memberId)
                .entryId(entryId)
                .challengeId(challengeId)
                .timestamp(Instant.now())
                .build();
    }
}
