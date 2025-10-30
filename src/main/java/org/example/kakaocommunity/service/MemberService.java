package org.example.kakaocommunity.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.dto.request.MemberRequestDto;
import org.example.kakaocommunity.dto.response.MemberResponseDto;
import org.example.kakaocommunity.entity.Image;
import org.example.kakaocommunity.entity.Member;
import org.example.kakaocommunity.global.exception.GeneralException;
import org.example.kakaocommunity.mapper.MemberMapper;
import org.example.kakaocommunity.repository.ImageRepository;
import org.example.kakaocommunity.repository.MemberRepository;
import org.example.kakaocommunity.repository.RefreshTokenRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberService {
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ImageRepository imageRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberResponseDto.ProfileDto getMemberInfo(Integer memberId ) {
        Member member = memberRepository.findById(memberId).get();
        return MemberMapper.toProfileDto(member);
    }


    public void changePassword(Integer memberId, MemberRequestDto.ChangePasswordDto request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        // 현재 비밀번호 확인
        if (!passwordEncoder.matches(request.getCurrentPassword(), member.getPassword())) {
            throw new GeneralException(ErrorStatus._NO_AUTHENTICATION);
        }

        // 새 비밀번호 암호화 및 저장
        String encodedPassword = passwordEncoder.encode(request.getNewPassword());
        member.changePassword(encodedPassword);
    }

    public MemberResponseDto.UpdateDto updateProfile(Integer memberId, MemberRequestDto.UpdateProfileDto request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        // 닉네임 중복 확인 (변경하려는 경우만)
        if (request.getNickname() != null && !request.getNickname().equals(member.getNickname())) {
            if (memberRepository.existsByNickname(request.getNickname())) {
                throw new GeneralException(ErrorStatus._DUPLICATED_NICKNAME);
            }
            member.changeNickname(request.getNickname());
        }

        // 이미지 변경 (imageId가 있는 경우)
        if (request.getProfileImageId() != null) {
            Image image = imageRepository.findById(request.getProfileImageId())
                    .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));
            member.changeImage(image);
        }

        return MemberMapper.toUpdateDto(member);
    }



    public void deleteAccount(Integer memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new GeneralException(ErrorStatus._NOTFOUND));

        // RefreshToken 삭제
        refreshTokenRepository.deleteByUserId(memberId);

        // 회원 삭제
        memberRepository.delete(member);
    }
}
