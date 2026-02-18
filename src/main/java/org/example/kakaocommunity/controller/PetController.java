package org.example.kakaocommunity.controller;

import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.PetRequestDto;
import org.example.kakaocommunity.dto.response.PetResponseDto;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.global.apiPayload.status.SuccessStatus;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.service.PetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/pets")
@RequiredArgsConstructor
public class PetController {

    private final PetService petService;

    @PostMapping
    public ResponseEntity<ApiResponse<PetResponseDto.CreateDto>> createPet(
            @RequestBody PetRequestDto.CreateDto request,
            @LoginUser Integer memberId
    ) {
        PetResponseDto.CreateDto response = petService.createPet(request, memberId);
        return ResponseEntity.status(SuccessStatus._CREATED.getCode())
                .body(ApiResponse.of(SuccessStatus._CREATED, response));
    }

    @GetMapping
    public ApiResponse<List<PetResponseDto.PetSummary>> getMyPets(
            @LoginUser Integer memberId
    ) {
        List<PetResponseDto.PetSummary> response = petService.getMyPets(memberId);
        return ApiResponse.onSuccess(response);
    }

    @GetMapping("/{petId}")
    public ApiResponse<PetResponseDto.DetailDto> getPetDetail(
            @PathVariable Long petId
    ) {
        PetResponseDto.DetailDto response = petService.getPetDetail(petId);
        return ApiResponse.onSuccess(response);
    }

    @PatchMapping("/{petId}")
    public ApiResponse<PetResponseDto.UpdateDto> updatePet(
            @PathVariable Long petId,
            @RequestBody PetRequestDto.UpdateDto request,
            @LoginUser Integer memberId
    ) {
        PetResponseDto.UpdateDto response = petService.updatePet(petId, request, memberId);
        return ApiResponse.onSuccess(response);
    }

    @DeleteMapping("/{petId}")
    public ApiResponse<String> deletePet(
            @PathVariable Long petId,
            @LoginUser Integer memberId
    ) {
        petService.deletePet(petId, memberId);
        return ApiResponse.onSuccess("펫을 삭제했습니다.");
    }
}