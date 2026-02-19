package org.example.kakaocommunity.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.entity.Entry;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.example.kakaocommunity.entity.QEntry.entry;

@Repository
@RequiredArgsConstructor
public class EntryRepositoryImpl implements EntryRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Entry> findEntriesByChallengeWithCursor(Long challengeId, Long cursorId, int limit) {
        // 최적화: Fetch Join으로 N+1 해결 (Pet, Image 함께 로드)
        return queryFactory
                .selectFrom(entry)
                .join(entry.pet).fetchJoin()
                .join(entry.image).fetchJoin()
                .where(
                        entry.challenge.id.eq(challengeId),
                        cursorIdCondition(cursorId)
                )
                .orderBy(entry.id.desc())
                .limit(limit)
                .fetch();
    }

    private BooleanExpression cursorIdCondition(Long cursorId) {
        return cursorId != null ? entry.id.lt(cursorId) : null;
    }
}