package org.example.kakaocommunity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

public class AuthResponseDto {

    @Getter
    @Builder
    @AllArgsConstructor
    public static class SignupDto {
        private Integer userId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class LoginDto {
        private String sessionId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class RefreshDto {
        private String accessToken;
    }
}
