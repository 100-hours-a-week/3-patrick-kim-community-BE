package org.example.kakaocommunity.mapper;

import org.example.kakaocommunity.dto.response.PetResponseDto;
import org.example.kakaocommunity.entity.Pet;

public class PetMapper {

    public static PetResponseDto.CreateDto toCreateDto(Pet pet) {
        return PetResponseDto.CreateDto.builder()
                .petId(pet.getId())
                .build();
    }

    public static PetResponseDto.UpdateDto toUpdateDto(Pet pet) {
        return PetResponseDto.UpdateDto.builder()
                .petId(pet.getId())
                .build();
    }

    public static PetResponseDto.PetSummary toPetSummary(Pet pet) {
        return PetResponseDto.PetSummary.builder()
                .petId(pet.getId())
                .name(pet.getName())
                .species(pet.getSpecies())
                .profileImageUrl(pet.getProfileImage() != null ? pet.getProfileImage().getUrl() : null)
                .build();
    }

    public static PetResponseDto.DetailDto toDetailDto(Pet pet) {
        return PetResponseDto.DetailDto.builder()
                .petId(pet.getId())
                .name(pet.getName())
                .species(pet.getSpecies())
                .breed(pet.getBreed())
                .birthDate(pet.getBirthDate())
                .gender(pet.getGender())
                .profileImageUrl(pet.getProfileImage() != null ? pet.getProfileImage().getUrl() : null)
                .build();
    }
}