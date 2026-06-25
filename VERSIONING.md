# Versioning Guide

---

## Semantic Versioning (SemVer)

The industry standard format is `MAJOR.MINOR.PATCH`:

```
1.4.2
│ │ └── PATCH — bug fix, no new features, backwards compatible
│ └──── MINOR — new feature, backwards compatible
└────── MAJOR — breaking change, not backwards compatible
```

### When to bump each number

| Change | Example | Version bump |
|---|---|---|
| Fix a bug | Fix null pointer in order creation | `1.4.2` → `1.4.3` |
| Add a new endpoint | Add `GET /api/orders/export` | `1.4.2` → `1.5.0` (resets PATCH to 0) |
| Rename or remove an endpoint | Remove `PUT`, replace with `PATCH` | `1.4.2` → `2.0.0` (resets MINOR and PATCH to 0) |
| Change a response field name | Rename `customerId` → `customer_id` | `1.4.2` → `2.0.0` |

> Start at `0.x.x` while the API is still unstable. Move to `1.0.0` when you consider the API stable and ready for public consumption.

---

## What SNAPSHOT means

`SNAPSHOT` is a Maven/Gradle convention that marks a version as **in active development**.

```
0.0.1-SNAPSHOT   ← work in progress
0.0.1            ← stable, immutable release
```

Key differences:

| | SNAPSHOT | Release |
|---|---|---|
| Can be overwritten in a repo | Yes | No |
| Considered stable | No | Yes |
| Suitable for production | No | Yes |
| CI/CD caches it aggressively | No | Yes |

---

## Typical version lifecycle

```
0.1.0-SNAPSHOT   ← feature development
       │
       ▼
0.1.0-RC1        ← release candidate (optional, for big projects)
       │
       ▼
0.1.0            ← stable release, tag in git
       │
       ▼
0.2.0-SNAPSHOT   ← next development cycle begins
```

---

## Who owns the version — hybrid approach

Most modern teams split the responsibility:

| Responsibility | Owner |
|---|---|
| Decide the next version number | Developer |
| Remove `-SNAPSHOT` and build the release artifact | CI/CD pipeline (DevOps) |

This means developers never manually strip SNAPSHOT — they just bump the base number in `build.gradle` and the pipeline does the rest.

---

## How to change the version in this project

### Developer's job — set the next version in `build.gradle`

```groovy
version = '0.1.0-SNAPSHOT'  // bump this when starting new work
```

The developer decides whether it's a PATCH, MINOR or MAJOR bump based on the changes planned. SNAPSHOT always stays in the code.

### CI/CD pipeline's job — inject the release version at build time

The pipeline reads the version from `build.gradle`, strips `-SNAPSHOT`, and passes it in:

```bash
# Extract base version from build.gradle and strip -SNAPSHOT
VERSION=$(./gradlew properties -q | grep "^version:" | awk '{print $2}' | sed 's/-SNAPSHOT//')

# Build the release JAR with the clean version
./gradlew bootJar -Pversion=$VERSION -x test
```

For this to work, `build.gradle` must support the override:

```groovy
version = project.hasProperty('version') && !project.version.toString().endsWith('SNAPSHOT')
        ? project.version
        : '0.0.1-SNAPSHOT'
```

Or more simply, read from an environment variable with a fallback:

```groovy
version = System.getenv('RELEASE_VERSION') ?: '0.0.1-SNAPSHOT'
```

Then the pipeline sets `RELEASE_VERSION=0.0.1` and the developer never touches it.

### Starting the next development cycle

After a release, the developer bumps the version in `build.gradle` for the next cycle:

```groovy
version = '0.1.0-SNAPSHOT'  // developer commits this after the release
```

---

## Git tagging a release

When you cut a release, tag the commit so you can always go back to exactly that code:

```bash
# Create an annotated tag
git tag -a v0.0.1 -m "Release 0.0.1"

# Push the tag to remote
git push origin v0.0.1
```

List existing tags:
```bash
git tag
```

Check out a specific release later:
```bash
git checkout v0.0.1
```

---

## Pre-release suffixes

Beyond SNAPSHOT, these suffixes are commonly used:

| Suffix | Meaning |
|---|---|
| `-SNAPSHOT` | Active development, unstable |
| `-alpha` | Early preview, likely to change |
| `-beta` | Feature complete, still being tested |
| `-RC1`, `-RC2` | Release candidate, nearly final |
| *(none)* | Stable, production-ready release |

Example progression:
```
2.0.0-SNAPSHOT → 2.0.0-alpha → 2.0.0-beta → 2.0.0-RC1 → 2.0.0
```

---

## Full example for this project — hybrid approach

Current state: `0.0.1-SNAPSHOT` — project is in development.

### Developer steps (code side)

1. Decide the release is ready. Version in `build.gradle` stays as-is:
   ```groovy
   version = '0.0.1-SNAPSHOT'
   ```
2. Open a release PR / tag the commit to trigger the pipeline:
   ```bash
   git tag -a v0.0.1 -m "Release 0.0.1"
   git push origin v0.0.1
   ```
3. After the release ships, bump to the next SNAPSHOT in `build.gradle`:
   ```groovy
   version = '0.1.0-SNAPSHOT'
   ```

### CI/CD pipeline steps (DevOps side)

Triggered by the git tag `v0.0.1`:

```bash
# 1. Strip -SNAPSHOT to get the release version
VERSION=0.0.1   # read from the git tag: $(git describe --tags --abbrev=0 | sed 's/^v//')

# 2. Build the release JAR
RELEASE_VERSION=$VERSION ./gradlew bootJar -x test

# 3. The JAR is ready
#    build/libs/shop-0.0.1.jar

# 4. Deploy or publish the artifact
```

### Summary of responsibilities

```
Developer                          CI/CD Pipeline
─────────────────────────────      ──────────────────────────────
Set version = '0.0.1-SNAPSHOT'  →  Reads version, strips -SNAPSHOT
Decide when to release          →  Builds shop-0.0.1.jar
Push git tag v0.0.1             →  Deploys to environment
Bump to 0.1.0-SNAPSHOT          →  Tags Docker image, publishes artifact
```
