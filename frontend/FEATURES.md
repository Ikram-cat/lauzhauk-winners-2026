# ALT · Frontend features

The browser frontend under `web/` is ALT’s disruption-aware Swiss public transport planner. See [DESIGN.md](DESIGN.md) for visual behavior, [frontend README](README.md) for preview instructions, and the [backend API reference](../backend/README.md) for the deployed endpoints. The existing app and the website share the Kotlin API.

## Welcome and settings

- On entry, animate the ALT mark into ALTERNATIVE. Advance through nineteen localized welcome headings on open or reload using a browser-stored index.
- The railway clock follows current Swiss local time. Display written times in 24-hour format.
- English, Français, Italiano and Deutsch are selectable in the header and settings. Deutsch uses Swiss Standard German (`de-CH`). Persist the selected language and light, dark or system theme. Keep the active itinerary and entered values when switching.
- Respect operating-system reduced-motion preferences. Journey notifications are a future capability.

## Choose FROM and TO

- Show two independent fields on one planner. Each queries `GET /api/locations?query=…&limit=20` after a debounce, identifies stations and addresses, and requires the traveller to select a suggestion.
- Current-location actions for FROM and TO request one browser position after an explicit click. Each queries the existing `near` API and offers Swiss stops for confirmation. Permission denial, GPS failure, timeout, no matches, and service errors leave autocomplete usable.
- The current API exposes coordinates and kind without a country field. The browser uses the bundled [swissBOUNDARIES3D national boundary](https://geo.ld.admin.ch/boundaries/country/CH) from ©swisstopo, including interior holes. Suggestions with unverifiable positions are omitted. A verified country field on the location contract could support records that lack coordinates.
- Show straight-line distance from the explicitly requested device position for nearby search when accurate enough. For typed suggestions use the selected opposite endpoint when both have valid positions. Omit distances without a reliable reference and keep coordinates internal.

## Search and ranking

- Departure shows the current Swiss date and NOW by default. Omit `departure` so Ktor uses its current time. Choosing a different date opens a 24-hour time input, which can also be enabled using the time selector. Preserve the scheduled date and time across edits and ranking changes. Interpret scheduled departures and optional arrival deadlines in Europe/Zurich and send RFC 3339 timestamps. Reject incomplete, ambiguous and impossible local times.
- The planner provides Fastest and Simplest with keyboard- and touch-accessible information buttons and one shared explanation panel. Fastest prioritizes earliest arrival. Simplest prioritizes fewest transfers, then earliest arrival. The backend supplies ranking and recommendation.
- Include first or second class. Send one `GET /api/journeys` per valid search with its selected preference and the supported result limit. Choosing another mode with results starts a new search through the same planner.
- While a search is pending, show a schematic Switzerland map with the selected departure and arrival. The train animation illustrates waiting and the direct connection between endpoints. Actual route legs and timing come from the journey response.
- Mobile collapses the form only after local validation succeeds. The summary retains FROM → TO, mode and departure and can reopen the original values. A failed search reopens the form. Desktop keeps the form visible.

## Journeys and disruptions

- Pin the backend’s `recommendedJourney`. Keep the primary route identifiable when it differs and localize the structured recommendation reasons.
- Show alternatives automatically in server order. Keep the recommended ID out of the alternatives and deduplicate other IDs. A separate chronological browsing switch orders the returned set by departure.
- The API supports a per-response result limit. The browser shows seven alternatives initially and appends further cached results in groups of seven on scroll. Earlier rows stay in the document and a Previous journeys control at the end jumps back to the preceding group.
- Show a neutral confirmation only for `NO_DISRUPTION`. For `DISRUPTION_DETECTED`, show the affected-service banner and supplied next action. Mention alternatives only when the API returns a distinct route. Omit the main status line for `UNKNOWN` and retain the per-journey source and verification information.
- Journey cards and each leg distinguish scheduled times, announced delays, and statistical estimates. Round departure estimates down and arrival estimates up for the delay badges, and omit indicators for missing or zero delays. The combined time still uses the backend projection and rounds to the nearest display minute. Older responses fall back to expected or scheduled times. The planner preserves the backend recommendation and result order.
- Show journey durations in `1h05` format. Label mock backend responses and unverified estimates. Include supplied platforms, occupancy, deadlines, fares and model metadata.
- Journey details may show the typical historical disruption duration and confidence. The website omits incident-rate percentages and estimate measurement counts.
- The detail timeline shows walking and transit segments using named places. If the backend sends a coordinate string instead of a name, substitute an available selected endpoint or report that a location name is unavailable. Directions require provider-supplied instructions.

## Verification and integration

Check desktop and narrow layouts, keyboard focus, touch explainers, all locales, theme switching, validation and backend failures. Validate address and station suggestions near the Swiss border, permission states, NOW, Swiss local times, live result ordering, and disruption handling. Review API additions such as a verified location country or pagination cursor with backend and integration owners before using them.
