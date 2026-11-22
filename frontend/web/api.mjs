const swissLocal = new Intl.DateTimeFormat("sv-SE", {
  timeZone: "Europe/Zurich", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
});

export function swissDateTime(date = new Date()) {
  return swissLocal.format(date).replace(" ", "T");
}

export function swissInstant(value) {
  if (!value) return null;
  const utc = Date.parse(value + "Z");
  const matches = [1, 2].map(hours => new Date(utc - hours * 3600000))
    .filter(date => !Number.isNaN(+date) && swissDateTime(date) === value);
  if (matches.length !== 1) throw new Error("dates");
  return matches[0].toISOString();
}

export function locationValue(place) {
  if (place.kind === "ADDRESS") {
    return place.coordinates ? [place.coordinates.lat, place.coordinates.lon].join(",") : place.name;
  }
  return place.id;
}

export function hasCoordinates(point) {
  return Number.isFinite(point?.lat) && Math.abs(point.lat) <= 90 &&
    Number.isFinite(point?.lon) && Math.abs(point.lon) <= 180;
}

function inRing({lat, lon}, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [x, y] = ring[i], [previousX, previousY] = ring[j];
    if ((y > lat) !== (previousY > lat) && lon < (previousX - x) * (lat - y) / (previousY - y) + x) {
      inside = !inside;
    }
  }
  return inside;
}

export function inSwitzerland(point, polygons) {
  return hasCoordinates(point) && polygons.some(([outer, ...holes]) =>
    inRing(point, outer) && holes.every(hole => !inRing(point, hole)));
}

let boundary;
export function swissBoundary() {
  boundary ??= fetch("swiss-boundary.geojson").then(async response => {
    if (!response.ok) throw new Error("countryUnavailable");
    return (await response.json()).geometry.coordinates;
  }).catch(() => { boundary = undefined; throw new Error("countryUnavailable"); });
  return boundary;
}

export async function swissLocations(places) {
  const candidates = places.filter(place => ["STOP", "ADDRESS"].includes(place.kind));
  if (!candidates.some(place => hasCoordinates(place.coordinates))) return [];
  const polygons = await swissBoundary();
  return candidates.filter(place => inSwitzerland(place.coordinates, polygons));
}

export function distanceKm(a, b) {
  if (!hasCoordinates(a) || !hasCoordinates(b)) return null;
  const rad = degrees => degrees * Math.PI / 180;
  const chord = Math.sin(rad(b.lat - a.lat) / 2) ** 2 +
    Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(rad(b.lon - a.lon) / 2) ** 2;
  return 6371 * 2 * Math.asin(Math.min(1, Math.sqrt(chord)));
}

