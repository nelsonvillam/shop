# JWT Security

This document explains the authentication layer added to the shop application using Spring Security and JSON Web Tokens (JWT).

---

## What is JWT?

A **JSON Web Token** is a compact, self-contained string that encodes a claim — typically "this user is who they say they are." It has three parts separated by dots:

```
header.payload.signature
```

- **Header** — algorithm used to sign the token (HS256)
- **Payload** — the data encoded in the token (username, expiry date)
- **Signature** — proves the token was issued by the server and hasn't been tampered with

The token is signed with a secret key only the server knows. If someone modifies the payload, the signature becomes invalid and the server rejects it.

---

## Flow

```
POST /auth/register  →  user saved to MongoDB with BCrypt password  →  JWT returned
POST /auth/login     →  credentials verified  →  JWT returned

GET /api/products    →  JwtAuthFilter reads "Authorization: Bearer <token>"
                     →  validates token  →  sets SecurityContext  →  request proceeds
```

On every protected request, the server **validates** the token — it does not look up a session or call a database. This is why JWT is called **stateless**.

---

## Endpoints

| Method | Endpoint | Auth required | Description |
|---|---|---|---|
| `POST` | `/auth/register` | No | Create an account, receive a token |
| `POST` | `/auth/login` | No | Login with credentials, receive a token |
| `*` | `/api/**` | Yes | All shop endpoints |
| `*` | `/actuator/**` | No | Health and metrics |
| `*` | `/swagger-ui/**` | No | API documentation |

---

## How to use

**1. Register:**

```bash
curl -X POST http://localhost:8081/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "nelson", "password": "secret123"}'
```

Response:
```json
{ "token": "eyJhbGciOiJIUzI1NiJ9..." }
```

**2. Use the token on every subsequent request:**

```bash
curl http://localhost:8081/api/products \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

Without the header, all `/api/**` endpoints return `401 Unauthorized`.

---

## Key classes

### `JwtUtil`

Responsible for generating and validating tokens.

```java
jwtUtil.generateToken(userDetails)   // creates a signed JWT
jwtUtil.extractUsername(token)       // reads the subject claim
jwtUtil.isTokenValid(token, user)    // checks signature + username match
```

The token is signed with HMAC-SHA256 using a secret configured in `application.properties`:

```properties
jwt.secret=shop-app-jwt-secret-key-change-me-in-production-use-env-var
jwt.expiration=86400000  # 24 hours in milliseconds
```

In production, override `jwt.secret` with an environment variable — never commit a real secret to git.

### `JwtAuthFilter`

A `OncePerRequestFilter` that runs on every request. It:

1. Reads the `Authorization` header
2. Extracts the token after `"Bearer "`
3. Validates the token with `JwtUtil`
4. If valid, sets the authenticated user in the `SecurityContextHolder`

If the header is missing or the token is invalid, the filter passes the request through unauthenticated — Spring Security then rejects it with `401`.

### `SecurityConfig`

Defines which routes require authentication and wires the filter into the chain:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/auth/**").permitAll()
    .requestMatchers("/actuator/**").permitAll()
    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
    .anyRequest().authenticated()
)
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
```

`SessionCreationPolicy.STATELESS` tells Spring Security to never create an HTTP session — the JWT is the only source of truth.

### `UserDetailsServiceImpl`

Loads a `User` from MongoDB by username. Spring Security calls this during login to fetch the stored password for comparison.

---

## Password storage

Passwords are never stored in plain text. `BCryptPasswordEncoder` hashes them before saving:

```
"secret123"  →  "$2a$10$X7Ld3..."  (stored in MongoDB)
```

On login, `BCrypt.matches("secret123", storedHash)` is called — the plain password never leaves memory.

---

## Token expiry

Tokens expire after 24 hours (`86400000` ms). After expiry, `JwtUtil.isTokenValid` returns `false` and the request is rejected with `401`. The user must login again to get a fresh token.

---

## Testing

Integration tests use a shared helper in `AbstractIntegrationTest` that registers a test user and installs a `ClientHttpRequestInterceptor` on `TestRestTemplate`. The interceptor automatically adds the `Authorization` header to every request — no individual test needs to handle auth manually.

Unit tests for `JwtUtil` use `ReflectionTestUtils` to inject the secret and expiration without starting a Spring context.

---

## Summary

| Concept | Technology |
|---|---|
| Token format | JWT (JSON Web Token) |
| Signing algorithm | HMAC-SHA256 |
| Password hashing | BCrypt |
| Token lifetime | 24 hours |
| Session strategy | Stateless (no server-side session) |
| User storage | MongoDB (`users` collection) |
