package org.example.kakaocommunity.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.entity.SupportMessage;
import org.springframework.stereotype.Repository;

import java.util.List;

import static org.example.kakaocommunity.entity.QSupportMessage.supportMessage;

@Repository
@RequiredArgsConstructor
public class SupportMessageRepositoryImpl implements SupportMessageRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<SupportMessage> findSupportMessagesByEntryWithCursor(Long entryId, Long cursorId, int limit) {
        return queryFactory
                .selectFrom(supportMessage)
                .where(
                        supportMessage.entry.id.eq(entryId),
                        cursorIdCondition(cursorId)
                )
                .orderBy(supportMessage.id.desc())
                .limit(limit)
                .fetch();
    }

    private BooleanExpression cursorIdCondition(Long cursorId) {
        return cursorId != null ? supportMessage.id.lt(cursorId) : null;
    }
}