const normalLabel = value => value.normalize("NFC").toLowerCase().replace(/[,\s-]+/g, " ").trim();
const stationAlias = /,\s*(?:gare|bahnhof|stazione)(?=\s*(?:\(|$))/i;

export function uniqueLocations(places) {
  const unique = [];
  for (const place of places) {
    const key = suggestionKey(place);
    const match = unique.findIndex(other => {
      if (place.kind !== other.kind) return false;
      if (place.id === other.id) return true;
      const distance = distanceKm(place.coordinates, other.coordinates);
      return key === suggestionKey(other) && distance != null && distance <= 0.35;
    });
    if (match === -1) unique.push(place);
    else if (place.kind === "STOP" && stationAlias.test(unique[match].name) && !stationAlias.test(place.name)) {
      unique[match] = place;
    }
  }
  return unique;
}

function suggestionKey({kind, name}) {
  if (kind === "STOP") name = name.replace(stationAlias, "");
  const [, primary, locality] = /^(.*?)\s*\(([^()]*)\)$/.exec(name) ?? [];
  if (primary && normalLabel(primary) === normalLabel(locality)) name = primary;
  return normalLabel(name);
}

let stationCatalog;
function railStations() {
  stationCatalog ??= fetch("rail-stations.json").then(async response => {
    if (!response.ok) throw new Error("service");
    const { stations } = await response.json();
    return stations.map(([id, name, lat, lon]) => ({id, name, kind:"STOP", coordinates:{lat, lon}}));
  }).catch(() => { stationCatalog = undefined; throw new Error("service"); });
  return stationCatalog;
}

export async function nearbyRailStations(point) {
  const ranked = (await railStations()).map(place => ({place, distance:distanceKm(point, place.coordinates)}))
    .filter(({distance}) => distance != null && distance <= 20)
    .sort((a, b) => a.distance - b.distance)
    .map(({place}) => place);
  return uniqueLocations(ranked).slice(0, 20);
}

const searchableLabel = value => normalLabel(value.normalize("NFD").replace(/\p{M}/gu, ""));
export async function railSuggestions(query) {
  const prefix = searchableLabel(query);
  if (prefix.length < 2) return [];
  const matches = (await railStations()).map(place => ({place, label:searchableLabel(place.name)}))
    .filter(({label}) => label.startsWith(prefix))
    .sort((a, b) => Number(b.label === prefix) - Number(a.label === prefix));
  return uniqueLocations(matches.map(({place}) => place)).slice(0, 20);
}

export function otherJourneys(result, chronological) {
  const skip = result.recommendedJourney?.id;
  const alternatives = new Map();
  for (const journey of result.alternatives) {
    if (journey.id !== skip && !alternatives.has(journey.id)) alternatives.set(journey.id, journey);
  }
  if (result.primaryJourney && result.primaryJourney.id !== skip && !alternatives.has(result.primaryJourney.id)) {
    alternatives.set(result.primaryJourney.id, result.primaryJourney);
  }
  const journeys = [...alternatives.values()];
  return chronological ? journeys.sort((a, b) => Date.parse(a.schedule.departure) - Date.parse(b.schedule.departure)) : journeys;
}

const errorKeys = {
  LOCATION_NOT_FOUND: "locationError", INVALID_REQUEST: "invalid", UPSTREAM_TIMEOUT: "timeout",
  UPSTREAM_RATE_LIMITED: "busy", UPSTREAM_ERROR: "service",
};

const locationCache = new Map();
let providerRetryAt = 0;
const maxAutomaticWait = 10000;

export function retryDelay(header, now = Date.now()) {
  if (header && /^\d+$/.test(header.trim())) {
    const seconds = Number(header);
    if (Number.isSafeInteger(seconds)) return Math.max(1000, seconds * 1000);
  }
  const date = header && Date.parse(header);
  return date ? Math.max(1000, date - now) : 5000;
}

function waitForProvider(ms, signal) {
  return new Promise((resolve, reject) => {
    function abort() {
      clearTimeout(timer);
      reject(signal.reason);
    }
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolve();
    }, ms);
    signal.addEventListener("abort", abort, {once:true});
    if (signal.aborted) abort();
  });
}

async function requestOnce(url, signal) {
  const response = await fetch(url, {signal, headers:{Accept:"application/json"}});
  let data;
  try { data = await response.json(); } catch { /* Gateways may return a non-JSON error. */ }
  if (response.ok && data) return data;
  const code = data?.error?.code;
  const busy = code === "UPSTREAM_RATE_LIMITED" || response.status === 429 || (response.status === 503 && !code);
  const error = new Error(busy ? "busy" : errorKeys[code] ?? "service");
  error.field = data?.error?.field;
  if (busy) providerRetryAt = Math.max(providerRetryAt, Date.now() + retryDelay(response.headers.get("retry-after")));
  throw error;
}

export async function request(endpoint, parameters, signal, onRetry) {
  const url = `/api/${endpoint}?${new URLSearchParams(parameters)}`;
  const cacheKey = endpoint === "locations" && parameters.query ?
    new URLSearchParams({...parameters, query:parameters.query.toLowerCase()}).toString() : null;
  const cached = locationCache.get(cacheKey);
  if (cached && cached.expires > Date.now() && !signal.aborted) return cached.data;
  if (!navigator.onLine) throw new Error("offline");
  const timeout = AbortSignal.timeout(25000);
  const combined = AbortSignal.any([signal, timeout]);
  try {
    async function respectCooldown() {
      const remaining = providerRetryAt - Date.now();
      if (remaining <= 0) return;
      if (!onRetry || remaining > maxAutomaticWait) throw new Error("busy");
      onRetry();
      await waitForProvider(remaining, combined);
    }
    await respectCooldown();
    let data;
    try {
      data = await requestOnce(url, combined);
    } catch (error) {
      if (!onRetry || error.message !== "busy") throw error;
      await respectCooldown();
      data = await requestOnce(url, combined);
    }
    if (cacheKey && data.locations?.length) {
      if (locationCache.size >= 50) locationCache.delete(locationCache.keys().next().value);
      locationCache.set(cacheKey, {data, expires:Date.now() + 5 * 60 * 1000});
    }
    return data;
  } catch (error) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (timeout.aborted) throw new Error("timeout");
    if (Object.values(errorKeys).includes(error.message)) throw error;
    throw new Error(navigator.onLine ? "service" : "offline");
  }
}
