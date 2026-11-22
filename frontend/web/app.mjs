import { copy, languageNames, welcomeHeadings } from "./copy.mjs";
import { distanceKm, hasCoordinates, locationValue, otherJourneys, request, swissBoundary, swissDateTime, swissInstant, swissLocations } from "./api.mjs";

const app = document.querySelector("#app");
const announcement = document.querySelector("#announcement");
const intro = document.querySelector(".intro");
document.addEventListener("focusin", () => { intro.hidden = true; }, {once:true});
const dark = matchMedia("(prefers-color-scheme: dark)");
const saved = key => { try { return localStorage.getItem("alt-" + key); } catch { return null; } };
let language = Object.hasOwn(languageNames, saved("language")) ? saved("language") : "en";
let theme = saved("theme") || "system";
const lastWelcome = saved("welcome-heading");
const welcome = lastWelcome === null ? 0 : (Number(lastWelcome) + 1) % welcomeHeadings.length || 0;
try { localStorage.setItem("alt-welcome-heading", String(welcome)); } catch { /* Storage can be disabled. */ }
const mobile = matchMedia("(max-width: 700px)");
const state = {
  origin: null, destination: null, preference: "FASTEST", activeQuery: null, collapsed: false,
  departure: "", arrivalDeadline: "", fareClass: "2", scheduled: false, result: null, error: "", field: "",
  busy: false, chronological: false, shown: 7, drafts: {},
};
let searchController, observer, disposeFields = [];
let activeInfoTooltip = null;
const t = key => copy(language, key);
const esc = value => String(value ?? "").replace(/[&<>"']/g, character => ({
  "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
})[character]);
const checked = value => value ? "checked" : "";
const formatTime = (value, date = false) => value ? new Intl.DateTimeFormat(language, {
  timeZone: "Europe/Zurich", hourCycle: "h23", hour: "2-digit", minute: "2-digit",
  ...(date ? { month: "short", day: "numeric" } : {}),
}).format(new Date(value)) : "—";
const number = value => new Intl.NumberFormat(language).format(value);
const percent = value => new Intl.NumberFormat(language, {style:"percent", maximumFractionDigits:2}).format(value);
const money = value => value == null ? "CHF —" : new Intl.NumberFormat(language, {style:"currency", currency:"CHF"}).format(value);
const token = value => value ? esc(t(value.toLowerCase()) === value.toLowerCase() ? value : t(value.toLowerCase())) : "—";
const paths = {
  station: '<rect x="5" y="3" width="14" height="15" rx="2"/><path d="M5 11h14M9 18l-3 4m9-4 3 4M8.5 14.5h.1m6.8 0h.1M12 4v7"/>',
  location: '<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3"/>',
  settings: '<path d="M3 6h18M3 12h18M3 18h18"/><path d="M8 4v4m8 2v4m-5 2v4" stroke-width="3"/>',
  close: '<path d="m5 5 14 14M19 5 5 19"/>',
  warning: '<path d="M12 3 2 21h20Z"/><path d="M12 9v5m0 3v.5"/>',
  clear: '<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',
  walking: '<circle cx="14" cy="4" r="2"/><path d="m11 21 2-6-3-4m5 10 1-5-3-4 1-4m-7 5 3-4 4-1 3 4h3"/>',
};
const icon = name => `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[name]}</svg>`;
const button = (action, label, classes = "", extra = "") =>
  `<button type="button" data-action="${action}" class="${classes}" ${extra}>${t(label)}</button>`;
function announce(key) { announcement.textContent = t(key); }
function preferences() {
  document.documentElement.lang = language;
  document.documentElement.dataset.theme = theme === "system" ? (dark.matches ? "dark" : "light") : theme;
  try {
    for (const [key, value] of Object.entries({ language, theme })) localStorage.setItem("alt-" + key, value);
  } catch { /* Browsing state works when storage is unavailable. */ }
}
dark.addEventListener("change", preferences);

