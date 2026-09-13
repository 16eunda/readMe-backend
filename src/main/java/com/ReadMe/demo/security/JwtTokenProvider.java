package com.ReadMe.demo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

// JwtTokenProvider.java
@Component
public class JwtTokenProvider {

    @Value("${jwt.secret}")
    private String secretKey;

    private static final long ACCESS_TOKEN_VALIDITY = 1000L * 60 * 60;             // 1시간
    private static final long REFRESH_TOKEN_VALIDITY = 1000L * 60 * 60 * 24 * 30; // 30일

    // 토큰 용도 구분. 이게 없으면 accessToken 으로도 재발급이 되어 무기한 연장이 가능하다.
    private static final String CLAIM_TOKEN_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    /**
     * accessToken 생성
     */
    public String generateAccessToken(String userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + ACCESS_TOKEN_VALIDITY);
        Key key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .setSubject(userId)
                .claim(CLAIM_TOKEN_TYPE, TYPE_ACCESS)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    /**
     * refreshToken 생성
     */
    public String generateRefreshToken(String userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + REFRESH_TOKEN_VALIDITY);
        Key key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .setSubject(userId)
                .claim(CLAIM_TOKEN_TYPE, TYPE_REFRESH)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key, SignatureAlgorithm.HS512)
                .compact();
    }

    /**
     * 재발급에 쓸 수 있는 토큰인지 확인.
     *
     * typ 클레임이 없는 토큰은 이 클레임 도입 이전에 발급된 것이다.
     * 기존 사용자가 한꺼번에 로그아웃되지 않도록 당분간 허용한다.
     * refreshToken 유효기간(30일)이 지난 뒤에는 이 하위호환 분기를 제거할 것.
     */
    public boolean isRefreshToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
            return type == null || TYPE_REFRESH.equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * API 인증에 쓸 수 있는 토큰인지 확인.
     * 30일짜리 refreshToken 이 1시간짜리 accessToken 처럼 쓰이지 않도록 막는다.
     * typ 클레임이 없는 토큰은 isRefreshToken 과 같은 이유로 당분간 허용한다.
     */
    public boolean isAccessToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
            return type == null || TYPE_ACCESS.equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    private Key getSigningKey() {
        return Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 토큰 유효성 검증
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 토큰에서 userId 추출 (만료된 토큰도 subject 추출 가능)
     */
    public String getUserIdFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getSubject();
        } catch (ExpiredJwtException e) {
            // 만료됐어도 subject는 꺼낼 수 있음
            return e.getClaims().getSubject();
        }
    }

    /**
     * 토큰에서 만료시간 추출 (만료된 토큰도 날짜 추출 가능)
     */
    public Date getExpirationDateFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getExpiration();
        } catch (ExpiredJwtException e) {
            return e.getClaims().getExpiration();
        }
    }
}