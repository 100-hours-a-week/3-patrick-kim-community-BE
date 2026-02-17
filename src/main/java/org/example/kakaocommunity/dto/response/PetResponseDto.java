package org.example.kakaocommunity.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.kakaocommunity.entity.enums.Gender;
import org.example.kakaocommunity.entity.enums.Species;

import java.time.LocalDate;

public class PetResponseDto {

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreateDto {
        private Long petId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UpdateDto {
        private Long petId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PetSummary {
        private Long petId;
        private String name;
        private Species species;
        private String profileImageUrl;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DetailDto {
        private Long petId;
        private String name;
        private Species species;
        private String breed;
        private LocalDate birthDate;
        private Gender gender;
        private String profileImageUrl;
    }
}
