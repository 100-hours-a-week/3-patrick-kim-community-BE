package org.example.kakaocommunity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.kakaocommunity.global.common.BaseEntity;

@Entity
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Getter
@Table(
        uniqueConstraints = @UniqueConstraint(
                name = "uk_vote_entry_member",
                columnNames = {"entry_id", "member_id"}
        )
)
public class Vote extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "vote_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entry_id", nullable = false)
    private Entry entry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;
}