package org.example.kakaocommunity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.kakaocommunity.entity.enums.Gender;
import org.example.kakaocommunity.entity.enums.Species;
import org.example.kakaocommunity.global.common.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Getter
public class Pet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pet_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(length = 50, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private Species species;

    @Column(length = 50)
    private String breed;

    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_image_id")
    private Image profileImage;

    private LocalDateTime deletedAt;

    public void changeName(String name) {
        this.name = name;
    }

    public void changeSpecies(Species species) {
        this.species = species;
    }

    public void changeBreed(String breed) {
        this.breed = breed;
    }

    public void changeBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public void changeGender(Gender gender) {
        this.gender = gender;
    }

    public void changeProfileImage(Image profileImage) {
        this.profileImage = profileImage;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
}