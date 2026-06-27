package com.example.shop.service;

import com.example.shop.exception.RefreshTokenException;
import com.example.shop.model.RefreshToken;
import com.example.shop.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    @Value("${jwt.refresh-expiration}")
    private long refreshExpiration;

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshToken create(String userId) {
        refreshTokenRepository.deleteByUserId(userId);
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plusMillis(refreshExpiration));
        return refreshTokenRepository.save(token);
    }

    public RefreshToken validate(String tokenValue) {
        RefreshToken token = refreshTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new RefreshTokenException("Invalid refresh token"));
        if (token.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.delete(token);
            throw new RefreshTokenException("Refresh token expired");
        }
        return token;
    }

    public RefreshToken rotate(RefreshToken old) {
        refreshTokenRepository.delete(old);
        return create(old.getUserId());
    }

    public void revokeByToken(String tokenValue) {
        refreshTokenRepository.findByToken(tokenValue).ifPresent(refreshTokenRepository::delete);
    }
}
