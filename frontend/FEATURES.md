# ALT · Frontend features

The browser frontend under `web/` is ALT’s disruption-aware Swiss public transport planner. See [DESIGN.md](DESIGN.md) for visual behavior, [frontend README](README.md) for preview instructions, and the [backend API reference](../backend/README.md) for the deployed endpoints. The existing app and the website share the Kotlin API.

## Welcome and settings

- On entry, animate the ALT mark into ALTERNATIVE. Advance through nineteen localized welcome headings on open or reload using a browser-stored index.
- The railway clock follows current Swiss local time. Display written times in 24-hour format.
- English, Français, Italiano and Deutsch are selectable in the header and settings. Deutsch uses Swiss Standard German (`de-CH`). Persist the selected language and light, dark or system theme. Keep the active itinerary and entered values when switching.
- Respect operating-system reduced-motion preferences. Journey notifications are a future capability.

## Choose FROM and TO

- Show two independent fields on one planner. Each queries `GET /api/locations?query=…&limit=20` after a debounce, identifies stations and addresses, and requires the traveller to select a suggestion.
- Briefly reuse successful autocomplete queries. Use the local rail directory if live autocomplete is unavailable or returns no usable match.
- Current-location actions request one fresh, high-accuracy browser position after an explicit click. Rank Swiss railway stations from the bundled SBB DiDok station index by straight-line distance and ask the traveller to confirm. Broad location fixes, permission denial, GPS failure, timeout, no nearby rail station and service errors leave autocomplete usable.
- Remove duplicate suggestions by stable ID or a matching nearby label. A separate transport stop with its own name or location remains selectable.
- The current API exposes coordinates and kind without a country field. The browser uses the bundled [swissBOUNDARIES3D national boundary](https://geo.ld.admin.ch/boundaries/country/CH) from ©swisstopo, including interior holes. Suggestions with unverifiable positions are omitted. A verified country field on the location contract could support records that lack coordinates.
- Show straight-line distance from the explicitly requested device position for nearby search when accurate enough. For typed suggestions use the selected opposite endpoint when both have valid positions. Omit distances without a reliable reference and keep coordinates internal.

## Search and ranking

- Departure shows the current Swiss date and NOW by default. Omit `departure` so Ktor uses its current time. Choosing a different date opens a 24-hour time input, which can also be enabled using the time selector. Preserve the scheduled date and time across edits and ranking changes. Interpret scheduled departures and optional arrival deadlines in Europe/Zurich and send RFC 3339 timestamps. Reject incomplete, ambiguous and impossible local times.
- The planner provides Fastest and Simplest with keyboard- and touch-accessible information buttons and one shared explanation panel. Fastest prioritizes earliest arrival. Simplest prioritizes fewest transfers, then earliest arrival. The backend supplies ranking and recommendation.
- Include first or second class. Each valid search starts with one `GET /api/journeys` request with its selected preference and the supported result limit. Choosing another mode with results starts a new search through the same planner.
- On an upstream rate limit, the browser respects `Retry-After`. A short cooldown permits one transparent journey retry. A longer cooldown returns the busy state without asking the provider again.
- While a search is pending, show a schematic Switzerland map with the selected departure and arrival. The train animation illustrates waiting and the direct connection between endpoints. Actual route legs and timing come from the journey response.
- Mobile collapses the form only after local validation succeeds. The summary retains FROM → TO, mode and departure and can reopen the original values. A failed search reopens the form. Desktop keeps the form visible.

## Journeys and disruptions

- Pin the backend’s `recommendedJourney`. Keep the primary route identifiable when it differs and localize the structured recommendation reasons.
- **Simulate disruption** reruns the selected result search with demo seed `12345`. Show **Simulation active** only when the backend marks the response as synthetic. **Return to live results** repeats the search with the seed omitted. For a reliable demonstration, choose a departure at least one hour ahead so live updates are less likely to override the simulation.
- Show alternatives automatically in server order. Keep the recommended ID out of the alternatives and deduplicate other IDs. A separate chronological browsing switch orders the returned set by departure.
- The API supports a per-response result limit. The browser shows seven alternatives initially and appends further cached results in groups of seven on scroll. Earlier rows stay in the document and a Previous journeys control at the end jumps back to the preceding group.
- Show a neutral confirmation only for `NO_DISRUPTION`. For `DISRUPTION_DETECTED`, show the affected-service banner and supplied next action. Mention alternatives only when the API returns a distinct route. Omit the main status line for `UNKNOWN` and retain the per-journey source and verification information.
- Journey cards and each leg distinguish scheduled times, announced delays, and statistical estimates. Show the backend projection to the second when supplied, and include the estimate basis, source, and sample count when available. Older responses fall back to expected or scheduled times. The planner preserves the backend recommendation and result order.
- The arrival summary badge on each result totals the available per-leg arrival estimates before rounding. Departure and arrival estimates describe separate stops on the same leg, so the total uses its arrival estimate only. Individual leg detail retains its own estimates.
- When the API supplies an incident-conditioned estimate, show the extra minutes relative to normal service and the historical cancellation and skipped-stop risks on the affected leg. The recommendation cites the incident and the journey-level percentages are the highest risks on any leg. Highlight cancellation risk above 30% and omit unknown values while retaining measured zeroes. Delay estimates use only trains that ran and reported times. Details show the supplied incident and observation counts.
- Show journey durations in `1h05` format. Label mock backend responses and unverified estimates. Include supplied platforms, occupancy, deadlines, fares and model metadata.
- Journey details use incident-conditioned risk in place of the general historical risk box. The panel appears when the API supplies incident data.
- The detail timeline shows walking and transit segments using named places. If the backend sends a coordinate string instead of a name, substitute an available selected endpoint or report that a location name is unavailable. Directions require provider-supplied instructions.

## Verification and integration

Check desktop and narrow layouts, keyboard focus, touch explainers, all locales, theme switching, validation and backend failures. Validate address and station suggestions near the Swiss border, permission states, NOW, Swiss local times, live result ordering, and disruption handling. Review API additions such as a verified location country or pagination cursor with backend and integration owners before using them.
