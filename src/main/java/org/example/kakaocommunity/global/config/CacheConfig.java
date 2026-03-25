package org.example.kakaocommunity.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 로컬 캐시 설정 (Round 3: 읽기 API 캐싱)
 *
 * 읽기 API가 DB 커넥션의 78%를 차지하는 것이 Round 1-2에서 확인됨.
 * Caffeine L1 캐시로 DB 호출을 줄여 HikariCP 커넥션 포화 해소.
 *
 * 캐시 대상:
 * - challengeList: 챌린지 목록 (TTL 10초, 변경 빈도 낮음)
 * - ranking: 랭킹 Entry 상세 (TTL 5초, 투표로 순위 변동)
 * - entryList: 엔트리 목록 (TTL 10초, 변경 빈도 낮음)
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .maximumSize(200)
                .recordStats());
        return cacheManager;
    }
}
