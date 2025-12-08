package org.example.kakaocommunity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.global.apiPayload.status.SuccessStatus;
import org.example.kakaocommunity.dto.request.AuthRequestDto;
import org.example.kakaocommunity.dto.request.MemberRequestDto;
import org.example.kakaocommunity.dto.response.AuthResponseDto;
import org.example.kakaocommunity.dto.response.MemberResponseDto;
import org.example.kakaocommunity.service.AuthService;
import org.example.kakaocommunity.service.MemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class MemberController {

    private final AuthService authService;
    private final MemberService memberService;

    //회원정보 조회
    @GetMapping("/me")
    public ApiResponse<MemberResponseDto.ProfileDto> getMemberInfo(
            @LoginUser Integer memberId
    ) {
        return ApiResponse.onSuccess(memberService.getMemberInfo(memberId));
    }

    // 회원가입
    @PostMapping
    public ResponseEntity<ApiResponse<AuthResponseDto.SignupDto>> signup(
            @Valid @RequestBody AuthRequestDto.SignupDto signupDto
    ) {
        AuthResponseDto.SignupDto response = authService.signup(signupDto);
        return ResponseEntity.status(SuccessStatus._CREATED.getCode())
                .body(ApiResponse.of(SuccessStatus._CREATED, response));
    }

    // 비밀번호 변경
    @PatchMapping("/me/password")
    public ApiResponse<String> changePassword(
            @LoginUser Integer memberId,
            @RequestBody MemberRequestDto.ChangePasswordDto request
    ) {
        memberService.changePassword(memberId, request);
        return ApiResponse.onSuccess("비밀번호가 변경되었습니다.");
    }

    // 프로필 수정
    @PatchMapping("/me")
    public ApiResponse<MemberResponseDto.UpdateDto> updateProfile(
            @LoginUser Integer memberId,
            @RequestBody MemberRequestDto.UpdateProfileDto request
    ) {
        MemberResponseDto.UpdateDto response = memberService.updateProfile(memberId, request);
        return ApiResponse.onSuccess(response);
    }

    // 회원 탈퇴
    @DeleteMapping("/me")
    public ApiResponse<String> deleteAccount(
            @LoginUser Integer memberId
    ) {
        memberService.deleteAccount(memberId);
        return ApiResponse.onSuccess("회원 탈퇴가 완료되었습니다.");
    }
}
