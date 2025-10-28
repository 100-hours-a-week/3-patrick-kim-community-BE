package org.example.kakaocommunity.global.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.global.util.JwtUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;


    //필터 제외 경로 목록
    private static final String[] EXCLUDED_PATHS = {
            "/users", "/auth/**","images","/terms","/privacy"
    };

    //  인증에서 제외함.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        return Arrays.stream(EXCLUDED_PATHS).anyMatch(path::startsWith);
    }

    // 필터 등록 -> jwt 방식
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // 요청 헤더에서 JWT 토큰 추출
            String token = extractTokenFromRequest(request);

            // 토큰이 존재하고 유효한 경우
            if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
                // 토큰에서 사용자 ID 추출
                Integer userId = jwtUtil.getUserIdFromToken(token);

            }
        } catch (Exception e) {
            log.error("인증 과정에서 문제 발생", e);
        }

        filterChain.doFilter(request, response);
    }

    // 요청 헤더에서 JWT 토큰 추출
    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");

        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }

        return null;
    }
}