package com.example.shop.unit.service;

import com.example.shop.exception.RefreshTokenException;
import com.example.shop.model.RefreshToken;
import com.example.shop.repository.RefreshTokenRepository;
import com.example.shop.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshExpiration", 604800000L);
    }

    @Test
    void create_deletesExistingAndPersistsNewToken() {
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshToken token = refreshTokenService.create("user1");

        verify(refreshTokenRepository).deleteByUserId("user1");
        assertThat(token.getUserId()).isEqualTo("user1");
        assertThat(token.getToken()).isNotBlank();
        assertThat(token.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void validate_withValidToken_returnsToken() {
        RefreshToken stored = new RefreshToken();
        stored.setUserId("user1");
        stored.setToken("valid-token");
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        when(refreshTokenRepository.findByToken("valid-token")).thenReturn(Optional.of(stored));

        assertThat(refreshTokenService.validate("valid-token")).isEqualTo(stored);
    }

    @Test
    void validate_withNonExistentToken_throwsRefreshTokenException() {
        when(refreshTokenRepository.findByToken("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.validate("bad"))
                .isInstanceOf(RefreshTokenException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void validate_withExpiredToken_deletesAndThrowsRefreshTokenException() {
        RefreshToken expired = new RefreshToken();
        expired.setToken("expired");
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByToken("expired")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> refreshTokenService.validate("expired"))
                .isInstanceOf(RefreshTokenException.class)
                .hasMessageContaining("expired");
        verify(refreshTokenRepository).delete(expired);
    }

    @Test
    void rotate_deletesOldTokenAndCreatesNew() {
        RefreshToken old = new RefreshToken();
        old.setUserId("user1");
        old.setToken("old-token");
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshToken next = refreshTokenService.rotate(old);

        verify(refreshTokenRepository).delete(old);
        verify(refreshTokenRepository).deleteByUserId("user1");
        assertThat(next.getToken()).isNotEqualTo("old-token");
    }

    @Test
    void revokeByToken_whenFound_deletesToken() {
        RefreshToken token = new RefreshToken();
        token.setToken("t");
        when(refreshTokenRepository.findByToken("t")).thenReturn(Optional.of(token));

        refreshTokenService.revokeByToken("t");

        verify(refreshTokenRepository).delete(token);
    }

    @Test
    void revokeByToken_whenNotFound_doesNothing() {
        when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        refreshTokenService.revokeByToken("missing");

        verify(refreshTokenRepository, never()).delete(any());
    }
}
