// Minimal static server + API proxy for the Compose/Wasm build.
// Usage: node frontend/kmp/serve.mjs   ->   http://127.0.0.1:8788/
// Serves composeApp/build/dist/wasmJs/productionExecutable and forwards
// /api/* and /health to the deployed Ktor backend (override with ALT_API_BASE).
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, "composeApp/build/dist/wasmJs/productionExecutable");
const API = (process.env.ALT_API_BASE || "https://lauzhauk-winners-2026-production.up.railway.app").replace(/\/$/, "");
const PORT = Number(process.env.ALT_PORT || 8788);

const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".wasm": "application/wasm",
  ".json": "application/json",
  ".css": "text/css; charset=utf-8",
  ".map": "application/json",
  ".txt": "text/plain; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml",
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  if (url.pathname.startsWith("/api/") || url.pathname === "/health") {
    try {
      const upstream = await fetch(API + url.pathname + url.search, {
        method: req.method,
        headers: { "content-type": req.headers["content-type"] || "application/json" },
        body: ["GET", "HEAD"].includes(req.method) ? undefined : req,
        duplex: "half",
      });
      res.writeHead(upstream.status, {
        "content-type": upstream.headers.get("content-type") || "application/json",
        "access-control-allow-origin": "*",
      });
      res.end(Buffer.from(await upstream.arrayBuffer()));
    } catch (e) {
      res.writeHead(502, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { code: "UPSTREAM_ERROR", message: String(e) } }));
    }
    return;
  }
  let file = path.join(root, url.pathname === "/" ? "index.html" : url.pathname);
  if (!file.startsWith(root)) { res.writeHead(403); return res.end("forbidden"); }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, "index.html");
  res.writeHead(200, { "content-type": types[path.extname(file)] || "application/octet-stream" });
  fs.createReadStream(file).pipe(res);
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`ALT wasm preview: http://127.0.0.1:${PORT}/  (API proxy -> ${API})`);
});