const clockFormat = new Intl.DateTimeFormat("en-GB", {
  timeZone: "Europe/Zurich", hourCycle: "h23", hour: "2-digit", minute: "2-digit", second: "2-digit",
});
function updateClock() {
  const face = app.querySelector(".clock");
  if (!face) return;
  const parts = Object.fromEntries(clockFormat.formatToParts(new Date()).map(({type, value}) => [type, Number(value)]));
  const angles = [(parts.hour % 12 + parts.minute / 60) * 30, (parts.minute + parts.second / 60) * 6, parts.second * 6];
  face.querySelectorAll("[data-hand]").forEach((hand, i) => hand.setAttribute("transform", "rotate(" + angles[i] + " 50 50)"));
  face.setAttribute("aria-label", t("clock") + " · " + clockFormat.format(new Date()).slice(0, 5));
}
function clock() {
  const ticks = Array.from({length: 60}, (_, i) =>
    `<path d="M50 6v${i % 5 === 0 ? 9 : 3}" stroke-width="${i % 5 === 0 ? 2.6 : 1}" transform="rotate(${i * 6} 50 50)"/>`).join("");
  return `<svg class="clock" viewBox="0 0 100 100" role="img" aria-label="${t("clock")}">
    <circle class="clock-face" cx="50" cy="50" r="48"/>${ticks}
    <path data-hand d="M50 55V26" stroke-width="5"/><path data-hand d="M50 56V16" stroke-width="3.5"/>
    <g data-hand class="seconds"><path d="M50 61V15"/><circle cx="50" cy="15" r="4"/></g>
    <circle cx="50" cy="50" r="2" fill="currentColor"/>
  </svg>`;
}
function hero() {
  return `<div class="lead"><div><p class="eyebrow">${t("planner")}</p>
    <h1>${welcomeHeadings[welcome][language]}</h1></div>${clock()}</div>`;
}
function placeButtons(places, reference) {
  return places.map((place, i) => {
    const distance = distanceKm(reference, place.coordinates);
    const away = distance == null ? "" : " · " + t("away").replace("{n}", new Intl.NumberFormat(language, {maximumFractionDigits:1}).format(distance));
    let depth = 0;
    const name = place.name.split(/([()])/).map(part => {
      if (part === "(") depth++;
      const context = depth > 0 || part === ")";
      if (part === ")") depth = Math.max(0, depth - 1);
      return context ? `<span class="suggestion-context">${esc(part)}</span>` : esc(part);
    }).join("");
    return `<button type="button" data-choice="${i}"><strong>${name}</strong>${away}<small>${t(place.kind === "STOP" ? "station" : "address")}</small></button>`;
  }).join("");
}
function field(id, label) {
  return `<div class="field" data-place="${id}"><label for="${id}">${t(label)}</label>
    <div class="place-input"><input id="${id}" type="search" autocomplete="off" autocapitalize="off" maxlength="120"
      placeholder="${t("searchPlace")}" value="${esc(state[id]?.name ?? state.drafts[id])}" aria-invalid="${state.field === id}" aria-describedby="${id}-status" aria-controls="${id}-choices" ${state.busy ? "disabled" : ""}>
    <button type="button" class="icon-button locate" title="${t("location")}" aria-label="${t(label)} · ${t("location")}" ${state.busy ? "disabled" : ""}>${icon("location")}</button></div>
    <div id="${id}-choices" class="suggestions"></div><div id="${id}-status" class="caption" role="status"></div></div>`;
}
function modes() {
  return `<div class="modes" role="group" aria-label="${t("rankBy")}">${["fastest","simplest"].map(key =>
    `<div class="mode-info" data-selected="${state.preference === key.toUpperCase()}"><button type="button" data-action="mode" data-value="${key.toUpperCase()}"
      aria-pressed="${state.preference === key.toUpperCase()}" ${state.busy ? "disabled" : ""}>${t(key)}</button>
      <button type="button" class="info" data-action="info" data-value="${key}" aria-label="${t("aboutMode")} ${t(key)}"
        aria-expanded="false" aria-controls="mode-help"><span aria-hidden="true">?</span></button></div>`).join("")}
    <p class="mode-help" id="mode-help" role="status" hidden></p></div>`;
}
function planner() {
  const locked = state.busy ? "disabled" : "";
  const query = state.activeQuery;
  const summary = query ? `<button type="button" class="mobile-summary" data-action="editSearch" aria-expanded="${!state.collapsed}" aria-controls="planner">
    <strong>${esc(query.origin)} → ${esc(query.destination)}</strong>
    <span>${t(query.preference.toLowerCase())} · ${query.now ? t("now") + " · " : ""}${formatTime(query.departure, true)}</span>
    <span class="summary-edit">${t(state.collapsed ? "editSearch" : "hideSearch")}</span></button>` : "";
  return `<section class="search-panel ${state.collapsed ? "collapsed" : ""}">${summary}<div class="form-frame">
    <form class="rail" id="planner" ${mobile.matches && state.collapsed ? "inert" : ""}>${field("origin", "from")}${field("destination", "to")}
    ${departureField(locked)}
    ${dateField("arrivalDeadline", "arriveBy")}
    <p class="caption">${t("swissTime")}</p>
    <label class="field"><span>${t("fareClass")}</span><select name="fareClass" aria-invalid="${state.field === "fareClass"}" ${locked}>
      <option value="2" ${state.fareClass === "2" ? "selected" : ""}>${t("second")}</option><option value="1" ${state.fareClass === "1" ? "selected" : ""}>${t("first")}</option>
    </select></label>
    ${modes()}
    ${state.error ? `<div class="notice warning" role="alert">${icon("warning")}${t(state.error)}</div>` : ""}
    <button type="submit" class="primary ${state.busy ? "loading" : ""}" ${locked}>${t(state.busy ? "searching" : state.error && !state.field ? "retry" : "find")}</button>
  </form></div></section>`;
}
function departureField(locked) {
  const [date, time] = (state.scheduled ? state.departure : swissDateTime()).split("T");
  const invalid = state.field === "departure";
  return `<fieldset class="date-fields"><legend>${t("depart")}</legend><div class="field-group">
    <label class="field"><span>${t("date")}</span><input id="departure" type="date" value="${esc(date)}" aria-invalid="${invalid}" ${locked}></label>
    <label class="field"><span>${t("time")} · 24h</span><select name="departureMode" ${locked}>
      <option value="now" ${!state.scheduled ? "selected" : ""}>${t("now")}</option>
      <option value="scheduled" ${state.scheduled ? "selected" : ""}>${t("scheduled")}</option></select></label>
    ${state.scheduled ? `<label class="field departure-time"><span>${t("departureTime")} · HH:mm</span>
      <input id="departure-time" type="text" inputmode="numeric" maxlength="5" value="${esc(time)}"
        placeholder="14:30" aria-invalid="${invalid}" ${locked}></label>` : ""}
    </div></fieldset>`;
}
function dateField(name, label) {
  const [date = "", time = ""] = state[name].split("T");
  return `<fieldset class="date-fields"><legend>${t(label)}</legend><div class="field-group">
    <label class="field"><span>${t("date")}</span><input id="${name}" type="date" value="${esc(date)}" data-date="${name}" aria-invalid="${state.field === name}" ${state.busy ? "disabled" : ""}></label>
    <label class="field"><span>${t("time")} · 24h</span><input type="text" inputmode="numeric" maxlength="5" placeholder="14:30"
      value="${esc(time)}" data-date="${name}" aria-invalid="${state.field === name}" ${state.busy ? "disabled" : ""}></label>
    </div></fieldset>`;
}

