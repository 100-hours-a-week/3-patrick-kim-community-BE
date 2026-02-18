package org.example.kakaocommunity.repository;

import org.example.kakaocommunity.entity.Entry;

import java.util.List;

public interface EntryRepositoryCustom {

    List<Entry> findEntriesByChallengeWithCursor(Long challengeId, Long cursorId, int limit);
}
