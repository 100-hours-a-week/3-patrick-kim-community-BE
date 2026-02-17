package org.example.kakaocommunity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.example.kakaocommunity.entity.enums.Role;
import org.example.kakaocommunity.global.common.BaseEntity;

import java.time.LocalDateTime;

@Entity
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@Getter
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Integer id;

    @Column(length = 10, nullable = false, unique = true)
    private String nickname;
    @Column(length = 320, nullable = false, unique = true)
    private String email;
    @Column(nullable = false)
    private String password;

    @OneToOne
    @JoinColumn(name = "image_id")
    private Image image;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    private LocalDateTime deletedAt;

    public void changePassword(String password) {
        this.password = password;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changeImage(Image image) {
        this.image = image;
    }
}
