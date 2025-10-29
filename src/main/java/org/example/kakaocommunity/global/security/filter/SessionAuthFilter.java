package org.example.kakaocommunity.global.security.filter;


import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.MemberInfo;
import org.example.kakaocommunity.global.apiPayload.code.ErrorReasonDto;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.infrastructure.SessionStore;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionAuthFilter extends OncePerRequestFilter {

    private final SessionStore sessionStore;

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


            // 세션 스토어 조회 , 검증
            Optional<MemberInfo> memberInfo = sessionStore.findMemberBySessionId(sessionId);


            request.setAttribute("SESSION", memberInfo.get().memberId());

            // 검증 완료 통과
            chain.doFilter(request, response);
        } catch (Exception e) {
            sessionExceptionHandler(response,ErrorStatus._UNAUTHORIZED);
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

    //session handler
    public void sessionExceptionHandler(HttpServletResponse response, ErrorStatus errorStatus) {
        response.setStatus(errorStatus.getHttpStatus().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        try {
            String json = new ObjectMapper().writeValueAsString(ErrorReasonDto.builder().httpStatus(errorStatus.getHttpStatus())
                    .isSuccess(false)
                    .message("인증이 필요합니다.")
                    .build() );
            response.getWriter().write(json);
        } catch (Exception e) {
            log.error(e.getMessage());
        }

    }
}
