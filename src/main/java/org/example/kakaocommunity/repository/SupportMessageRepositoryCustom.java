package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.SupportMessage;

import java.util.List;

public interface SupportMessageRepositoryCustom {

    List<SupportMessage> findSupportMessagesByEntryWithCursor(Long entryId, Long cursorId, int limit);
}
