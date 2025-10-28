package org.example.kakaocommunity.global.security.resolver;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.kakaocommunity.global.security.annotation.LoginUser;
import org.example.kakaocommunity.global.util.JwtUtil;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;


@Component
@RequiredArgsConstructor
public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final JwtUtil jwtUtil;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(LoginUser.class)
                && parameter.getParameterType().equals(Integer.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                   ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest,
                                   WebDataBinderFactory binderFactory) throws Exception {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        String bearerToken = request.getHeader("Authorization");
        String token;
        // 보안 인증 헤더 없을 경우
        if(!StringUtils.hasText(bearerToken)) {
            return null;
        }

        if(bearerToken.startsWith("Bearer ")) {
            token =  (bearerToken.substring(7));
        } else {
            return null;
        }


        //유효한 토큰인지
        if(!jwtUtil.validateToken(token)) {
            return null;
        }

        // 유효할 경우 userId 리턴
        return jwtUtil.getUserIdFromToken((token));


    }
}
