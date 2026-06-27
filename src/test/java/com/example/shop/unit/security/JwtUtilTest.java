package com.example.shop.unit.security;

import com.example.shop.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private JwtUtil jwtUtil;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "shop-app-jwt-secret-key-change-me-in-production-use-env-var");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);

        userDetails = new User("alice", "password", List.of());
    }

    @Test
    void generateToken_returnsNonBlankToken() {
        String token = jwtUtil.generateToken(userDetails);
        assertThat(token).isNotBlank();
    }

    @Test
    void extractUsername_returnsCorrectUsername() {
        String token = jwtUtil.generateToken(userDetails);
        assertThat(jwtUtil.extractUsername(token)).isEqualTo("alice");
    }

    @Test
    void isTokenValid_withMatchingUser_returnsTrue() {
        String token = jwtUtil.generateToken(userDetails);
        assertThat(jwtUtil.isTokenValid(token, userDetails)).isTrue();
    }

    @Test
    void isTokenValid_withDifferentUser_returnsFalse() {
        String token = jwtUtil.generateToken(userDetails);
        UserDetails other = new User("bob", "password", List.of());
        assertThat(jwtUtil.isTokenValid(token, other)).isFalse();
    }

    @Test
    void isTokenValid_withExpiredToken_returnsFalse() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1000L);
        String token = jwtUtil.generateToken(userDetails);
        assertThat(jwtUtil.isTokenValid(token, userDetails)).isFalse();
    }

    @Test
    void extractUsername_withInvalidToken_throwsException() {
        assertThatThrownBy(() -> jwtUtil.extractUsername("not.a.token"))
                .isInstanceOf(Exception.class);
    }
}
