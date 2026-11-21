import { readFile, writeFile } from "node:fs/promises";

const args = process.argv.slice(2);
if (args.length > 1 || (args.length === 1 && args[0] !== "--require-api")) {
    throw new Error("Usage: node scripts/configure-web.mjs [--require-api]");
}

const configured = process.env.API_BASE_URL?.trim() ?? "";
if (!configured && args.includes("--require-api")) {
    throw new Error("Set API_BASE_URL to the public Ktor origin before deployment.");
}

let origin = "";
if (configured) {
    const url = URL.parse(configured);
    if (!url || !["http:", "https:"].includes(url.protocol) ||
        url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
        throw new Error("API_BASE_URL must be an HTTP or HTTPS origin with no credentials, path, query, or fragment.");
    }
    origin = url.origin;
}

const index = new URL("../build/dist/wasmJs/productionExecutable/index.html", import.meta.url);
const html = await readFile(index, "utf8");
const tag = /<meta name="api-base-url" content="[^"]*">/g;
if ([...html.matchAll(tag)].length !== 1) {
    throw new Error("The Wasm distribution must contain exactly one api-base-url meta tag.");
}

const escapedOrigin = origin.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;");
await writeFile(index, html.replace(tag, () => `<meta name="api-base-url" content="${escapedOrigin}">`), "utf8");
console.log(origin ? "Configured the Web bundle for the public API origin." : "Configured the Web bundle for demo mode.");