const coordinateText = /[-+]?\d{1,3}\.\d{3,}\s*[,;/ ]\s*[-+]?\d{1,3}\.\d{3,}/;
const placeLabel = (name, fallback) => esc(name && !coordinateText.test(name) ? name :
  fallback && !coordinateText.test(fallback) ? fallback : t("unnamedStop"));

function historicalRate(risk) {
  if (risk?.corridorIncidentRate == null) return "";
  const basis = (risk.basis ?? "").toUpperCase();
  const key = basis === "HOURLY_BASELINE" ? "historicHour" :
    risk.source?.toUpperCase() === "BASELINE" || basis.includes("BASELINE") ? "historicBaseline" : "historicRate";
  const window = /^DAY_WINDOW_(\d+)$/.exec(basis);
  const rate = t(key).replace("{n}", percent(risk.corridorIncidentRate));
  return window ? rate + " · " + t("historicWindow").replace("{n}", number(Number(window[1]))) : rate;
}
const reasonKeys = {
  EARLIER_ARRIVAL: ["reasonEarlier","savedMinutes"], EXPECTED_FASTER: ["reasonExpected","savedMinutes"],
  FEWER_TRANSFERS: ["reasonTransfers","savedTransfers"], CHEAPER: ["reasonCheaper","savedChf"],
  DEADLINE_MET: ["deadlineMet"], DEADLINE_MISSED: ["reasonMargin","marginMinutes"],
  STAY_FASTEST: ["reasonFastest"],
  PRIMARY_LEG_CANCELLED: ["cancelled"], SUPPRESSED_STOP: ["disruption"],
  PRIMARY_LEG_DELAYED: ["disruption"], EARLIER_DEPARTURE_CANCELLED: ["cancelled"],
};
function reasonText(reason) {
  const [key, field] = reasonKeys[reason.code] ?? [];
  if (!key) return "";
  const value = reason[field];
  return t(key).replace("{n}", value == null ? "—" : field === "savedChf" ? money(value) : number(value));
}
function metadata(journey) {
  const { dataStatus, realtime, verified, feasible, maxOccupancy, risk, fares } = journey;
  const riskData = historicalRate(risk);
  return [
    dataStatus === "MOCK" && t("mock"), !verified && t("estimated"),
    realtime?.sampleCount != null && `${t("samples")} ${number(realtime.sampleCount)}`,
    fares?.selectedChf == null && t("noPrice"), riskData,
    maxOccupancy && `${t("occupancy")} ${token(maxOccupancy)}`,
    feasible === false && t("deadlineMiss"),
  ].filter(Boolean).join(" · ");
}
const arrival = journey => journey.realtime?.expectedArrival ?? journey.schedule.arrival;
const duration = minutes => `${Math.floor(minutes / 60)}h${String(minutes % 60).padStart(2, "0")}`;
function timeDisplay(value) {
  const [hour, minute] = formatTime(value).split(":");
  return minute ? `<time datetime="${esc(value)}"><span>${hour}</span><span>:</span><span>${minute}</span></time>` : esc(hour);
}
const resultTimes = journey => `${timeDisplay(journey.schedule.departure)} <span aria-hidden="true">→</span> <span class="sr-only">${t("to")}</span> ${timeDisplay(journey.schedule.arrival)}`;
const travelTimes = journey => `${formatTime(journey.schedule.departure)} → ${formatTime(journey.schedule.arrival)}`;
function facts(journey) {
  return `<dl class="facts">
    <div><dt>${t("duration")}</dt><dd>${duration(journey.schedule.durationMinutes)}</dd></div>
    <div><dt>${t("transfers")}</dt><dd>${number(journey.schedule.transfers)}</dd></div>
    <div><dt>${t("price")}</dt><dd>${money(journey.fares?.selectedChf)}</dd></div>
  </dl>`;
}
function warning(result) {
  const { disruption, primaryJourney, recommendedJourney, alternatives, action } = result;
  const hasAlternative = alternatives.some(journey => journey.id !== primaryJourney?.id) ||
    (action === "REROUTE" && recommendedJourney && recommendedJourney.id !== primaryJourney?.id);
  return `<div class="notice warning result-status" role="alert">${icon("warning")}<div><strong>${t("disruption")}</strong>
    <p>${t(hasAlternative ? "disruptionAlternatives" : "disruptionAffected")}</p>
    ${disruption ? `<p class="caption">${esc(disruption.serviceLabel)} ${placeLabel(disruption.from)} → ${placeLabel(disruption.to)}
      ${disruption.cancelled ? " · " + t("cancelled") : delay(disruption.delayMinutes)}</p>
    <p class="caption">${[disruption.cause && token(disruption.cause), disruption.severity && token(disruption.severity), disruption.basis && token(disruption.basis), disruption.confidence && t("confidence") + " " + token(disruption.confidence)].filter(Boolean).join(" · ")}</p>` : ""}
  </div></div>`;
}
const delay = minutes => minutes ? ` · ${minutes > 0 ? "+" : ""}${number(minutes)} min` : "";

