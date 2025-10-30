package org.example.kakaocommunity.global.security.filter;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.MemberInfo;
import org.example.kakaocommunity.global.apiPayload.code.ErrorReasonDto;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.infrastructure.SessionStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Arrays;

@Component
@Slf4j
public class SessionAuthFilter extends OncePerRequestFilter {

    private final SessionStore sessionStore;
    private final HandlerExceptionResolver resolver;

    public SessionAuthFilter(SessionStore sessionStore,
                             @Qualifier("handlerExceptionResolver")
                             HandlerExceptionResolver resolver) {
        this.sessionStore = sessionStore;
        this.resolver = resolver;
    }


    private static final String[] EXCLUDED_PATHS = {
            "/users", "/auth", "/images", "/terms", "/privacy"
    };

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        return Arrays.stream(EXCLUDED_PATHS).anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        // 쿠키에서 세션 추출하기
        try {
            String sessionId = extractSessionFromCookie(request.getCookies());

            if (sessionId == null) throw new GeneralException(ErrorStatus._NO_AUTHENTICATION);

            // 세션 스토어 조회 , 검증
            MemberInfo memberInfo = sessionStore.findMemberBySessionId(sessionId).orElseThrow(
                    () -> new GeneralException(ErrorStatus._NO_AUTHENTICATION)
            );

            request.setAttribute("SESSION", memberInfo.memberId());

            // 검증 완료 통과
            chain.doFilter(request, response);
        } catch (GeneralException e) {
            resolver.resolveException(request, response, null, e);
            return;
        }

    }
    // 세션추출
    private static String extractSessionFromCookie(Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if("SESSION".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

}
