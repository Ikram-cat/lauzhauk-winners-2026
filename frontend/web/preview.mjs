import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

const upstream = new URL(process.env.ALT_API_BASE ?? "https://lauzhauk-winners-2026-production.up.railway.app");
if (!["https:", "http:"].includes(upstream.protocol) || upstream.username || upstream.password) {
  throw new Error("ALT_API_BASE must be an HTTP(S) origin without credentials");
}
const port = Number(process.env.ALT_PREVIEW_PORT ?? 8790);
const files = new Map([
  ["/", ["index.html", "text/html"]],
  ["/style.css", ["style.css", "text/css"]],
  ["/swiss-boundary.geojson", ["swiss-boundary.geojson", "application/geo+json"]],
  ...["app", "api", "copy"].map(name => [`/${name}.mjs`, [`${name}.mjs`, "text/javascript"]]),
]);

createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  if (req.method !== "GET") return res.writeHead(405).end();
  if (["/api/locations", "/api/journeys"].includes(url.pathname)) {
    try {
      const response = await fetch(new URL(url.pathname + url.search, upstream), {
        signal: AbortSignal.timeout(23000),
        headers: { accept: "application/json" },
      });
      const body = await response.text();
      if (!response.headers.get("content-type")?.includes("application/json")) throw new Error("Invalid API response");
      return res.writeHead(response.status, { "Content-Type": "application/json", "Cache-Control": "no-store" }).end(body);
    } catch (error) {
      const timedOut = error.name === "TimeoutError";
      return res.writeHead(timedOut ? 504 : 502, { "Content-Type": "application/json" })
        .end(JSON.stringify({ error: { code: timedOut ? "UPSTREAM_TIMEOUT" : "UPSTREAM_ERROR" } }));
    }
  }
  const file = files.get(url.pathname);
  if (!file) return res.writeHead(404).end();
  try {
    const body = await readFile(new URL(file[0], import.meta.url));
    return res.writeHead(200, { "Content-Type": file[1] + "; charset=utf-8", "Cache-Control": "no-store" }).end(body);
  } catch {
    return res.writeHead(500).end();
  }
}).listen(port, "127.0.0.1", () => console.log(`ALT preview http://127.0.0.1:${port}/`));