function featured(result) {
  const journey = result.recommendedJourney;
  if (!journey) return `<p class="notice">${t(result.alternatives.length || result.primaryJourney ? "noRecommended" : "noRoutes")}</p>`;
  const reasons = result.reasons.map(reasonText).filter(Boolean);
  return `<article class="feature"><p class="tag recommended-badge">${t("recommended")}</p>
    <div><p class="label">${t("arrival")}${journey.realtime?.expectedArrival ? " · " + t("expected") : ""}</p>
      <p class="arrival">${timeDisplay(arrival(journey))}</p></div>
    <p class="result-times">${resultTimes(journey)}</p>${facts(journey)}
    ${reasons.length ? `<div><strong>${t("why")}</strong><ul>${reasons.map(text => `<li>${esc(text)}</li>`).join("")}</ul></div>` : ""}
    <p class="caption">${metadata(journey)}</p>
    ${button("details", "details", "", `data-id="${esc(journey.id)}"`)}
  </article>`;
}

function bands(journeys, result) {
  return journeys.map(journey => {
    const { durationMinutes, transfers } = journey.schedule;
    const original = result.primaryJourney?.id === journey.id ? t("original") : "";
    return `<button type="button" class="band" data-action="details" data-id="${esc(journey.id)}">
      <span><strong class="band-title result-times">${resultTimes(journey)}</strong>
      <small>${duration(durationMinutes)} · ${number(transfers)} ${t("transfers").toLowerCase()}${journey.realtime?.expectedArrival ? " · " + t("expected") + " " + formatTime(arrival(journey)) : ""}</small>
      <small>${[original, metadata(journey)].filter(Boolean).join(" · ")}</small></span>
      <span class="band-end">${icon(journey.legs.every(leg => leg.mode === "WALK") ? "walking" : "station")}
        <small>${money(journey.fares?.selectedChf)}</small></span></button>`;
  }).join("");
}
function alternatives(result) {
  const journeys = otherJourneys(result, state.chronological);
  return `<div id="alternatives"><label class="checkbox"><input type="checkbox" name="chrono" ${checked(state.chronological)}> ${t("chrono")}</label>
    <div id="bands" class="stack">${bands(journeys.slice(0, state.shown), result)}</div>
    <div class="page-end" id="page-end">${pageEnd(journeys.length)}</div></div>`;
}
function pageEnd(total) {
  const previous = total > 7 && state.shown > 7 ? button("previousJourneys", "previousJourneys") : "";
  return previous + (total > state.shown ? button("more", "more") :
    `<p class="caption" role="status">${t(total ? "shown" : "noAlternatives")}</p>`);
}
function routeLoader(query) {
  return `<figure class="route-loader" aria-busy="true">
    <svg viewBox="0 0 540 340" aria-hidden="true" focusable="false"></svg>
    <figcaption><span><small>${t("from")}</small><strong>${esc(query.origin)}</strong></span>
      <span aria-hidden="true">→</span><span><small>${t("to")}</small><strong>${esc(query.destination)}</strong></span></figcaption>
  </figure>`;
}
const mapPoint = ([lon, lat]) => [Math.round((lon - 5.75) * 108), Math.round((47.9 - lat) * 153)];
let mapOutline;
async function drawLoadingRoute(svg, origin, destination) {
  let outline = "";
  try {
    const polygons = await swissBoundary();
    mapOutline ??= polygons.flatMap(polygon => polygon.map(ring => {
      const stride = Math.max(1, Math.floor(ring.length / 1000));
      return "M" + ring.filter((_, index) => index % stride === 0).map(mapPoint).join("L") + "Z";
    })).join("");
    outline = mapOutline;
  } catch { /* The endpoint connection can still be shown. */ }
  if (!svg.isConnected) return;
  const point = (place, fallback) => outline && hasCoordinates(place?.coordinates)
    ? mapPoint([place.coordinates.lon, place.coordinates.lat]) : fallback;
  const [x1, y1] = point(origin, [90, 135]);
  const [x2, y2] = point(destination, [410, 135]);
  svg.innerHTML = `<path class="swiss-outline" fill-rule="evenodd" d="${outline}"/>
    <path class="route-connection" d="M${x1},${y1}L${x2},${y2}"/>
    <circle class="route-stop" cx="${x1}" cy="${y1}" r="4"/><circle class="route-stop" cx="${x2}" cy="${y2}" r="4"/>
    <g class="route-train" style="--start-x:${x1}px;--start-y:${y1}px;--end-x:${x2}px;--end-y:${y2}px">
      <circle r="13"/><g transform="translate(-9 -9) scale(.75)">${paths.station}</g></g>`;
}
function results() {
  const result = state.result;
  if (state.busy) return routeLoader(state.activeQuery);
  if (!result) return `<section class="placeholder">${icon("station")}<h2>${t("routes")}</h2>
    <p>${t("planHint")}</p></section>`;
  const { status, action, request: query, deadlineSummary, meta } = result;
  const placeName = place => place.resolvedName ?? place.input;
  return `<section class="result" aria-label="${t("routes")}">
    <div class="result-heading"><h2 tabindex="-1" id="results-heading">${t("routes")}</h2>
    <p>${placeLabel(placeName(query.origin), state.activeQuery?.origin)} → ${placeLabel(placeName(query.destination), state.activeQuery?.destination)}</p>
    <p class="caption">${formatTime(query.departure, true)} · ${t(query.preference.toLowerCase())} · ${t(query.fareClass === 1 ? "first" : "second")}</p></div>
    ${action !== "NONE" ? `<p class="caption">${t(action === "REROUTE" ? "reroute" : "stay")}</p>` : ""}
    ${deadlineSummary ? `<p class="notice">${t(deadlineSummary.feasible ? "deadlineMet" : "deadlineMiss")}
      ${deadlineSummary.latestFeasibleDeparture ? " · " + t("leaveBy") + " " + formatTime(deadlineSummary.latestFeasibleDeparture, true) : ""}</p>` : ""}
    ${status === "DISRUPTION_DETECTED" ? warning(result) : status === "NO_DISRUPTION" ?
      `<div class="notice result-status" role="status">${icon("clear")}<strong>${t("clear")}</strong></div>` : ""}
    ${featured(result)}
    <h3>${t("otherRoutes")}</h3>${alternatives(result)}
    <p class="caption">${t("updated")} ${formatTime(meta.computedAt, true)} ${meta.modelVersion ? " · " + t("model") + " " + esc(meta.modelVersion) : ""}</p>
  </section>`;
}

