package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.PetRequestDto;
import org.example.kakaocommunity.dto.response.PetResponseDto;
import org.example.kakaocommunity.entity.Image;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.entity.Pet;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.mapper.PetMapper;
import org.example.kakaocommunity.repository.ImageRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.PetRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PetService {

    private final PetRepository petRepository;
    private final MemberRepository memberRepository;
    private final ImageRepository imageRepository;

    public PetResponseDto.CreateDto createPet(PetRequestDto.CreateDto request, Integer memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        Image profileImage = null;
        if (request.getProfileImageId() != null) {
            profileImage = imageRepository.findById(request.getProfileImageId())
                    .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
        }

        Pet pet = Pet.builder()
                .member(member)
                .name(request.getName())
                .species(request.getSpecies())
                .breed(request.getBreed())
                .birthDate(request.getBirthDate())
                .gender(request.getGender())
                .profileImage(profileImage)
                .build();

        Pet savedPet = petRepository.save(pet);
        return PetMapper.toCreateDto(savedPet);
    }

    public List<PetResponseDto.PetSummary> getMyPets(Integer memberId) {
        List<Pet> pets = petRepository.findByMemberIdAndDeletedAtIsNull(memberId);
        return pets.stream()
                .map(PetMapper::toPetSummary)
                .collect(Collectors.toList());
    }

    public PetResponseDto.DetailDto getPetDetail(Long petId) {
        Pet pet = petRepository.findByIdAndDeletedAtIsNull(petId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
        return PetMapper.toDetailDto(pet);
    }

    public PetResponseDto.UpdateDto updatePet(Long petId, PetRequestDto.UpdateDto request, Integer memberId) {
        Pet pet = petRepository.findByIdAndDeletedAtIsNull(petId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        validatePetOwnership(pet, memberId);

        if (request.getName() != null) {
            pet.changeName(request.getName());
        }
        if (request.getSpecies() != null) {
            pet.changeSpecies(request.getSpecies());
        }
        if (request.getBreed() != null) {
            pet.changeBreed(request.getBreed());
        }
        if (request.getBirthDate() != null) {
            pet.changeBirthDate(request.getBirthDate());
        }
        if (request.getGender() != null) {
            pet.changeGender(request.getGender());
        }
        if (request.getProfileImageId() != null) {
            Image profileImage = imageRepository.findById(request.getProfileImageId())
                    .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
            pet.changeProfileImage(profileImage);
        }

        return PetMapper.toUpdateDto(pet);
    }

    public void deletePet(Long petId, Integer memberId) {
        Pet pet = petRepository.findByIdAndDeletedAtIsNull(petId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        validatePetOwnership(pet, memberId);
        pet.delete();
    }

    private void validatePetOwnership(Pet pet, Integer memberId) {
        if (!pet.getMember().getId().equals(memberId)) {
            throw new GeneralException(ErrorStatus._FORBIDDEN);
        }
    }
}