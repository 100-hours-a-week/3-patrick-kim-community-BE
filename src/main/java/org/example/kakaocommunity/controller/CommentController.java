package org.example.kakaocommunity.controller;

import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.dto.request.CommentRequestDto;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.global.apiPayload.ApiResponse;
import org.example.kakaocommunity.service.CommentService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class CommentController {
    private final CommentService commentService;

    // 댓글 삭제
    @DeleteMapping("/comments/{commentId}")
    public ApiResponse<String> deleteComment(
            @PathVariable Long commentId,
            @LoginUser Integer memberId
    ) {
        commentService.delete(memberId,commentId);
        return ApiResponse.onSuccess("성공적으로 삭제했습니다.");

    }

    //댓글 수정
    @PatchMapping("/comments/{commentId}")
    public ApiResponse<String> updateComment(
            @PathVariable Long commentId,
            @LoginUser Integer memberId,
            @RequestBody CommentRequestDto.UpdateDto request
            )
    {
        commentService.updateComment(commentId,memberId,request.getContent());
        return ApiResponse.onSuccess("댓글을 수정했습니다.");

    }

}