function preferencesDialog() {
  return `<dialog class="settings-dialog" aria-labelledby="settings-title"><div class="dialog-body">
    <div class="heading-row"><h2 id="settings-title">${t("settings")}</h2>${button("close", "close")}</div>
    <label>${t("theme")}<select name="theme">${["system", "light", "dark"].map(key =>
      `<option value="${key}" ${theme === key ? "selected" : ""}>${t(key)}</option>`).join("")}</select></label>
    <label>${t("language")}<select name="language">${languageOptions()}</select></label>
    <p class="caption">${t("notifications")}</p>
  </div></dialog>`;
}
function languageOptions() {
  return Object.entries(languageNames).map(([code, name]) =>
    `<option value="${code}" ${code === language ? "selected" : ""}>${esc(name)}</option>`).join("");
}

function render(focus, keepSettings = false) {
  activeInfoTooltip = null;
  disposeFields.forEach(dispose => dispose());
  disposeFields = [];
  observer?.disconnect();
  preferences();
  document.title = "ALT · " + t("planner");
  app.innerHTML = `<header class="site-head"><div class="wrap head-inner">
    <a class="brand" href="./" aria-label="ALT"><span class="mark" aria-hidden="true">ALT</span><span class="brand-word">ALT</span></a>
    <div class="head-actions"><select name="language" aria-label="${t("language")}">${languageOptions()}</select>
      <button type="button" data-action="settings" class="icon-button" aria-label="${t("settings")}">${icon("settings")}</button></div>
  </div></header><main class="wrap" ${state.collapsed ? 'data-compact="true"' : ""}>${hero()}
    <div class="layout">${planner()}${results()}</div></main>
  <footer class="wrap foot"><span>${t("swiss")}</span><span>©swisstopo</span></footer>
  ${preferencesDialog()}`;
  for (const container of app.querySelectorAll("[data-place]")) connectPlace(container);
  const loadingMap = app.querySelector(".route-loader svg");
  if (loadingMap) drawLoadingRoute(loadingMap, state.origin, state.destination);
  updateClock();
  watchBatches();
  if (keepSettings) app.querySelector(".settings-dialog").showModal();
  if (focus) app.querySelector(focus)?.focus({preventScroll: true});
}

