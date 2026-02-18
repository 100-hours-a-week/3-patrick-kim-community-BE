package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VoteRepository extends JpaRepository<Vote, Long> {

    boolean existsByEntryIdAndMemberId(Long entryId, Integer memberId);

    Optional<Vote> findByEntryIdAndMemberId(Long entryId, Integer memberId);
}
