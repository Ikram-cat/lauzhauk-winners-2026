# Web/Wasm deployment

The Web version runs the shared Kotlin Compose app. Its static bundle can be hosted on any HTTPS origin that serves WebAssembly as `application/wasm`. The deployed frontend origin and the Railway backend origin are separate.

## Build and configure

Run from `frontend/` with JDK 17 or 21 and Node.js 22 or later. On Windows use `.\gradlew.bat` and set the environment variable with `$env:API_BASE_URL = "https://your-backend.example"`.

```sh
bash ./gradlew wasmJsBrowserDistribution
API_BASE_URL=https://your-backend.example node scripts/configure-web.mjs --require-api
```

Replace the example origin with the deployed Ktor URL. Use only the origin, with no `/api` suffix or credentials. The script writes the public API origin to the generated HTML, so it may be rerun for a different deployment without recompiling Kotlin. Publish the entire `build/dist/wasmJs/productionExecutable` directory, including its compiled assets. For a demo build, leave `API_BASE_URL` unset and omit `--require-api`.

## Verify and coordinate CORS

For local backend integration, configure the bundle with `API_BASE_URL=http://localhost:8080` and serve it over HTTP.

```sh
python -m http.server 8773 --bind 127.0.0.1 --directory build/dist/wasmJs/productionExecutable
```

Open `http://127.0.0.1:8773/`. Allow that exact origin in the backend's `CORS_ALLOWED_ORIGINS`. For hosting, add the exact production HTTPS origin and any approved preview origins. Check `/health` on the backend, then search from the deployed browser app and verify that requests reach the intended backend.