function connectPlace(container) {
  const id = container.dataset.place;
  const input = container.querySelector("input");
  const list = container.querySelector(".suggestions");
  const status = container.querySelector('[role="status"]');
  const locate = container.querySelector(".locate");
  let controller, timer, places = [];
  const cancel = () => { clearTimeout(timer); controller?.abort(); locate.disabled = false; };
  disposeFields.push(cancel);
  input.addEventListener("input", () => {
    cancel();
    state.drafts[id] = input.value;
    state[id] = null;
    state.field = "";
    input.setAttribute("aria-invalid", "false");
    list.innerHTML = "";
    places = [];
    const query = input.value.trim();
    status.textContent = "";
    if (query.length < 2) return;
    const next = new AbortController();
    controller = next;
    status.textContent = t("searching");
    timer = setTimeout(async () => {
      try {
        const result = await request("locations", { query, limit: 20 }, next.signal);
        const swiss = await swissLocations(result.locations);
        if (next.signal.aborted) return;
        places = swiss;
        list.innerHTML = placeButtons(places, state[id === "origin" ? "destination" : "origin"]?.coordinates);
        status.textContent = t(places.length ? "selectPlace" : result.locations.length ? "noSwissPlaces" : "noPlaces");
      } catch (error) {
        if (!next.signal.aborted) status.textContent = t(error.message);
      }
    }, 350);
  });
  locate.addEventListener("click", async () => {
    cancel();
    const next = new AbortController();
    controller = next;
    locate.disabled = true;
    list.replaceChildren();
    status.textContent = t("finding");
    try {
      const point = await currentPosition();
      if (next.signal.aborted) return;
      const data = await request("locations", {near:[point.lat, point.lon].join(","), radiusKm:5, limit:20}, next.signal);
      const swiss = await swissLocations(data.locations);
      if (next.signal.aborted) return;
      places = swiss;
      list.innerHTML = placeButtons(places, point.accuracy <= 250 ? point : null);
      status.textContent = t(places.length ? "selectPlace" : "noNearby");
    } catch (error) {
      if (!next.signal.aborted) status.textContent = t(error.message);
    } finally {
      if (!next.signal.aborted) locate.disabled = false;
    }
  });
  list.addEventListener("click", event => {
    const item = event.target.closest("[data-choice]");
    if (!item) return;
    const place = places[Number(item.dataset.choice)];
    input.value = place.name;
    status.textContent = "";
    list.innerHTML = "";
    state[id] = place;
    delete state.drafts[id];
    input.setAttribute("aria-invalid", "false");
    announce("placeReady");
    input.focus();
  });
  container.addEventListener("keydown", event => {
    const buttons = [...list.querySelectorAll("button")];
    if (event.key === "Escape" && buttons.length) {
      list.innerHTML = "";
      input.focus();
      event.preventDefault();
    }
    if (buttons.length && ["ArrowDown", "ArrowUp"].includes(event.key)) {
      const index = buttons.indexOf(document.activeElement);
      (buttons[index + (event.key === "ArrowDown" ? 1 : -1)] ?? input).focus();
      event.preventDefault();
    }
  });
}

function currentPosition() {
  if (!navigator.geolocation) return Promise.reject(new Error("geoUnavailable"));
  return new Promise((resolve, reject) => navigator.geolocation.getCurrentPosition(
    ({coords}) => resolve({lat:coords.latitude, lon:coords.longitude, accuracy:coords.accuracy}),
    error => reject(new Error(({1:"denied", 2:"geoUnavailable", 3:"geoTimeout"})[error.code] ?? "geoUnavailable")),
    {timeout:12000, maximumAge:0},
  ));
}

function watchBatches() {
  observer?.disconnect();
  const more = app.querySelector('[data-action="more"]');
  if (!more || !("IntersectionObserver" in window)) return;
  observer = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) appendBatch();
  });
  observer.observe(more);
}
function appendBatch() {
  const end = app.querySelector("#page-end");
  if (!end || !state.result) return;
  const journeys = otherJourneys(state.result, state.chronological);
  if (state.shown >= journeys.length) return;
  const focused = end.contains(document.activeElement);
  const start = state.shown;
  state.shown += 7;
  const stack = app.querySelector("#bands");
  stack.insertAdjacentHTML("beforeend", bands(journeys.slice(start, state.shown), state.result));
  end.innerHTML = pageEnd(journeys.length);
  if (focused) stack.children[start]?.focus();
  watchBatches();
}

