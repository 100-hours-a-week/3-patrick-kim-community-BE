package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Entry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EntryRepository extends JpaRepository<Entry, Long>, EntryRepositoryCustom {

    List<Entry> findByChallengeIdOrderByVoteCountDesc(Long challengeId);

    List<Entry> findByMemberId(Integer memberId);

    boolean existsByChallengeIdAndPetId(Long challengeId, Long petId);
}
