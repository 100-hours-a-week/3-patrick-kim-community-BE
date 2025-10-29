package org.example.kakaocommunity.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kakaocommunity.dto.request.AuthRequestDto;
import org.example.kakaocommunity.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // 쿠키에 담을 세션아이디
    private final static String  COOKIE_SESSION_ID =  "SESSION";

    @PostMapping
    public ResponseEntity<Void> login(
            @Valid @RequestBody AuthRequestDto.LoginDto loginDto,
            HttpServletResponse response
    ) {


        String sessionId =  authService.login(loginDto);
        // 3) 쿠키 세팅
        ResponseCookie cookie = ResponseCookie.from(COOKIE_SESSION_ID, sessionId)
                .httpOnly(true)
                .secure(false)          // localhost HTTP 테스트를 위해 true
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(1800))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> logout(
            @CookieValue(value = COOKIE_SESSION_ID, required = false) String sessionId,
            HttpServletResponse response
    ) {
        authService.logout(sessionId);

        ResponseCookie expired = ResponseCookie.from(COOKIE_SESSION_ID,"")
                        .path("/").httpOnly(true).sameSite("Strict").maxAge(0).build();

        response.addHeader(HttpHeaders.SET_COOKIE,expired.toString());
        return ResponseEntity.noContent().build();
    }
}