function impactPanels(result) {
  if (!result.disruption) return "";
  const disruption = result.disruption;
  const consequence = disruption.cancelled ? t("cancelled") : delay(disruption.delayMinutes).slice(3) || t("unavailable");
  return `<div class="panel-grid">
    <div><strong>${t("changed")}</strong><p>${esc(disruption.serviceLabel)} ${placeLabel(disruption.from)} → ${placeLabel(disruption.to)}</p></div>
    <div><strong>${t("means")}</strong><p>${consequence}</p></div>
    <div><strong>${t("nextStep")}</strong><p>${result.action === "NONE" ? t("unavailable") : t(result.action === "REROUTE" ? "reroute" : "stay")}</p>
      ${button("showOptions", "otherRoutes")}</div>
  </div>`;
}
function openJourney(id) {
  const { result } = state;
  const journey = [result.recommendedJourney, result.primaryJourney, ...result.alternatives].find(item => item?.id === id);
  if (!journey) return;
  const timeline = journey.legs.map((leg, index) => {
    const disruption = result.disruption;
    const impacted = disruption && disruption.from === leg.origin && disruption.to === leg.destination &&
      (!disruption.serviceLabel || disruption.serviceLabel === leg.serviceLabel) &&
      Date.parse(disruption.scheduledDeparture) === Date.parse(leg.departure);
    const stop = (place, time, platform, expected, delayMinutes, fallback) =>
      `<span>${formatTime(time, true)} · ${placeLabel(place, fallback)}${platform ? " · " + t("platform") + " " + esc(platform) : ""}
        ${expected ? " · " + t("expected") + " " + formatTime(expected) : ""}${delay(delayMinutes)}</span>`;
    const occupancy = leg.occupancy ? [["first",leg.occupancy.firstClass],["second",leg.occupancy.secondClass]]
      .filter(([, value]) => value).map(([key,value]) => `${t(key)} ${token(value)}`).join(" · ") : "";
    const walkingMinutes = leg.mode === "WALK" ? Math.round((Date.parse(leg.arrival) - Date.parse(leg.departure)) / 60000) : null;
    return `<li class="${impacted ? "impacted" : ""}"><strong>${esc(leg.serviceLabel ?? t(leg.mode === "WALK" ? "walking" : "train"))}
      ${walkingMinutes > 0 ? " · " + number(walkingMinutes) + " min" : ""}
      ${impacted ? " · " + t("disruption") : ""}</strong>
      ${stop(leg.origin, leg.departure, leg.departurePlatform, leg.expectedDeparture, leg.departureDelayMinutes, index === 0 ? state.activeQuery?.origin : null)}
      ${stop(leg.destination, leg.arrival, leg.arrivalPlatform, leg.expectedArrival, leg.arrivalDelayMinutes, index === journey.legs.length - 1 ? state.activeQuery?.destination : null)}
      ${occupancy ? `<small>${t("occupancy")} · ${occupancy}</small>` : ""}</li>`;
  }).join("");
  const { risk, fares, realtime } = journey;
  const catalog = fares?.catalogChf;
  const dialog = document.createElement("dialog");
  dialog.className = "details-dialog";
  dialog.setAttribute("aria-labelledby", "journey-detail-title");
  dialog.innerHTML = `<div class="dialog-body"><div class="heading-row"><h2 id="journey-detail-title">${t("details")}</h2>${button("close","close")}</div>
    <p>${travelTimes(journey)}${realtime?.expectedArrival ? " · " + t("expected") + " " + formatTime(realtime.expectedArrival) : ""}${delay(realtime?.arrivalDelayMinutes)}</p>
    ${facts(journey)}<p class="caption">${metadata(journey)}</p>
    <ol class="timeline">${timeline}</ol>${impactPanels(result)}
    <div class="panel-grid"><div><strong>${t("price")}</strong>
      <p>${token(fares?.status)} ${esc(fares?.product)} ${esc(fares?.source)}</p>
      ${catalog ? [["second","secondClassFull"],["first","firstClassFull"]].map(([classKey, key]) =>
        `<p>${t(classKey)} · ${money(catalog[key])}</p>`).join("") : ""}
    </div><div><strong>${t("risk")}</strong>
      <p>${historicalRate(risk) || t("historyUnavailable")}</p>
      ${risk?.typicalIncidentDurationMinutes != null ? `<p>${t("typical")} · ${number(risk.typicalIncidentDurationMinutes)} min</p>` : ""}
      ${risk?.confidence ? `<p>${t("confidence")} ${token(risk.confidence)}</p>` : ""}
    </div></div>
    <p class="caption">${t("updated")} ${formatTime(result.meta.computedAt, true)} ${result.meta.modelVersion ? " · " + t("model") + " " + esc(result.meta.modelVersion) : ""}</p>
    ${journey.feasible === true ? `<p>${t("deadlineMet")}</p>` : ""}
  </div>`;
  app.append(dialog);
  dialog.addEventListener("close", () => dialog.remove(), {once:true});
  dialog.showModal();
}

function resetResults() {
  searchController?.abort();
  Object.assign(state, {result:null, busy:false, error:"", field:"", chronological:false, shown:7});
}
function invalid(message, field = "") {
  if (!["origin", "destination", "departure", "arrivalDeadline", "fareClass"].includes(field)) field = "";
  state.error = message;
  state.field = field;
  state.collapsed = false;
  render();
  app.querySelector(field ? `#${field},[name="${field}"]` : '[type="submit"]')?.focus();
  announce(message);
}
let retryAfter = 0;
async function search() {
  if (state.busy) return;
  if (!state.origin) return invalid("selectPlace", "origin");
  if (!state.destination) return invalid("selectPlace", "destination");
  if (locationValue(state.origin) === locationValue(state.destination)) return invalid("same", "destination");
  if (Date.now() < retryAfter) return invalid("busy");
  let departure, deadline;
  try { departure = state.scheduled ? swissInstant(state.departure) : null; } catch { return invalid("dates", "departure"); }
  if (state.scheduled && !departure) return invalid("dates", "departure");
  try { deadline = swissInstant(state.arrivalDeadline); } catch { return invalid("dates", "arrivalDeadline"); }
  if (deadline && Date.parse(deadline) <= (departure ? Date.parse(departure) : Date.now())) return invalid("dates", "arrivalDeadline");
  const parameters = {
    origin:locationValue(state.origin), destination:locationValue(state.destination), preference:state.preference,
    fareClass:state.fareClass, limit:10,
  };
  if (departure) parameters.departure = departure;
  if (deadline) parameters.arrivalDeadline = deadline;
  resetResults();
  state.activeQuery = {origin:state.origin.name, destination:state.destination.name,
    preference:state.preference, departure:departure ?? new Date().toISOString(), now:!state.scheduled};
  state.collapsed = mobile.matches;
  state.busy = true;
  const controller = new AbortController();
  searchController = controller;
  render(state.collapsed ? ".mobile-summary" : undefined);
  if (state.collapsed) window.scrollTo(0, 0);
  announce("searching");
  try {
    const result = await request("journeys", parameters, controller.signal);
    if (controller.signal.aborted) return;
    state.result = result;
    state.activeQuery.departure = result.request.departure;
    state.busy = false;
    render(mobile.matches && state.collapsed ? ".mobile-summary" : "#results-heading");
    announce("resultReady");
  } catch (error) {
    if (controller.signal.aborted) return;
    state.busy = false;
    if (error.message === "busy") retryAfter = Date.now() + 15000;
    invalid(error.message, error.field);
  }
}

