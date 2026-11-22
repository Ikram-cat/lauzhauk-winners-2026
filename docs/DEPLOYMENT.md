# Deployment runbook

D owns the production release and records results in the [session handoff](SESSION_HANDOFF.md). The backend is live on Railway; the Vercel frontend is still pending. The backend section records the configuration that actually runs in production, the frontend section the target configuration. The master plan reports existing Vercel access for a team member. Verify repository access, account permissions, usage limits, and rollback availability with the hosting owners before the first release. [Master plan](MASTER_PLAN.md)

## Environment variables

| Name | Consumer | Local use | Hosting configuration |
| --- | --- | --- | --- |
| `API_BASE_URL` | Frontend | Set to the running local backend origin, such as `http://localhost:8080`. | Set to the Railway HTTPS origin with no path or trailing slash. Supply it when building the static frontend if the bundle embeds its configuration. |
| `PORT` | Backend | Planned local default is `8080`. | Listen on the port Railway injects at runtime. |
| `CORS_ALLOWED_ORIGINS` | Backend | Set to the actual local frontend origin, including its port. | Comma-separated list containing the Vercel production origin and only the preview origins approved for that backend environment. |

Use [the environment template](../.env.example) as a reference for variable names. In local development, export values in the shell before running the applicable build or server. Keep secrets in the platform's server-side variable store and only expose the public backend URL to browser code. Changes to Vercel build-time values require a new deployment. [Vercel environment variables](https://vercel.com/docs/environment-variables)

## Backend on Railway

### As built (2026-11-22)

| Item | Value |
| --- | --- |
| Account | Railway CLI 5.75.0, `sabu8craft@gmail.com`, workspace *My Projects* |
| Project | `lauzhauk-backend` (`20402d86-1efd-4f47-8671-0c8b937a2d2c`), environment `production` |
| Service | `lauzhauk-backend` (`fc7b6632-d1d4-438a-b55c-a8982f554bc8`), region `sfo` |
| Service root directory | `backend` — the Docker build context is the backend module only |
| Build | `backend/Dockerfile` via `backend/railway.json` (`builder: DOCKERFILE`, `healthcheckPath: /health`, `healthcheckTimeout: 120`, restart on failure ×3) |
| Public URL | `https://lauzhauk-backend-production.up.railway.app` (also aliased as `https://lauzhauk-winners-2026-production.up.railway.app`) |
| Runtime port | `PORT` is injected by Railway; `Application.kt` reads `System.getenv("PORT")` and falls back to 8080. The image's `ENV PORT=8080` is only the local default. |

A third project named `alt-backend` was attempted first and refused by the plan:
`Free plan resource provision limit exceeded. Please upgrade to provision more
resources!` Creating a project is only possible after upgrading or freeing an
existing project; the deploy therefore went into the existing `lauzhauk-backend`
project.

### Variables

Set with `railway variables --service lauzhauk-backend --skip-deploys --set KEY=value`.
Values are read from local files (`~/lauzhack-ojp.env`, `~/lauzhack-gtfsrt.env`) and
never committed to the repository.

| Name | Value / source |
| --- | --- |
| `TRANSPORT_PROVIDER` | `ojp` |
| `OJP_API_KEY` | OJP key, server-side only |
| `GTFS_RT_API_KEY` | GTFS-RT key, server-side only |
| `CORS_ALLOWED_ORIGINS` | **not set** — backend defaults to accepting any host. Set it to the Vercel origin once the frontend URL exists, then redeploy. |

### Deploy and expose

```bash
cd <repo-root>                     # service root dir = backend, so upload the repo
railway link --project lauzhauk-backend
railway up --service lauzhauk-backend --ci
railway domain                     # attach/print the public domain
```

Pitfalls confirmed on this service:

- `railway up` from inside `backend/` uploads the repository root and Railpack then
  fails with *"could not determine how to build the app"* — always run it from the
  repository root once the service root directory is `backend`.
