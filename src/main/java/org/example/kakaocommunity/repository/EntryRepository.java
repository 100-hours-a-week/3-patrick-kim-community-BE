package org.example.kakaocommunity.repository;

import jakarta.persistence.LockModeType;
import org.example.kakaocommunity.entity.Entry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EntryRepository extends JpaRepository<Entry, Long>, EntryRepositoryCustom {

    // ========== 동시성 제어용 쿼리 ==========

    // 전략 1: Pessimistic Lock (SELECT ... FOR UPDATE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Entry e WHERE e.id = :entryId")
    Optional<Entry> findByIdWithPessimisticLock(@Param("entryId") Long entryId);

    // 전략 3: Atomic Update (원자적 voteCount 증가)
    @Modifying
    @Query("UPDATE Entry e SET e.voteCount = e.voteCount + 1 WHERE e.id = :entryId")
    int incrementVoteCount(@Param("entryId") Long entryId);

    // 전략 3: Atomic Update (원자적 voteCount 감소)
    @Modifying
    @Query("UPDATE Entry e SET e.voteCount = e.voteCount - 1 WHERE e.id = :entryId AND e.voteCount > 0")
    int decrementVoteCount(@Param("entryId") Long entryId);

    // voteCount 조회 (응답용)
    @Query("SELECT e.voteCount FROM Entry e WHERE e.id = :entryId")
    int getVoteCount(@Param("entryId") Long entryId);

    List<Entry> findByChallengeIdOrderByVoteCountDesc(Long challengeId);

    // 최적화: DB에서 LIMIT 적용 (Pageable)
    List<Entry> findByChallengeIdOrderByVoteCountDesc(Long challengeId, Pageable pageable);

    // 최적화: Fetch Join으로 N+1 해결 (Pet, Image를 한 번에 로드)
    @Query("SELECT e FROM Entry e " +
           "JOIN FETCH e.pet " +
           "JOIN FETCH e.image " +
           "WHERE e.challenge.id = :challengeId " +
           "ORDER BY e.voteCount DESC")
    List<Entry> findTopEntriesWithFetchJoin(@Param("challengeId") Long challengeId, Pageable pageable);

    List<Entry> findByMemberId(Integer memberId);

    boolean existsByChallengeIdAndPetId(Long challengeId, Long petId);

    // Redis 초기화용: 챌린지의 모든 Entry 조회
    List<Entry> findByChallengeId(Long challengeId);

    // Redis 랭킹 조회용: ID 목록으로 Entry 조회 (Fetch Join)
    @Query("SELECT e FROM Entry e " +
           "JOIN FETCH e.pet " +
           "JOIN FETCH e.image " +
           "WHERE e.id IN :entryIds")
    List<Entry> findEntriesByIdsWithFetchJoin(@Param("entryIds") List<Long> entryIds);
}
