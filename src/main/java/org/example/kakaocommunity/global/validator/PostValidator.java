package org.example.kakaocommunity.global.validator;

import org.example.kakaocommunity.entity.Post;
import org.example.kakaocommunity.global.apiPayload.status.ErrorStatus;
import org.example.kakaocommunity.global.exception.GeneralException;

public class PostValidator {
     public static void validatePostOwnerShip(Post post, Integer memberId) {
            if (!post.getMember().getId().equals(memberId)) {
                throw new GeneralException(ErrorStatus._NO_AUTHENTICATION);
            }
        }

}
