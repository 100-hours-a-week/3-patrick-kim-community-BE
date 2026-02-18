package org.example.kakaocommunity.controller;

import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.SupportMessageRequestDto;
import org.example.kakaocommunity.dto.response.EntryResponseDto;
import org.example.kakaocommunity.dto.response.SupportMessageResponseDto;
import org.example.kakaocommunity.dto.response.VoteResponseDto;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.global.apiPayload.status.SuccessStatus;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.service.EntryService;
import org.example.kakaocommunity.service.SupportMessageService;
import org.example.kakaocommunity.service.VoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/entries")
@RequiredArgsConstructor
public class EntryController {

    private final EntryService entryService;
    private final VoteService voteService;
    private final SupportMessageService supportMessageService;

    @GetMapping("/{entryId}")
    public ApiResponse<EntryResponseDto.DetailDto> getEntryDetail(
            @PathVariable Long entryId,
            @LoginUser Integer memberId
    ) {
        EntryResponseDto.DetailDto response = entryService.getEntryDetail(entryId, memberId);
        return ApiResponse.onSuccess(response);
    }

    @DeleteMapping("/{entryId}")
    public ApiResponse<String> deleteEntry(
            @PathVariable Long entryId,
            @LoginUser Integer memberId
    ) {
        entryService.deleteEntry(entryId, memberId);
        return ApiResponse.onSuccess("참여작을 삭제했습니다.");
    }

    @PostMapping("/{entryId}/votes")
    public ResponseEntity<ApiResponse<VoteResponseDto.VoteResult>> vote(
            @PathVariable Long entryId,
            @LoginUser Integer memberId
    ) {
        VoteResponseDto.VoteResult response = voteService.vote(entryId, memberId);
        return ResponseEntity.status(SuccessStatus._CREATED.getCode())
                .body(ApiResponse.of(SuccessStatus._CREATED, response));
    }

    @DeleteMapping("/{entryId}/votes")
    public ApiResponse<VoteResponseDto.VoteResult> cancelVote(
            @PathVariable Long entryId,
            @LoginUser Integer memberId
    ) {
        VoteResponseDto.VoteResult response = voteService.cancelVote(entryId, memberId);
        return ApiResponse.onSuccess(response);
    }

    @PostMapping("/{entryId}/supports")
    public ResponseEntity<ApiResponse<SupportMessageResponseDto.CreateDto>> createSupportMessage(
            @PathVariable Long entryId,
            @RequestBody SupportMessageRequestDto.CreateDto request,
            @LoginUser Integer memberId
    ) {
        SupportMessageResponseDto.CreateDto response = supportMessageService.createSupportMessage(entryId, request, memberId);
        return ResponseEntity.status(SuccessStatus._CREATED.getCode())
                .body(ApiResponse.of(SuccessStatus._CREATED, response));
    }

    @GetMapping("/{entryId}/supports")
    public ApiResponse<SupportMessageResponseDto.ListDto> getSupportMessages(
            @PathVariable Long entryId,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        SupportMessageResponseDto.ListDto response = supportMessageService.getSupportMessages(entryId, cursorId, limit);
        return ApiResponse.onSuccess(response);
    }
}