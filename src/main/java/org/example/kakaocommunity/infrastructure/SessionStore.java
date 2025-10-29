package org.example.kakaocommunity.infrastructure;


import org.example.kakaocommunity.MemberInfo;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 *  세션 Id : MemberInfo 를 저장하는 임시 세션 저장소
 */
// 싱글톤으로 등록
@Component
public class SessionStore {

    private final Map<String, MemberInfo> store =new ConcurrentHashMap<>();

    //저장
    public void save(String sessionId, MemberInfo memberInfo) {
        if (sessionId == null || memberInfo == null) return;
        store.put(sessionId, memberInfo);
    }
    // 조회
    public Optional<MemberInfo> findMemberBySessionId(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));

    }

    // 삭제
    public void deleteBySessionId(String sessionId) {
        if(sessionId == null) return;
        store.remove(sessionId);
    }

}
