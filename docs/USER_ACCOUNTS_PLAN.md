# Implementation plan: user accounts, saved plans, travel history

Branch: `feat/user-accounts` (from main @ 1d447cc). Worktree: `~/code/wt/accounts`.
Scope of ownership: `backend/`, `docs/API_CONTRACT.md`, `backend/INTERFACES.md`.
FORBIDDEN: `frontend/`, `shared/`, `analytics/`, existing handlers of
`/health`, `/api/locations`, `/api/journeys`, `/api/model` (frontend is
wire-building against the current contract — zero drift allowed).

Before writing any code, read: `backend/AGENTS.md`, `backend/INTERFACES.md`,
`docs/API_CONTRACT.md` (error envelope + style), `config/Plugins.kt`, `api/Routes.kt`.

## 1. Scope

IN:
- User registration, login, logout, current-user lookup (bearer-token auth).
- Save / list / delete planned journeys, per user ("saved trips").
- Log / list past travels: created from a saved plan or standalone.
- Contract documentation updates + tests. Existing 165 tests stay green.

OUT (do not build):
- No auth on the 4 existing public endpoints.
- No password reset / email verification (no mail service).
- No auto-populated actual arrival times from OJP StopEvent (stretch, see §9).
- No account deletion, no rate limiting (note as future work in contract).

## 2. Tech decisions

| Decision | Choice | Rationale |
| --- | --- | --- |
| Persistence | Exposed (JetBrains) + JDBC | Kotlin-native; Ktor house style; no Spring. |
| Prod DB | PostgreSQL via `DATABASE_URL` (Railway) | Railway Postgres service; survives redeploys. |
| Dev/test DB | H2 in-memory when `DATABASE_URL` unset | Zero-config local + tests; nothing breaks today. |
| Auth | Opaque bearer token, stored hashed | No JWT deps; revocable (logout = delete); simple. |
| Password hashing | BCrypt (at.favre.lib:bcrypt 0.10.2), cost 10 | Standard, small standalone lib. |
| Token format | 32 random bytes -> 64 hex chars; store SHA-256 hex | Lookup by hash; leak of DB does not leak tokens. |
| Token lifetime | 30 days, `expiresAt` returned | Hackathon-friendly. |
| Schema | `SchemaUtils.createMissingTablesAndColumns` on startup | Idempotent; no Flyway for MVP. |

Traps encoded: Railway `DATABASE_URL` is `postgres://user:pass@host:port/db`
and must be converted to `jdbc:postgresql://...` (accept both `postgres://` and
`postgresql://` prefixes; pass through if it already starts with `jdbc:`).
Default when unset: `jdbc:h2:mem:alt;DB_CLOSE_DELAY=-1`. Log which mode at
startup ("Database: postgres" / "h2-in-memory (dev default)").

## 3. Data model (Exposed tables, all ids UUIDv4)

    users(id UUID PK, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL,
          display_name TEXT NULL, created_at TIMESTAMP NOT NULL)
        - email stored lowercase + trimmed
    auth_tokens(id UUID PK, user_id FK->users ON DELETE CASCADE,
          token_hash CHAR(64) UNIQUE NOT NULL, created_at, expires_at,
          last_used_at NULL)
    plans(id UUID PK, user_id FK->users ON DELETE CASCADE, label TEXT NULL,
          origin_input TEXT, origin_name TEXT NOT NULL, origin_id TEXT NULL,
          dest_input TEXT, dest_name TEXT NOT NULL, dest_id TEXT NULL,
          departure TEXT NOT NULL,            -- RFC 3339 with offset, as sent
          journey_snapshot TEXT NULL,         -- opaque JSON blob, max 64 KiB
          created_at TIMESTAMP NOT NULL)
    travels(id UUID PK, user_id FK->users ON DELETE CASCADE,
          plan_id FK->plans NULL ON DELETE SET NULL,
          origin_name TEXT NOT NULL, destination_name TEXT NOT NULL,
          departure TEXT NOT NULL, arrival_planned TEXT NULL,
          rating INT NULL,                    -- 1..5, nullable
          note TEXT NULL,                     -- max 500 chars
          delay_minutes INT NULL,            -- >= 0, nullable
          created_at TIMESTAMP NOT NULL)

Timestamps are stored as RFC 3339 strings (offset-preserving, house rule);
`created_at` uses the injected `Clock`. Delete of a plan keeps the travel
(`ON DELETE SET NULL`) — history must not lose entries.

## 4. API design (all JSON; structured responses, zero prose)

