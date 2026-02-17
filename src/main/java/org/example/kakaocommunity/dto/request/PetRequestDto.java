package org.example.kakaocommunity.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.kakaocommunity.entity.enums.Gender;
import org.example.kakaocommunity.entity.enums.Species;

import java.time.LocalDate;

public class PetRequestDto {

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CreateDto {
        private String name;
        private Species species;
        private String breed;
        private LocalDate birthDate;
        private Gender gender;
        private Long profileImageId;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UpdateDto {
        private String name;
        private Species species;
        private String breed;
        private LocalDate birthDate;
        private Gender gender;
        private Long profileImageId;
    }
}
