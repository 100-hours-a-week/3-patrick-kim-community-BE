package org.example.kakaocommunity.controller;

import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.service.SupportMessageService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/supports")
@RequiredArgsConstructor
public class SupportMessageController {

    private final SupportMessageService supportMessageService;

    @DeleteMapping("/{messageId}")
    public ApiResponse<String> deleteSupportMessage(
            @PathVariable Long messageId,
            @LoginUser Integer memberId
    ) {
        supportMessageService.deleteSupportMessage(messageId, memberId);
        return ApiResponse.onSuccess("응원 메시지를 삭제했습니다.");
    }
}