package org.example.kakaocommunity.global.validator;

import org.example.kakaocommunity.entity.Comment;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;

public class CommentValidator {

    public static void validateCommentOwnership(Comment comment, Integer memberId)
    {
        if(!comment.getMember().getId().equals(memberId))
            throw new GeneralException(ErrorStatus._UNAUTHORIZED);

    }
}
