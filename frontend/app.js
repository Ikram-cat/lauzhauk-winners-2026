import { apiSearch, day, demoBoard, demoSearch, swissClock, swissDeparture, time } from "./journeys.js";

const byId = id => document.getElementById(id);
const form = byId("search");
const origin = form.elements.namedItem("origin");
const destination = form.elements.namedItem("destination");
const departure = form.elements.namedItem("departure");
const base = document.querySelector('meta[name="api-base-url"]').content.trim();
const status = byId("status");
const routes = byId("routes");
const rail = document.querySelector(".rail");
let activeRequest;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function animateTrain() {
  rail.classList.remove("is-moving");
  requestAnimationFrame(() => requestAnimationFrame(() => rail.classList.add("is-moving")));
}

for (let index = 0; index < 60; index++) {
  const tick = document.createElementNS("http://www.w3.org/2000/svg", "line");
  const major = index % 5 === 0;
  for (const [name, value] of Object.entries({
    x1: 120, y1: 13, x2: 120, y2: major ? 37 : 21,
    "stroke-width": major ? 6 : 2.5, transform: `rotate(${index * 6} 120 120)`
  })) tick.setAttribute(name, value);
  byId("ticks").append(tick);
}

const rows = demoBoard(new Date());
const next = rows[0];
for (const row of rows) {
  const tr = element("tr");
  const platform = element("td");
  platform.append(element("span", row.platform, "platform"));
  tr.append(element("td", time(row.departure)), element("td", row.destination), platform,
    element("td", row.delay ? `+${row.delay} min` : "À l’heure", row.delay ? "delay" : ""));
  byId("board-rows").append(tr);
}

if (base) byId("mode").textContent = "Recherche API";
else {
  byId("next").hidden = false;
  byId("next-route").textContent = `Lausanne → ${next.destination}`;
  byId("next-time").textContent = time(next.departure);
}

function updateClock() {
  const now = new Date();
  const [hour, minute, second] = swissClock(now);
  for (const [id, angle] of [
    ["hour", (hour % 12 + minute / 60) * 30],
    ["minute", (minute + second / 60) * 6],
    ["second", second * 6]
  ]) byId(id).setAttribute("transform", `rotate(${angle} 120 120)`);
  byId("digital").textContent = [hour, minute, second].map(value => String(value).padStart(2, "0")).join(":");
  if (!base) {
    const left = Math.ceil((next.departure - now) / 1000);
    byId("countdown").textContent = left > 0
      ? `Dans ${String(Math.floor(left / 60)).padStart(2, "0")}:${String(left % 60).padStart(2, "0")}`
      : "Horaire prévu écoulé";
  }
}

let ticker;
function syncClock() {
  clearInterval(ticker);
  if (document.hidden) return;
  updateClock();
  ticker = setInterval(updateClock, 250);
}
document.addEventListener("visibilitychange", syncClock);
syncClock();
byId("clock").addEventListener("click", event => {
  const clock = event.currentTarget;
  const digital = clock.getAttribute("aria-pressed") === "false";
  clock.setAttribute("aria-pressed", String(digital));
  clock.setAttribute("aria-label", digital ? "Afficher l’horloge analogique" : "Afficher l’heure numérique");
});

byId("swap").addEventListener("click", () => {
  [origin.value, destination.value] = [destination.value, origin.value];
  byId("rail-from").textContent = origin.value;
  byId("rail-to").textContent = destination.value;
  animateTrain();
});

const provenance = { MOCK: "Démo", LIVE_SCHEDULE: "Horaire fournisseur" };
function renderRoute(journey, index) {
  const card = element("details", "", "route-card");
  card.name = "itineraries";
  card.open = index === 0;
  const summary = element("summary");
  const crossesDate = day(journey.departure) !== day(journey.arrival);
  const timeLabel = date => time(date) + (crossesDate ? " · " + day(date) : "");
  summary.append(element("strong", `${timeLabel(journey.departure)} → ${timeLabel(journey.arrival)}`));
  const transfers = journey.transfers === 0 ? "Direct" : `${journey.transfers} correspondance(s)`;
  summary.append(element("span",
    `${journey.durationMinutes} min · ${transfers} · ${provenance[journey.dataStatus] ?? "Source inconnue"}`, "route-meta"));
  const legs = element("ol", "", "legs");
  for (const leg of journey.legs) {
    const service = leg.serviceLabel ? ` · ${leg.serviceLabel}` : "";
    const platform = leg.departurePlatform ? ` · Voie ${leg.departurePlatform}` : "";
    legs.append(element("li", `${timeLabel(leg.departure)} ${leg.origin} → ${timeLabel(leg.arrival)} ${leg.destination}${service}${platform}`));
  }
  card.append(summary, legs);
  card.addEventListener("toggle", () => { if (card.open) animateTrain(); });
  return card;
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  activeRequest?.abort();
  const request = new AbortController();
  activeRequest = request;
  const from = origin.value.trim();
  const to = destination.value.trim();
  routes.replaceChildren();
  byId("results").hidden = false;
  status.className = "loading";
  status.textContent = "Recherche des itinéraires…";
  try {
    if (!from || !to || from.toLowerCase() === to.toLowerCase()) throw new Error("Choisissez deux gares différentes.");
    const date = swissDeparture(departure.value);
    byId("rail-from").textContent = from;
    byId("rail-to").textContent = to;
    const journeys = base
      ? await apiSearch(base, from, to, date, AbortSignal.any([request.signal, AbortSignal.timeout(10_000)]))
      : await demoSearch(from, to, date, request.signal);
    if (request.signal.aborted) return;
    routes.replaceChildren(...journeys.map(renderRoute));
    status.textContent = journeys.length
      ? `${from} → ${to} · ${journeys.length} itinéraire(s)`
      : base ? "Aucun itinéraire trouvé. Essayez une autre heure."
        : "Pas d’itinéraire de démonstration pour ces gares. Essayez Lausanne → Genève.";
    if (journeys.length) animateTrain();
  } catch (error) {
    if (request.signal.aborted) return;
    status.textContent = error.name === "TimeoutError" ? "La recherche a expiré. Veuillez réessayer."
      : error instanceof TypeError ? "Impossible de joindre le service. Veuillez réessayer." : error.message;
  } finally {
    if (activeRequest === request) status.className = "";
  }
  if (!request.signal.aborted) byId("results").scrollIntoView({ block: "nearest" });
});

animateTrain();