Error envelope = existing: {"error":{"code","message","field"?}}.
New codes: `UNAUTHORIZED` (401), reuse `INVALID_REQUEST` (400), `NOT_FOUND` (404),
`DATABASE_ERROR` (503, DB unreachable). Successes: 200 with body shapes below.

### 4.1 Auth

POST /api/auth/register   body {email, password, displayName?}
  -> 200 {"status":"OK","user":{"id","email","displayName"},
          "token":"<64hex>","expiresAt":"<RFC3339>"}
  400 INVALID_REQUEST when: email missing/not matching
  ^[^@\s]+@[^@\s]+\.[^@\s]+$, password <8 or >128 chars (field email|password);
  email taken -> 400 INVALID_REQUEST field=email message "Email already registered."
  displayName >80 chars -> 400 field=displayName.

POST /api/auth/login     body {email, password}
  -> 200 same shape as register. Wrong email or password ->
  401 UNAUTHORIZED "Invalid email or password." (no user enumeration).

GET  /api/auth/me        Authorization: Bearer <token>
  -> 200 {"status":"OK","user":{...}}
  401 UNAUTHORIZED when missing/unknown/expired.

POST /api/auth/logout    Authorization: Bearer <token>
  -> 200 {"status":"OK"} (deletes that token only). 401 as above.

### 4.2 Saved plans (all Bearer-protected; 401 without valid token)

POST /api/plans          body {origin:{input?,name,id?}, destination:{...},
                           departure:"<RFC3339>", label?, journey?}
  - `origin`/`destination` mirror the place echo the client already received
    from /api/journeys (fields input/name/id). name required on both.
  - departure must parse as RFC 3339 WITH offset (reuse existing timestamp
    validation rules); invalid -> 400 INVALID_REQUEST field=departure.
  - journey snapshot serialized as-is and returned as-is; >65536 bytes ->
    400 INVALID_REQUEST field=journey.
  -> 200 {"plan":{"id","label"?,"origin":{"input","name","id"},
        "destination":{...},"departure","journey"?,"createdAt"}}

GET  /api/plans?limit=   -> 200 {"plans":[...]} newest-first; limit default 20,
  max 100, invalid -> 400 INVALID_REQUEST (same clamp style as /api/locations).

DELETE /api/plans/{id}   -> 200 {"status":"OK"}; 404 NOT_FOUND "Plan not found."
  (404 also when the plan belongs to another user — no enumeration).

### 4.3 Past travels (Bearer-protected)

POST /api/travels        body either {planId} or standalone
                           {origin:{name,...}, destination:{name,...},
                            departure, arrival?} plus optional
                           {rating?, note?, delayMinutes?}
  - planId: plan must exist and belong to the caller -> 400 field=planId
    when not a UUID or not found ("Plan not found." as INVALID_REQUEST,
    or 404 — pick one, document it, stay consistent).
  - rating outside 1..5 -> 400 field=rating; note >500 chars -> 400 field=note;
    delayMinutes <0 -> 400 field=delayMinutes.
  -> 200 {"travel":{"id","planId"?,"origin":{"name"},"destination":{"name"},
        "departure","arrival"?,"rating"?,"note"?,"delayMinutes"?,"createdAt"}}

GET  /api/travels?limit= -> 200 {"travels":[...]} newest-first; limit as plans.

## 5. Files

NEW
    backend/src/main/kotlin/com/alt/backend/db/Database.kt
        connect(url,user,pass) + DATABASE_URL parsing + createMissingTables
    backend/src/main/kotlin/com/alt/backend/db/Tables.kt
    backend/src/main/kotlin/com/alt/backend/auth/PasswordHasher.kt
    backend/src/main/kotlin/com/alt/backend/auth/TokenService.kt  (Clock injected)
    backend/src/main/kotlin/com/alt/backend/auth/CurrentUser.kt   (Bearer parse
        -> resolve user or null; constant-time MessageDigest.isEqual)
    backend/src/main/kotlin/com/alt/backend/api/AuthRoutes.kt
    backend/src/main/kotlin/com/alt/backend/api/PlansRoutes.kt
    backend/src/main/kotlin/com/alt/backend/api/TravelsRoutes.kt
    backend/src/main/kotlin/com/alt/backend/api/AuthDtos.kt       (Dto suffix)
    backend/src/main/kotlin/com/alt/backend/service/UserService.kt
    backend/src/main/kotlin/com/alt/backend/service/PlanService.kt
    backend/src/main/kotlin/com/alt/backend/service/TravelService.kt
    backend/src/test/kotlin/com/alt/backend/api/AuthRoutesTest.kt
    backend/src/test/kotlin/com/alt/backend/api/PlansRoutesTest.kt
    backend/src/test/kotlin/com/alt/backend/api/TravelsRoutesTest.kt