app.addEventListener("submit", event => {
  if (event.target.id !== "planner") return;
  event.preventDefault();
  search();
});
app.addEventListener("input", ({target}) => {
  if (state.scheduled && (target.id === "departure" || target.id === "departure-time")) {
    state.departure = app.querySelector("#departure").value + "T" + app.querySelector("#departure-time").value;
    return;
  }
  const name = target.dataset.date;
  if (!name) return;
  const [date, time] = [...app.querySelectorAll(`[data-date="${name}"]`)].map(input => input.value);
  state[name] = date || time ? date + "T" + time : "";
});
app.addEventListener("change", ({ target }) => {
  if (target.name === "language") {
    const inSettings = Boolean(target.closest("dialog"));
    language = target.value;
    render(inSettings ? 'dialog [name="language"]' : 'header [name="language"]', inSettings);
  } else if (target.name === "theme") {
    theme = target.value;
    preferences();
  } else if (target.id === "departure" && !state.scheduled) {
    if (target.value === swissDateTime().split("T")[0]) return;
    state.departure = target.value + "T" + swissDateTime().split("T")[1];
    state.scheduled = true;
    render(target.value ? "#departure-time" : "#departure");
  } else if (target.name === "departureMode") {
    state.scheduled = target.value === "scheduled";
    if (state.scheduled && !state.departure) state.departure = swissDateTime();
    render(state.scheduled ? "#departure-time" : '[name="departureMode"]');
  } else if (target.name === "fareClass") {
    state.fareClass = target.value;
  } else if (target.name === "chrono") {
    state.chronological = target.checked;
    state.shown = 7;
    app.querySelector("#alternatives").outerHTML = alternatives(state.result);
    app.querySelector('[name="chrono"]').focus({preventScroll:true});
    watchBatches();
  }
});
app.addEventListener("click", event => {
  const control = event.target.closest("[data-action]");
  if (!control) return;
  const { action, value, id } = control.dataset;
  switch (action) {
    case "settings": app.querySelector(".settings-dialog").showModal(); break;
    case "close": control.closest("dialog").close(); break;
    case "editSearch":
      state.collapsed = !state.collapsed;
      app.querySelector(".search-panel").classList.toggle("collapsed", state.collapsed);
      app.querySelector("main").toggleAttribute("data-compact", state.collapsed);
      app.querySelector("#planner").inert = mobile.matches && state.collapsed;
      control.setAttribute("aria-expanded", !state.collapsed);
      control.querySelector(".summary-edit").textContent = t(state.collapsed ? "editSearch" : "hideSearch");
      break;
    case "mode":
      if (state.preference === value) break;
      state.preference = value;
      app.querySelectorAll('[data-action="mode"]').forEach(item => {
        item.setAttribute("aria-pressed", item === control);
        item.parentElement.dataset.selected = String(item === control);
      });
      if (state.result) search();
      break;
    case "info":
      setInfo(activeInfoTooltip?.mode === value && activeInfoTooltip.pinned ? null : value, true);
      break;
    case "showOptions":
      control.closest("dialog").close();
      app.querySelector("#alternatives").scrollIntoView({block:"nearest"});
      app.querySelector('[name="chrono"]').focus({preventScroll:true});
      break;
    case "more": appendBatch(); break;
    case "previousJourneys": {
      const rows = app.querySelector("#bands").children;
      const previous = rows[Math.max(0, Math.floor((rows.length - 1) / 7) * 7 - 7)];
      previous?.focus({preventScroll:true});
      previous?.scrollIntoView({block:"start"});
      break;
    }
    case "details": openJourney(id); break;
  }
});
function setInfo(mode, pinned = false) {
  activeInfoTooltip = mode ? {mode, pinned} : null;
  const help = app.querySelector("#mode-help");
  help.hidden = !mode;
  help.textContent = mode ? t(mode + "Help") : "";
  for (const icon of app.querySelectorAll(".info")) {
    const active = icon.dataset.value === mode;
    icon.setAttribute("aria-expanded", String(active));
    if (active) icon.setAttribute("aria-describedby", help.id);
    else icon.removeAttribute("aria-describedby");
  }
}
app.addEventListener("focusin", event => {
  if (event.target.matches(".info") && !activeInfoTooltip?.pinned) setInfo(event.target.dataset.value);
});
app.addEventListener("focusout", event => {
  if (event.target.matches(".info") && activeInfoTooltip?.mode === event.target.dataset.value &&
    !activeInfoTooltip.pinned) setInfo(null);
});
app.addEventListener("pointerover", event => {
  const control = event.target.closest(".info");
  if (control && event.pointerType === "mouse" && !control.contains(event.relatedTarget) &&
    !activeInfoTooltip?.pinned) setInfo(control.dataset.value);
});
app.addEventListener("pointerout", event => {
  const control = event.target.closest(".info");
  if (control && !control.contains(event.relatedTarget) && document.activeElement !== control &&
    activeInfoTooltip?.mode === control.dataset.value && !activeInfoTooltip.pinned) setInfo(null);
});
document.addEventListener("keydown", event => {
  if (event.key === "Escape" && activeInfoTooltip) setInfo(null);
});
document.addEventListener("click", event => {
  if (activeInfoTooltip && !event.target.closest(".info, #mode-help")) setInfo(null);
});
mobile.addEventListener("change", () => {
  app.querySelector("#planner").inert = mobile.matches && state.collapsed;
});
render();
setInterval(updateClock, 250);
