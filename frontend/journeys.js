const zone = "Europe/Zurich";
const clockParts = new Intl.DateTimeFormat("fr-CH", {
  timeZone: zone, hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
});
const dateTime = new Intl.DateTimeFormat("sv-SE", {
  timeZone: zone, dateStyle: "short", timeStyle: "short", hourCycle: "h23"
});
const displayTime = new Intl.DateTimeFormat("fr-CH", {
  timeZone: zone, hour: "2-digit", minute: "2-digit", hourCycle: "h23"
});
const displayDay = new Intl.DateTimeFormat("fr-CH", {
  timeZone: zone, day: "numeric", month: "short"
});

export function swissClock(date) {
  return clockParts.formatToParts(date).filter(part => part.type !== "literal").map(part => Number(part.value));
}

export function swissDeparture(value) {
  if (!value) return undefined;
  const matches = ["+01:00", "+02:00"]
    .map(offset => new Date(value + offset))
    .filter(date => Number.isFinite(+date) && dateTime.format(date).replace(" ", "T") === value);
  if (matches.length !== 1) throw new Error("Cette heure suisse est inexistante ou ambiguë. Choisissez une autre heure.");
  return matches[0].toISOString();
}

export const time = date => displayTime.format(new Date(date));
export const day = date => displayDay.format(new Date(date));

const demoServices = [
  { destination: "Genève", minutes: 8, platform: "3", duration: 39, service: "IR 90", delay: 0 },
  { destination: "Bern", minutes: 17, platform: "7", duration: 68, service: "IC 1", delay: 5 },
  { destination: "Zürich HB", minutes: 31, platform: "1", duration: 132, service: "IC 5", delay: 0 }
];

export function demoBoard(now) {
  return demoServices.map(service => ({ ...service, departure: new Date(+now + service.minutes * 60_000) }));
}

export function demoSearch(origin, destination, departure, signal) {
  const normalize = text => text.normalize("NFD").replace(/\p{Diacritic}/gu, "").toLowerCase();
  const fromLausanne = normalize(origin) === "lausanne";
  const service = demoServices.find(item => normalize(item.destination) === normalize(fromLausanne ? destination : origin));
  const supported = service && (fromLausanne || normalize(destination) === "lausanne");
  const anchor = departure ? Date.parse(departure) : Date.now();
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", cancel);
      resolve(supported ? [service.minutes, service.minutes + 28].map((offset, index) => {
        const start = new Date(anchor + offset * 60_000).toISOString();
        const durationMinutes = service.duration + index * 8;
        const end = new Date(Date.parse(start) + durationMinutes * 60_000).toISOString();
        return {
          id: "demo-" + index, departure: start, arrival: end, durationMinutes, transfers: 0,
          dataStatus: "MOCK",
          legs: [{
            origin, destination, departure: start, arrival: end, serviceLabel: service.service,
            departurePlatform: fromLausanne ? service.platform : null
          }]
        };
      }) : []);
    }, 320);
    function cancel() {
      clearTimeout(timer);
      reject(signal.reason);
    }
    signal.addEventListener("abort", cancel, { once: true });
  });
}

export async function apiSearch(base, origin, destination, departure, signal) {
  const url = new URL("api/journeys", base.replace(/\/$/, "") + "/");
  url.search = new URLSearchParams({ origin, destination });
  if (departure) url.searchParams.set("departure", departure);
  const response = await fetch(url, { signal, headers: { Accept: "application/json" } });
  if (!response.ok) {
    if (response.status === 400) throw new Error("Vérifiez les gares et l’heure de départ.");
    throw new Error("Le service est momentanément indisponible. Veuillez réessayer.");
  }
  const result = await response.json();
  if (!Array.isArray(result.journeys)) throw new Error("La réponse ne contient pas d’itinéraires lisibles.");
  return result.journeys;
}
