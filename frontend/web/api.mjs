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

export async function request(endpoint, parameters, signal) {
  if (!navigator.onLine) throw new Error("offline");
  const timeout = AbortSignal.timeout(25000);
  const combined = AbortSignal.any([signal, timeout]);
  try {
    const response = await fetch(`/api/${endpoint}?${new URLSearchParams(parameters)}`, { signal: combined });
    const data = await response.json();
    if (!response.ok) {
      const error = new Error(errorKeys[data.error?.code] ?? "service");
      error.field = data.error?.field;
      throw error;
    }
    return data;
  } catch (error) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    if (timeout.aborted) throw new Error("timeout");
    if (Object.values(errorKeys).includes(error.message)) throw error;
    throw new Error(navigator.onLine ? "service" : "offline");
  }
}
