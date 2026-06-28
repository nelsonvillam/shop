# Refresh Tokens

This document explains why refresh tokens exist and how the shop application implements token rotation and revocation.

---

## The problem with short-lived access tokens

A JWT access token is self-contained — once issued, it is valid until it expires. The shop application issues access tokens that expire after **15 minutes** (`jwt.expiration=900000` ms).

Short expiry limits the damage if a token is stolen, but it forces the user to log in again every 15 minutes. Refresh tokens solve this by letting the client obtain a new access token silently, without re-entering credentials.

---

## What is a refresh token?

A **refresh token** is a long-lived, single-use credential stored in the database. When the access token expires, the client sends the refresh token to `/auth/refresh` and receives a fresh access token (and a new refresh token) in return.

| Token | Lifetime | Where stored | Self-contained? |
|---|---|---|---|
| Access token (JWT) | 15 minutes | Client only | Yes — stateless |
| Refresh token | 7 days | MongoDB `refresh_tokens` collection | No — requires DB lookup |

---

## Token lifecycle

```
POST /auth/login
  → access token (15 min JWT) + refresh token (7-day UUID) returned

... 15 minutes later, access token expires ...

POST /auth/refresh  { "refreshToken": "<uuid>" }
  → old refresh token deleted
  → new access token + new refresh token returned   ← rotation

POST /auth/logout  { "refreshToken": "<uuid>" }
  → refresh token deleted from DB
  → future refresh attempts with that token → 401
```

---

## MongoDB model

Refresh tokens are stored in a separate collection:

```java
@Document(collection = "refresh_tokens")
public class RefreshToken {
    @Id
    private String id;
    private String userId;

    @Indexed(unique = true)
    private String token;       // UUID, indexed for fast lookup

    private Instant expiresAt;
}
```

The `@Indexed(unique = true)` on `token` prevents duplicates and makes the lookup by token value a fast O(log n) operation.

---

## RefreshTokenService

```java
// Issue a new token — deletes any existing token for this user first
public RefreshToken create(String userId) {
    refreshTokenRepository.deleteByUserId(userId);
    RefreshToken token = new RefreshToken();
    token.setUserId(userId);
    token.setToken(UUID.randomUUID().toString());
    token.setExpiresAt(Instant.now().plusMillis(refreshExpiration));
    return refreshTokenRepository.save(token);
}

// Validate: token must exist and not be expired
public RefreshToken validate(String tokenValue) {
    RefreshToken token = refreshTokenRepository.findByToken(tokenValue)
            .orElseThrow(() -> new RefreshTokenException("Invalid refresh token"));
    if (token.getExpiresAt().isBefore(Instant.now())) {
        refreshTokenRepository.delete(token);
        throw new RefreshTokenException("Refresh token expired");
    }
    return token;
}

// Rotation: delete the old token, issue a new one
public RefreshToken rotate(RefreshToken old) {
    refreshTokenRepository.delete(old);
    return create(old.getUserId());
}

// Logout: delete the token so it can never be used again
public void revokeByToken(String tokenValue) {
    refreshTokenRepository.findByToken(tokenValue).ifPresent(refreshTokenRepository::delete);
}
```

---

## Auth endpoints

| Method | Endpoint | Body | Description |
|---|---|---|---|
| `POST` | `/auth/register` | `{username, password}` | Register; returns access + refresh token |
| `POST` | `/auth/login` | `{username, password}` | Login; returns access + refresh token |
| `POST` | `/auth/refresh` | `{refreshToken}` | Rotate; returns new access + refresh token |
| `POST` | `/auth/logout` | `{refreshToken}` | Revoke the refresh token |

**Example refresh call:**

```bash
curl -X POST http://localhost:8081/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "f47ac10b-58cc-4372-a567-0e02b2c3d479"}'
```

Response:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "a1b2c3d4-e5f6-..."
}
```

---

## Token rotation — why it matters

Each call to `/auth/refresh` **deletes** the old refresh token before issuing a new one. This is called **rotation**.

The security benefit: if an attacker steals a refresh token and uses it, the original token is deleted. When the legitimate user's client tries to use the now-deleted token, it gets a `401`. This signals that the token was compromised and the user is prompted to log in again.

Without rotation, a stolen refresh token is valid for its full 7-day lifetime with no way to detect misuse.

---

## One active refresh token per user

`create()` always calls `deleteByUserId(userId)` before saving the new token. This means every login or refresh replaces any previously issued token for that user. There is never more than one active refresh token per user in the database.

---

## Error responses

| Situation | Response |
|---|---|
| Token not found in DB | `401 Unauthorized` — "Invalid refresh token" |
| Token found but expired | `401 Unauthorized` — "Refresh token expired" (token also deleted) |

---

## Configuration

```properties
jwt.expiration=900000          # access token: 15 minutes
jwt.refresh-expiration=604800000  # refresh token: 7 days
```

---

## Summary

| Concept | Detail |
|---|---|
| Access token lifetime | 15 minutes (JWT, stateless) |
| Refresh token lifetime | 7 days (UUID, stored in MongoDB) |
| Storage | `refresh_tokens` MongoDB collection |
| Rotation | Old token deleted on every `/auth/refresh` call |
| Revocation | `POST /auth/logout` deletes the token immediately |
| One token per user | `create()` deletes any existing token before issuing a new one |
