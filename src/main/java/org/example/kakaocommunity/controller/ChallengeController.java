package org.example.kakaocommunity.controller;

import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.EntryRequestDto;
import org.example.kakaocommunity.dto.response.ChallengeResponseDto;
import org.example.kakaocommunity.dto.response.EntryResponseDto;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.global.apiPayload.status.SuccessStatus;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.service.ChallengeService;
import org.example.kakaocommunity.service.EntryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/challenges")
@RequiredArgsConstructor
public class ChallengeController {

    private final ChallengeService challengeService;
    private final EntryService entryService;

    @GetMapping
    public ApiResponse<List<ChallengeResponseDto.ChallengeSummary>> getChallengeList(
            @RequestParam(required = false) ChallengeStatus status
    ) {
        List<ChallengeResponseDto.ChallengeSummary> response = challengeService.getChallengeList(status);
        return ApiResponse.onSuccess(response);
    }

    @GetMapping("/{challengeId}")
    public ApiResponse<ChallengeResponseDto.DetailDto> getChallengeDetail(
            @PathVariable Long challengeId
    ) {
        ChallengeResponseDto.DetailDto response = challengeService.getChallengeDetail(challengeId);
        return ApiResponse.onSuccess(response);
    }

    @GetMapping("/{challengeId}/ranking")
    public ApiResponse<List<ChallengeResponseDto.RankingEntry>> getChallengeRanking(
            @PathVariable Long challengeId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        List<ChallengeResponseDto.RankingEntry> response = challengeService.getChallengeRanking(challengeId, limit);
        return ApiResponse.onSuccess(response);
    }

    @PostMapping("/{challengeId}/entries")
    public ResponseEntity<ApiResponse<EntryResponseDto.CreateDto>> createEntry(
            @PathVariable Long challengeId,
            @RequestBody EntryRequestDto.CreateDto request,
            @LoginUser Integer memberId
    ) {
        EntryResponseDto.CreateDto response = entryService.createEntry(challengeId, request, memberId);
        return ResponseEntity.status(SuccessStatus._CREATED.getCode())
                .body(ApiResponse.of(SuccessStatus._CREATED, response));
    }

    @GetMapping("/{challengeId}/entries")
    public ApiResponse<EntryResponseDto.ListDto> getEntryList(
            @PathVariable Long challengeId,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        EntryResponseDto.ListDto response = entryService.getEntryList(challengeId, cursorId, limit);
        return ApiResponse.onSuccess(response);
    }
}