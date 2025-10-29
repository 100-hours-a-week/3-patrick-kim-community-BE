package org.example.kakaocommunity.service;

import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.MemberInfo;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.dto.request.AuthRequestDto;
import org.example.kakaocommunity.dto.response.AuthResponseDto;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.entity.Image;
import org.example.kakaocommunity.infrastructure.SessionStore;
import org.example.kakaocommunity.repository.ImageRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {



    private final MemberRepository memberRepository;
    private final ImageRepository imageRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionStore sessionStore;


    @Transactional
    public AuthResponseDto.SignupDto signup(AuthRequestDto.SignupDto signupDto) {
        // 이메일 중복 확인
        if (memberRepository.existsByEmail(signupDto.getEmail())) {
            throw new GeneralException(ErrorStatus._DUPLICATED_EMAIL);
        }

        // 닉네임 중복 확인
        if (memberRepository.existsByNickname(signupDto.getNickname())) {
            throw new GeneralException(ErrorStatus._DUPLICATED_NICKNAME);
        }

        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(signupDto.getPassword());

        // 이미지 조회 (있는 경우)
        Image image = null;
        if (signupDto.getProfileImageId() != null) {
            image = imageRepository.findById(signupDto.getProfileImageId())
                    .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
        }

        // 회원 생성 및 저장
        Member member = Member.builder()
                .email(signupDto.getEmail())
                .password(encodedPassword)
                .nickname(signupDto.getNickname())
                .image(image)
                .build();

        Member savedMember = memberRepository.save(member);

        return AuthResponseDto.SignupDto.builder()
                .userId(savedMember.getId())
                .build();
    }

    @Transactional
    public String login(AuthRequestDto.LoginDto loginDto) {
        // 이메일로 회원 조회
        Member member = memberRepository.findByEmail(loginDto.getEmail())
                .orElseThrow(() -> new GeneralException(ErrorStatus._BAD_REQUEST));

        // 비밀번호 확인
        if (!passwordEncoder.matches(loginDto.getPassword(), member.getPassword())) {
            throw new GeneralException(ErrorStatus._BAD_REQUEST);
        }

        // 세션 Id 생성
        String sessionId = UUID.randomUUID().toString();

        // 세션 생성
        sessionStore.save(sessionId, new MemberInfo(member.getId(), member.getEmail()));

        return sessionId;
    }

    @Transactional
    public void logout(String sessionId) {
        // 세션 만료 처리
        if(sessionId != null) sessionStore.deleteBySessionId(sessionId);
    }


}