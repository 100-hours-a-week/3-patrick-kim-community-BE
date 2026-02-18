package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.SupportMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long>, SupportMessageRepositoryCustom {

    List<SupportMessage> findByEntryIdOrderByCreatedAtDesc(Long entryId);
}
