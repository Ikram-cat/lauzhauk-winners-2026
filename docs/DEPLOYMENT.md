# Deployment runbook

D owns the production release and records results in the [session handoff](SESSION_HANDOFF.md). This runbook describes the target configuration. The application build, Dockerfile, hosting connections, deploy URLs, and public smoke tests are pending in the repository. The master plan reports existing Vercel access for a team member. Verify repository access, account permissions, usage limits, and rollback availability with the hosting owners before the first release. [Master plan](MASTER_PLAN.md)

## Environment variables

| Name | Consumer | Local use | Hosting configuration |
| --- | --- | --- | --- |
| `API_BASE_URL` | Frontend | Set to the running local backend origin, such as `http://localhost:8080`. | Set to the Railway HTTPS origin with no path or trailing slash. Supply it when building the static frontend if the bundle embeds its configuration. |
| `PORT` | Backend | Planned local default is `8080`. | Listen on the port Railway injects at runtime. |
| `CORS_ALLOWED_ORIGINS` | Backend | Set to the actual local frontend origin, including its port. | Comma-separated list containing the Vercel production origin and only the preview origins approved for that backend environment. |

Use [the environment template](../.env.example) as a reference for variable names. In local development, export values in the shell before running the applicable build or server. Keep secrets in the platform's server-side variable store and only expose the public backend URL to browser code. Changes to Vercel build-time values require a new deployment. [Vercel environment variables](https://vercel.com/docs/environment-variables)

## Backend on Railway

1. After B supplies a runnable app, review `backend/Dockerfile` with B. Use the tested Gradle wrapper and JDK to build the Ktor artifact, then run only the built artifact and its JVM runtime in the final image. Run the same artifact locally before connecting the service. See the [backend README](../backend/README.md) for the selected toolchain and commands.
2. Connect the GitHub repository to a Railway service and select `main` for production. Set the service root to `/backend` and keep the build inputs in that directory. If a future shared Gradle project requires repository-level inputs, coordinate a build-context change with D and recheck local, CI, and Railway builds. Confirm the build log identifies the Dockerfile. [Railway monorepos](https://docs.railway.com/deployments/monorepo) · [Railway Dockerfiles](https://docs.railway.com/builds/dockerfiles)
3. Bind Ktor to `0.0.0.0` and the runtime `PORT`. Configure `/health` as the deployment health check and return HTTP 200 with `{"status":"ok"}` once the server accepts requests. Keep the health route independent of provider availability. Enable public HTTPS networking and verify the domain's target port. [Railway health checks](https://docs.railway.com/deployments/healthchecks) · [Railway public networking](https://docs.railway.com/networking/public-networking)
4. Set `CORS_ALLOWED_ORIGINS` to the frontend origins from the table. Parse each entry as a complete origin and configure Ktor CORS with its explicit scheme, hostname, and port if present. Allow the methods and headers the browser client actually uses. Keep browser credentials disabled for the public read-only MVP. [Ktor CORS configuration](https://ktor.io/docs/server-cors.html)

After `backend/Dockerfile` and the health route exist, run this container check from the repository root with Docker available.

```powershell
docker build --tag cff-plan-b-backend:local ./backend
docker run --rm -p 127.0.0.1:8080:8080 -e PORT=8080 cff-plan-b-backend:local
```

In a second terminal, run `curl.exe --fail-with-body http://localhost:8080/health`. Confirm an HTTP 200 response body matches the [API contract](API_CONTRACT.md). Stop the local container with Ctrl+C when finished.

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
