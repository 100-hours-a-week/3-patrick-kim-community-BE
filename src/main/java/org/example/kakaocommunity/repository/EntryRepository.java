package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Entry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntryRepository extends JpaRepository<Entry, Long>, EntryRepositoryCustom {

    List<Entry> findByChallengeIdOrderByVoteCountDesc(Long challengeId);

    // 최적화: DB에서 LIMIT 적용 (Pageable)
    List<Entry> findByChallengeIdOrderByVoteCountDesc(Long challengeId, Pageable pageable);

    List<Entry> findByMemberId(Integer memberId);

    boolean existsByChallengeIdAndPetId(Long challengeId, Long petId);
}
