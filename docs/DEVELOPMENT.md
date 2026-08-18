# Development

MODOSE is a Kotlin, Rust, and Go monorepo. Node.js and TypeScript are not part of the toolchain.

## Pinned toolchains

| Boundary | Version source | Version |
| --- | --- | --- |
| Java | `.java-version` | 25.0.1 |
| Gradle | `apps/android/gradle/wrapper/gradle-wrapper.properties` | 9.2.1 |
| Rust | `rust-toolchain.toml` | 1.93.0 |
| Go | `go.work` and `services/vision-api/go.mod` | 1.25.5 |

## Baseline builds

Run commands from the repository root.

### Android build root

Windows:

```powershell
apps\android\gradlew.bat -p apps\android build
```

macOS or Linux:

```sh
./apps/android/gradlew -p apps/android build
```

This build intentionally has no Android application module. The app shell is introduced by M-009.

### Rust workspace

```sh
cargo build --workspace
```

The `scene-core` package is buildable but contains no domain behavior. Domain types,
strict lint policy, and tests are introduced by M-029.

### Go workspace

```sh
go -C services/vision-api build ./...
```

The module contains only a package declaration. The HTTP service is introduced by M-055.

## Repository boundaries

- `apps/android/`: Kotlin, Compose, ARCore, and Android adapters.
- `crates/`: Android-independent Rust core and the narrow JNI adapter.
- `services/vision-api/`: Go Cloud Run API.
- `api/`: OpenAPI and JSON Schema contracts.
- `fixtures/`: versioned Baseline, Compare, and Verify contract examples.

## Continuous integration

`.github/workflows/ci.yml` classifies changed paths and runs only the applicable
Android, Rust, Go, and contract baseline jobs. The stable `CI gate` check aggregates
successful and skipped jobs and is the check intended for `main` branch protection.

Lint the OpenAPI 3.1 contract with the pinned Go validator:

```sh
bash .github/scripts/lint-openapi.sh
```
