# Fare investigation — exact per-trip / per-leg / per-zone cost (Halbtax + GA)

Branch: `feat/fare-costs` (investigation doc). All evidence below was probed
live on 2026-11-22 (transport.opendata.ch + api.opentransportdata.swiss/ojp20).

## Question

Can we fetch each travel possibility's EXACT cost — per leg, per zone — to
recommend the cheapest route, considering only Halbtax (Half-Fare) and GA
("fully free abo")?

## Verdict

YES — via the official OJP Fare service (NOVA-backed) on the same ojp20
endpoint our OJP key already works against. NOT via the fare source we use
today. One integration gap remains: the exact Swiss request shape for a
non-empty OJPFareDelivery (our probes return HTTP 200 with an empty
delivery; most likely missing intermediate stops — see evidence).

## Evidence

1. Current stack (transport.opendata.ch /v1/connections + /v1/fare,
   what NovaFareProvider uses): prices the ORIGIN-DESTINATION pair only.
   - Genève → Zürich HB: every connection prices 92.00 CHF 2nd full /
     46.00 half, regardless of the connection returned (probed conn[0] and
     conn[1] of the same search). Same for Lausanne → Zermatt (85.00).
   - Response contains ONLY {currency, fares{2nd/1st: full, halfFare},
     product}. No per-leg/pass breakdown, no zones, no reduction profiles
     beyond halfFare, no GA concept.
   - Consequence: "cheapest route" ranking is degenerate today — all
     alternatives of one search share one price. Halbtax IS already served
     (halfFare = Halbtax price, live as discount=HALF_FARE).
2. Official OJP Fare (OJPFareRequest, TripFareRequest variant):
   - Our EXISTING OJP token is accepted on ojp20 for OJPFareRequest:
     HTTP 200, no auth error (probed 5 request variants).
   - All variants currently return an EMPTY delivery (200, no FareResult,
     no Problem). Root-cause hypothesis, per SBB's own OJPFare/TOMP research
     paper: "In Switzerland, the pricing service (NOVA) needs ALL
     intermediate stops as well" — and our OJP trip responses carry ZERO
     LegIntermediate elements (probed: count 0 on the Lausanne→Genève trip).
   - What the OJPFareDelivery gives once shaped correctly (per the OJP
     standard / the SBB paper): FareResults per leg RANGE
     (FromLegIdRef/ToLegIdRef = per-pass pricing), FareProduct with price,
     currency, TravelClass, ValidityTariffZones (zone-based products +
     PassedZones), RequiredCard (travel cards needed), Traveller profiles
     (Age, PassengerCategory) and EntitlementProduct — the fare products the
     passenger ALREADY HOLDS (GA / Halbtax) → exact GA and Halbtax pricing.

## What this unlocks

- Exact price PER CONNECTION (incl. supersaver where offered) → CHEAPEST
  preference becomes real ranking, not a tie.
- Per-leg / per-pass products + zone validity → "this leg is a ZVV 3-zone
  ticket, this one a national Streckenbillett" in the UI.
- Halbtax and GA priced by NOVA itself (EntitlementProduct), including the
  GA edge cases (private / mountain railways where GA is not or partially
  valid — NOVA knows; today we would have to guess).
- NO new token needed — the existing OJP key already gets 200 on fare
  requests (unlike the API manager's separate "OJP Fare" product page
  suggests, the endpoint accepts the request type; if deeper access ever
  403s, the human step is requesting product tedp_ojpfare-1 in the
  opentransportdata API manager — one click for the lead).

## Token + endpoint round (2026-11-22, after the lead supplied the dedicated fare token)

- Token stored at ~/lauzhack-ojpfare.env (600). The fare product has its OWN
  endpoint: **https://api.opentransportdata.swiss/ojpfare** (found on the
  API-manager product page tedp_ojpfare-1). The ojp20 endpoint 403s the fare
  token ("Access to this API has been disallowed") and answers fare requests
  with the JOURNEY token as empty 200s — product tokens are endpoint-scoped.
- Cookbook found (opentransportdata.swiss/en/cookbook/open-journey-planner-ojp-landing-page/ojp-fare):
  canonical request is v1.0-flavor (siri default ns, ojp: prefixed), trips must
  include intermediate stops (OJPTripRequest param `IncludeIntermediateStops`
  — works on ojp20: 14 LegIntermediate elements flow for Lausanne→Genève),
  and **EntitlementProduct HTA = Halbtax is the ONLY entitlement the beta
  supports** — GA is NOT supported by the beta service; GA needs the labeled
  static-coverage fallback in our product.
- Probe matrix against /ojpfare with the fare token (6 rounds): auth PASSES
  (500, not 403) but EVERY well-formed variant returns a naked
  `500 Internal Server Error` — v1.0 siri-default, v2.0 vdv-default,
  RequestorRef alt/OJP2NOVA, plain Trip vs Trip-wrapped-in-TripResult,
  text/xml vs application/xml, with/without Params. The 2023 cookbook example
  may be stale, or the beta service is currently broken. The status page
  shows no direct mention.
- REMAINING UNKNOWN (one human step or one support mail): the CURRENT
  canonical request example / Postman collection behind the API-manager
  product page ("Read more" on tedp_ojpfare-1, needs the lead's login).
  With one working request in hand, the backend build is mechanical.

## Plan (Phase F) — exact fares (updated)

1. Get one working OJPFareDelivery (was "crack the shape"):
   - HUMAN STEP: lead opens api-manager product tedp_ojpfare-1 → "Read more"
     → copies the CURRENT example request (or Postman collection) to Teddy.
     Fallback: mail opendata@sbb.ch with our failing request + trace.
   - Exit criterion: a Lausanne→Genève trip returns FareProducts with CHF
     prices, and a Genève→Zürich search returns DIFFERENT prices for
     different connections.
2. Backend: OjpFareProvider.fareFor(trip) — parse FareResults into
   per-pass products {price, class, product, zones[], requiredCard}; cache
   per trip id with a short TTL (fare calls per connection = more upstream
   calls; cap and budget them like the existing FareCache).
3. Wire (additive): journey.fares gains exactPriceChf (per connection) +
   fares.passes[] {fromLeg, toLeg, product, price, zones}; new discount
   profile value GA (discount=NONE|HALF_FARE|GA today becomes
   discountProfile with GA added); legs priced 0 where NOVA says the
   entitlement covers them; source labels NOVA_EXACT vs NOVA_OD (current).
4. CHEAPEST ranking switches to exactPriceChf when present (falls back to
   today's shared O-D price otherwise — additive, honest labels).
5. GA fallback if EntitlementProduct support turns out limited in the Swiss
   profile: static GA-coverage model per operator (SBB + regional = covered,
   private/mountain = priced), labeled GA_COVERAGE_STATIC — transparent,
   never silent.

Effort after step 1: backend + tests + smoke ≈ 3-5h.
Risk: step 1 only. No new accounts, no new tokens confirmed necessary.

## Traps

- Never log or echo the OJP key; fare probes read it from ~/lauzhack-ojp.env.
- NOVA prices are per fare authority — keep the FareAuthorityFilter.
- The current NovaFareProvider (transport.opendata.ch) stays as the fallback
  path; do not remove it until the OJP fare path is proven on real queries.
- Zone products and supersavers change pricing semantics: keep the honesty
  rule — unknown price = null, never a guess; label every price with its
  source.
