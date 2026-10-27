# Conventions

## Kotlin / Android

- Package root is `com.example.persona`; keep new code inside the existing layer packages.
- Kotlin official code style (`kotlin.code.style=official`).
- ViewModels extend `BaseViewModel` and expose `StateFlow` (the codebase uses no
  `LiveData`). Use `launchCatching { ... }` so errors surface through `errorEvents`.
- Annotate ViewModels with `@HiltViewModel` and inject dependencies via the constructor.
- Use `@AndroidEntryPoint` on Activities/Fragments; bind implementations in `di/`.
- Screens live in `features/<name>/`; keep layouts in `res/layout/` and user-facing text
  in `res/values/strings.xml`.
- Repository interfaces belong in `domain/repository/`; implementations in
  `data/repository/`.
- Room schema changes require a migration in `data/local/Migrations.kt`; the destructive
  fallback has been removed, so bump the database version and add the migration.
- Domain models stay framework-free; map DTO/entity types in `data/mapper/`.

## Backend

- TypeScript, ESM (`"type": "module"`), Node >= 20.
- Validate request input with `zod`.
- Keep the DeepSeek model allowlist, token limits, and rate limits in place.
- Never log `Authorization`, full prompts, or full model responses.

## Security rules

- The DeepSeek API key exists only in backend environment variables or a secret manager.
- Never commit `local.properties`, `.env`, or tokens.
- Do not add client-side ownership fields; ownership comes from the session.

## Anti-patterns

- Adding a new layer or module when an existing package owns the responsibility.
- Calling Retrofit or Room directly from a Fragment/Activity; go through a repository.
- Hardcoding backend URLs or secrets.
- Storing model packages in the APK or in the repository.