MODIFY (surgical)
    backend/build.gradle.kts       deps only (see §6)
    backend/src/main/kotlin/com/alt/backend/config/AppConfig.kt
        + databaseUrl (DATABASE_URL), auth defaults
    backend/src/main/kotlin/com/alt/backend/config/Plugins.kt
        allowHeader(HttpHeaders.Authorization) in CORS;
        init DB before routes; mount the 3 new route files;
        SQL failures on auth/plans/travels routes -> 503 DATABASE_ERROR
    docs/API_CONTRACT.md            new "Authentication / Saved plans /
                                    Travel history" sections (shapes from §4)
    backend/INTERFACES.md           new seams (UserService, PlanService,
                                    TravelService, TokenService)
    docs/DEPLOYMENT.md              Railway Postgres + DATABASE_URL step

House rules to keep: routes do validation/delegation/translation only;
services take the injected Clock; DTO suffix on boundary types; unknown JSON
body fields ignored (parser already ignoreUnknownKeys=true); missing/invalid
body -> 400; never log emails, tokens, passwords or personal inputs.

## 6. Dependencies (backend/build.gradle.kts)

    implementation("org.jetbrains.exposed:exposed-core:<latest 0.x>")
    implementation("org.jetbrains.exposed:exposed-jdbc:<same>")
    implementation("org.jetbrains.exposed:exposed-java-time:<same>")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("com.h2database:h2:2.2.224")
    implementation("org.postgresql:postgresql:42.7.4")

Pick the Exposed version that resolves against Kotlin 2.0.21 and pin it
exactly. Use `newSuspendedTransaction` for DB calls inside route handlers.

## 7. Tests (ktor-server-test-host, H2 in-memory)

AuthRoutesTest: register ok + shape; duplicate email 400; bad email 400;
short password 400; login ok; login wrong password 401; login unknown email
401 (identical body as wrong password); /me with token; /me without token
401; /me with expired token 401; logout invalidates token (subsequent /me
401); logout with unknown token 401.

PlansRoutesTest: create + list (newest first) roundtrip; limit clamp; create
without token 401; bad departure 400; oversized journey snapshot 400; delete
own plan 200; delete other user's plan 404; delete unknown 404; journey
snapshot returned byte-identical.

TravelsRoutesTest: create from planId (fields copied, planId link); create
standalone; bad rating 400; long note 400; negative delayMinutes 400; list
newest first; without token 401.

All existing tests untouched and green. Run FRESH:
`./gradlew test --rerun-tasks` and count pass/fail from JUnit XML in
`backend/build/test-results/test/` (an UP-TO-DATE banner proves nothing).
JDK: `export JAVA_HOME=$HOME/.local/jdk/jdk-21.0.13.1+2`.

## 8. Verification protocol (do all, in order)

1. Fresh suite green (165 old + new, from XML counts, not the banner).
2. `./gradlew -p backend installDist`, run the distribution on a scratch
   port with NO DATABASE_URL (H2 mode), then curl:
   - register -> login -> create plan -> list plans -> log travel -> list
     travels, chaining the bearer token (full happy path)
   - one auth failure (old token after logout -> 401 envelope)
   - regression: `/health` and `/api/journeys?...` behave exactly as before.
3. Run the same binary WITH a DATABASE_URL pointing at a local H2 file to
   prove the URL parser (postgres:// prefix conversion is code-reviewed;
   live Postgres proof happens at deploy time).
4. `git diff --stat main...feat/user-accounts` — only the files in §5.
5. Commit on feat/user-accounts. Do NOT merge, do NOT push to main.

## 9. Stretch (only if §1-§8 all green, as separate commit)

Mark-as-traveled actuals: when POST /api/travels carries planId and no
arrival, call the existing OJP StopEvent path for the destination stop to
backfill arrivalActual/delayMinutes. Keep it null-safe and demo-labelled if
unverifiable.

## 10. After implementation (Teddy's job, not the agent's)

- Teddy re-runs §8 independently, reviews the diff, posts the new contract
  sections to GitHub Discussion #4 (roles A and D must see them before the
  frontend builds), and asks Sacha for `merge feat/user-accounts`.
- Human step at deploy time: add Railway Postgres service + DATABASE_URL,
  plus AUTH note in DEPLOYMENT.md.
