package com.example.shop.controller;

import com.example.shop.dto.AuthRequestDTO;
import com.example.shop.dto.AuthResponseDTO;
import com.example.shop.dto.RefreshRequestDTO;
import com.example.shop.dto.RegisterRequestDTO;
import com.example.shop.exception.ResourceNotFoundException;
import com.example.shop.model.RefreshToken;
import com.example.shop.model.User;
import com.example.shop.repository.UserRepository;
import com.example.shop.security.JwtUtil;
import com.example.shop.service.RefreshTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Register, login, refresh tokens, and logout")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/register")
    @Operation(summary = "Register a new user and receive an access + refresh token pair")
    public ResponseEntity<AuthResponseDTO> register(@Valid @RequestBody RegisterRequestDTO dto) {
        if (userRepository.findByUsername(dto.username()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        User user = new User();
        user.setUsername(dto.username());
        user.setPassword(passwordEncoder.encode(dto.password()));
        userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthResponseDTO(
                jwtUtil.generateToken(user),
                refreshTokenService.create(user.getId()).getToken()
        ));
    }

    @PostMapping("/login")
    @Operation(summary = "Login and receive an access + refresh token pair")
    public ResponseEntity<AuthResponseDTO> login(@Valid @RequestBody AuthRequestDTO dto) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(dto.username(), dto.password()));
        User user = userRepository.findByUsername(dto.username()).orElseThrow();
        return ResponseEntity.ok(new AuthResponseDTO(
                jwtUtil.generateToken(user),
                refreshTokenService.create(user.getId()).getToken()
        ));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new access + refresh token pair")
    public ResponseEntity<AuthResponseDTO> refresh(@Valid @RequestBody RefreshRequestDTO dto) {
        RefreshToken old = refreshTokenService.validate(dto.refreshToken());
        User user = userRepository.findById(old.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + old.getUserId()));
        RefreshToken next = refreshTokenService.rotate(old);
        return ResponseEntity.ok(new AuthResponseDTO(jwtUtil.generateToken(user), next.getToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token (logout)")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequestDTO dto) {
        refreshTokenService.revokeByToken(dto.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