- `railway up backend` (path argument) fails with `prefix not found`.
- The service root directory is a service-instance setting, not a repository file:
  `serviceInstanceUpdate(input: {rootDirectory: "backend"})` over the Railway
  GraphQL API, or Settings → Source in the dashboard.

### Verify the release

```bash
BASE=https://lauzhauk-backend-production.up.railway.app
./scripts/smoke.sh "$BASE"                                    # 22 checks, exit 1 on failure
curl -fsS "$BASE/health"                                      # 200, feeds.ojp = OK
curl -fsS "$BASE/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve&limit=2"
```

A green smoke run proves the Dockerfile builds the Gradle distribution, the
service binds to Railway's `PORT`, `/health` answers 200 for the health check, and
the OJP/GTFS-RT credentials resolve at runtime.

## Frontend on Vercel

1. Connect the same GitHub repository to Vercel, select `main` as the production branch, and set the Root Directory to `frontend`. Use an isolated preview deployment to validate build settings before production.
2. Select the static build settings documented by A in the [frontend README](../frontend/README.md). For Compose, install the selected JDK in the build environment, use the checked-in Gradle wrapper and the proven browser production task, and set the Output Directory to the generated static directory relative to `frontend`. Verify it contains `index.html` and required JS/Wasm assets. If the team adopts the HTML/JS fallback, point Vercel at the verified static output and use its recorded build/configuration step. [Vercel build settings](https://vercel.com/docs/builds/configure-a-build)
3. Set `API_BASE_URL` for the target environment. Start with the verified Railway backend URL. If preview builds use a separate backend, configure that preview URL separately. When the frontend origin is assigned, add it to the target backend's CORS list and apply the backend settings before the browser smoke test.
4. Confirm the latest production deployment corresponds to the intended `main` revision. Complete the public checks below before assigning a milestone tag.

## Public verification

With the published Railway HTTPS origin exported as `API_BASE_URL`, run these checks from PowerShell.

```powershell
curl.exe --fail-with-body "$env:API_BASE_URL/health"
curl.exe --fail-with-body "$env:API_BASE_URL/api/journeys?origin=Lausanne&destination=Gen%C3%A8ve"
```

Confirm health returns HTTP 200 and that a service-time search returns at least one `LIVE_SCHEDULE` journey through the real provider. Open the public Vercel URL in a clean browser and repeat the search, confirming the HTTPS response, absence of CORS errors and rendered itinerary. Follow the [smoke test checklist](TESTING.md#deployed-smoke-test) and record the two deployment IDs or revisions, URLs, and outcomes in the [session handoff](SESSION_HANDOFF.md). A mock replay can support the demo during an outage. Mark it as mock and keep the real itinerary smoke test pending until it passes. [Demo script](DEMO_SCRIPT.md)

## Rollback

1. Identify the last verified frontend/backend revision pair, their public origins, and the effective environment settings. A recent checkpoint tag or deployment record provides the starting point. Determine whether a single service can roll back while retaining compatibility with the other.
2. If needed and available for the current account, use Vercel's Instant Rollback on the eligible frontend deployment and Railway's rollback action on the retained backend deployment. Confirm the restored configuration and remember that an existing frontend artifact can embed an older API URL. To resume automatic Vercel production promotion after rollback, follow the documented promotion procedure. [Vercel Instant Rollback](https://vercel.com/docs/instant-rollback) · [Railway deployment actions](https://docs.railway.com/deployments/deployment-actions)
3. If the previous deployment cannot be restored, have the authorized integration owner merge a reviewed revert to `main` and deploy from that reviewed revision. In either case, repeat health, browser/CORS, and real search verification. Record the replacement deployment IDs, restored revision, settings needed, and follow-up in the [session handoff](SESSION_HANDOFF.md).


## Challenge build and inference

Keep the Web deployment and record a reproducible second-target launch command and demo environment. Configure any optional model-provider secret on the backend only, cap calls and timeouts, and verify search works when extraction is unavailable. Confirm provider data handling before accepting personal images. See the [challenge plan](CHALLENGES.md).
