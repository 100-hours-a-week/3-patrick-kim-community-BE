package org.example.kakaocommunity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.kakaocommunity.entity.enums.ChallengeStatus;
import org.example.kakaocommunity.global.common.BaseEntity;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Getter
public class Challenge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "challenge_id")
    private Long id;

    @Column(length = 100, nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thumbnail_id")
    private Image thumbnail;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private ChallengeStatus status = ChallengeStatus.UPCOMING;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    private Integer maxEntries;

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void changeThumbnail(Image thumbnail) {
        this.thumbnail = thumbnail;
    }

    public void changeStatus(ChallengeStatus status) {
        this.status = status;
    }

    public void changeStartAt(LocalDateTime startAt) {
        this.startAt = startAt;
    }

    public void changeEndAt(LocalDateTime endAt) {
        this.endAt = endAt;
    }

    public void changeMaxEntries(Integer maxEntries) {
        this.maxEntries = maxEntries;
    }
}