# Spring Profile Configuration Guide

## How Spring profiles work

Spring loads `application.properties` first, then merges the active profile file (`application-{profile}.properties`) on top of it. Profile-specific values override the base ones; anything not overridden in the profile file falls back to the base.

The active profile is controlled by:

```properties
# application.properties
spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}
```

Available profiles in this project: `dev`, `uat`, `prod`.

---

## What goes where

### `application.properties` — shared, profile-agnostic config

Put here only what is truly the same across every environment:

- Cache type (`spring.cache.type=redis`)
- MongoDB database name and index settings
- Logging levels for debugging/tracing

**Avoid** hardcoding hostnames, ports, or credentials here. If a value differs between dev/uat/prod, it does not belong in the base file.

### `application-{profile}.properties` — environment-specific overrides

Each profile file should define:

| Property | dev | uat | prod |
|---|---|---|---|
| MongoDB URI | uses defaults with `appdev`/`dev` fallback | requires `MONGO_USER` / `MONGO_PASSWORD` env vars | requires `MONGO_USER` / `MONGO_PASSWORD` env vars |
| Redis host | `${REDIS_HOST:localhost}` | `${REDIS_HOST}` (no default) | `${REDIS_HOST}` (no default) |
| Redis port | `${REDIS_PORT:6379}` | `${REDIS_PORT:6379}` | `${REDIS_PORT:6379}` |

---

## Environment variable conventions

| Variable | Required in | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | all | selects the active profile |
| `MONGO_USER` | uat, prod | MongoDB username |
| `MONGO_PASSWORD` | uat, prod | MongoDB password |
| `REDIS_HOST` | uat, prod | Redis server hostname |
| `REDIS_PORT` | uat, prod (optional) | Redis port, defaults to 6379 |

### Default fallbacks

- Use `${VAR:default}` syntax for dev where a local default makes sense.
- Leave no default (`${VAR}`) in uat/prod so the app fails fast on startup if a required variable is missing, rather than connecting to a wrong host silently.

---

## Things to watch when adding a new property

1. **Is this value the same in every environment?** If yes, put it in `application.properties`. If no, put it in each profile file.
2. **Does it contain a host, port, credential, or URL?** Always use an env var, never hardcode.
3. **Dev needs a sensible local default.** Use `${VAR:local-default}` so the app runs without extra setup.
4. **Uat/prod must not have defaults for sensitive or host-specific values.** A missing env var should crash startup, not silently use a wrong connection.
5. **Add the new variable to the table above** so it is discoverable by anyone setting up a new environment.
