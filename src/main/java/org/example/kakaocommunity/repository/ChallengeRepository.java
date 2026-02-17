package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Challenge;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    List<Challenge> findByStatus(ChallengeStatus status);

    List<Challenge> findAllByOrderByCreatedAtDesc();
}
