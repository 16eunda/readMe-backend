package com.ReadMe.demo.security;

import com.ReadMe.demo.dto.ApiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * 이 필터가 토큰을 검사하지 않는 경로.
     * - 로그인/회원가입/재발급: 토큰이 필요 없거나, 재발급처럼 컨트롤러가 refreshToken 을 직접 검사한다.
     * - 외부 웹훅: Pub/Sub push 인증을 켜면 우리 JWT 가 아닌 Authorization 헤더가 온다.
     */
    private static final List<String> EXCLUDED_PATHS = List.of(
            "/auth/login",
            "/auth/signup",
            "/auth/refresh",
            "/subscriptions/webhook/**"
    );
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return EXCLUDED_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        // 토큰이 없으면 비회원(deviceId) 요청이다.
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 토큰을 보냈는데 쓸 수 없으면 비회원으로 강등하지 않고 401 을 준다.
        // 대부분의 API 가 permitAll 이라 그냥 통과시키면, accessToken 이 만료된 로그인 사용자가
        // deviceId 기준 비회원 응답(구독 없음 포함)을 200 으로 받고 앱은 재발급할 기회를 얻지 못한다.
        // 컨트롤러에 닿기 전에 거절하므로 앱이 재발급 후 같은 요청을 다시 보내도 중복 처리되지 않는다.
        if (!jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isAccessToken(token)) {
            writeUnauthorized(response, "유효하지 않거나 만료된 토큰입니다.");
            return;
        }

        UserDetails userDetails;
        try {
            userDetails = customUserDetailsService.loadUserByUsername(jwtTokenProvider.getUserIdFromToken(token));
        } catch (UsernameNotFoundException | NumberFormatException e) {
            // 탈퇴한 사용자는 UserEntity 의 @Where 때문에 조회되지 않는다.
            writeUnauthorized(response, "탈퇴했거나 존재하지 않는 사용자입니다.");
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiErrorResponse.of("401", message, null));
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
