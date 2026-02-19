package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Entry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EntryRepository extends JpaRepository<Entry, Long>, EntryRepositoryCustom {

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
}
