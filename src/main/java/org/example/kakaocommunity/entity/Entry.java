package org.example.kakaocommunity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.kakaocommunity.global.common.BaseEntity;
import org.hibernate.annotations.ColumnDefault;

import java.util.ArrayList;
import java.util.List;

@Entity
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Getter
@Table(
        uniqueConstraints = @UniqueConstraint(
                name = "uk_entry_challenge_pet",
                columnNames = {"challenge_id", "pet_id"}
        )
)
public class Entry extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private Image image;

    @Column(length = 200)
    private String caption;

    @ColumnDefault("0")
    private int voteCount;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Vote> votes = new ArrayList<>();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SupportMessage> supportMessages = new ArrayList<>();

    public void changeCaption(String caption) {
        this.caption = caption;
    }

    public void changeImage(Image image) {
        this.image = image;
    }

    public void increaseVoteCount() {
        this.voteCount++;
    }

    public void decreaseVoteCount() {
        if (this.voteCount > 0) {
            this.voteCount--;
        }
    }
}