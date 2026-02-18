package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Pet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PetRepository extends JpaRepository<Pet, Long> {

    List<Pet> findByMemberIdAndDeletedAtIsNull(Integer memberId);

    Optional<Pet> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByIdAndMemberId(Long petId, Integer memberId);
}
