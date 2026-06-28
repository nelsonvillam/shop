# Role-Based Access Control (RBAC)

This document explains how the shop application restricts certain operations to administrators using Spring Security's method-level authorization.

---

## What is RBAC?

**Role-Based Access Control** is the practice of assigning permissions to roles rather than individual users. Users are then assigned a role, and they inherit whatever permissions that role carries.

In the shop application there are two roles:

| Role | Who | What they can do |
|---|---|---|
| `ROLE_USER` | Any registered account | Read products, customers, orders; place orders |
| `ROLE_ADMIN` | Administrator account | Everything above + create, update, delete |

---

## How roles are stored

Each `User` document in MongoDB carries a `role` field:

```java
@Document(collection = "users")
public class User implements UserDetails {
    private Role role = Role.ROLE_USER;   // default for new registrations

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.name()));
    }
}
```

`Role` is a plain enum:

```java
public enum Role {
    ROLE_USER, ROLE_ADMIN
}
```

New accounts registered through `POST /auth/register` always receive `ROLE_USER`. The only admin account is seeded at startup via `DataLoader`, which reads the password from the `ADMIN_PASSWORD` environment variable.

---

## Enabling method security

RBAC is activated by a single annotation on `SecurityConfig`:

```java
@EnableMethodSecurity
public class SecurityConfig { ... }
```

`@EnableMethodSecurity` turns on Spring Security's AOP support so that `@PreAuthorize` expressions on controller methods are evaluated before the method body runs.

---

## Protecting endpoints with `@PreAuthorize`

Write operations are guarded with `hasRole('ADMIN')`:

```java
// ProductController
@PreAuthorize("hasRole('ADMIN')")
@PostMapping
public ProductResponseDTO create(...) { ... }

@PreAuthorize("hasRole('ADMIN')")
@PutMapping("/{id}")
public ProductResponseDTO update(...) { ... }

@PreAuthorize("hasRole('ADMIN')")
@DeleteMapping("/{id}")
public void delete(...) { ... }
```

The same pattern is applied in `CustomerController` (update, delete) and `OrderController` (create via the simple endpoint, update status, delete).

Read endpoints (`GET`) are not annotated — they require authentication but no specific role.

---

## What happens when a non-admin calls a protected endpoint

Spring Security evaluates `hasRole('ADMIN')` before the method runs. If the check fails, it throws `AccessDeniedException`, which is caught by `GlobalExceptionHandler` and returned as:

```json
HTTP 403 Forbidden
{
  "status": 403,
  "error": "Forbidden",
  "message": "Access Denied",
  "timestamp": "..."
}
```

---

## Access rules summary

| Endpoint | ROLE_USER | ROLE_ADMIN |
|---|---|---|
| `GET /api/products` | ✓ | ✓ |
| `GET /api/products/{id}` | ✓ | ✓ |
| `POST /api/products` | ✗ 403 | ✓ |
| `PUT /api/products/{id}` | ✗ 403 | ✓ |
| `DELETE /api/products/{id}` | ✗ 403 | ✓ |
| `GET /api/customers` | ✓ | ✓ |
| `PUT /api/customers/{id}` | ✗ 403 | ✓ |
| `DELETE /api/customers/{id}` | ✗ 403 | ✓ |
| `GET /api/orders` | ✓ | ✓ |
| `POST /api/orders` | ✗ 403 | ✓ |
| `POST /api/orders/place` | ✓ | ✓ |
| `PATCH /api/orders/{id}/status` | ✗ 403 | ✓ |
| `DELETE /api/orders/{id}` | ✗ 403 | ✓ |

---

## Admin account setup

The admin account is seeded by `DataLoader` at application startup. It only runs when `ADMIN_PASSWORD` is set in the environment and no `admin` user already exists:

```java
if (!adminPassword.isBlank() && userRepository.findByUsername("admin").isEmpty()) {
    User admin = new User();
    admin.setUsername("admin");
    admin.setPassword(passwordEncoder.encode(adminPassword));
    admin.setRole(Role.ROLE_ADMIN);
    userRepository.save(admin);
}
```

**In Docker Compose:**

```yaml
shop:
  environment:
    ADMIN_PASSWORD: ${ADMIN_PASSWORD}
```

**In Jenkins** the credential `shop/admin-password` is injected as `ADMIN_PASSWORD` at deploy time.

No literal password is stored anywhere in the codebase or property files — a SonarQube rule (`java:S2068`) enforces this.

---

## Testing RBAC

Integration tests verify that:
- An admin token returns `200` / `201` / `204` on protected endpoints
- A regular user token returns `403` on those same endpoints

```java
// in AuthControllerIT
@Test
void adminEndpoint_asUser_returns403() {
    // logs in as testuser (ROLE_USER), attempts DELETE /api/products/{id}
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
}
```

---

## Summary

| Concept | Implementation |
|---|---|
| Role storage | `role` field on `User` document in MongoDB |
| Role enforcement | `@PreAuthorize("hasRole('ADMIN')")` on controller methods |
| AOP activation | `@EnableMethodSecurity` on `SecurityConfig` |
| Access denied response | `GlobalExceptionHandler` → HTTP 403 |
| Admin provisioning | `DataLoader` reads `ADMIN_PASSWORD` env var at startup |
