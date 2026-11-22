# ALT → ALTERNATIVE — Visual Design & UX Specification

> LauzHack 2026 · Companion to `FEATURES_COMPLETED.md` (rename that file to `FEATURES.md` in your repository).

## 1. Creative direction

A **Swiss editorial / experimental minimalism** aesthetic: strong **Helvetica** typography, precise grids, thin rules, utilitarian transit information, geometric controls, and intentional white space. Combine the calm, information-dense feel of the supplied sunset UI with the high-contrast modular grid and experimental typographic hierarchy of the other three references.

**Hard color rule: ONLY red, gray, white, and black.** No blue, orange, yellow, pink, green, violet, gradients introducing other hues, or default system-blue controls. Neutral gray gradients are allowed; a red-to-dark-red gradient is allowed only if both endpoints remain red.

**Personality:** intelligent, fast, precise, confident, distinctly Swiss; not a generic travel booking template.

## 2. Attached visual references and what to borrow

These four uploaded images are the **visual moodboard**; they are not screens to copy pixel-for-pixel. In the local task workspace, the original assets are:

| Reference | Original attached image | Take from it | Adapt for ALT |
|---|---|---|---|
| **A — Sunset dashboard** | `image (1).png` | Large numerical hierarchy, tiny spaced-out labels, atmospheric header, segmented progress, precise data layout | Replace colorful sky with neutral grayscale; reserve red for alerts, train-position markers, and key actions |
| **B — Editorial grid** | `image (2).png` | Thin black dividers, off-white surfaces, modular square icon cells, unembellished typography | Use for origin-method selection and settings tiles; replace original coral block with ALT red |
| **C — AI OS two-phone concept** | `image (3).png` | Dot-matrix information, Swiss clock-inspired circular dial, quiet UI, round geometric controls | Use a restrained dot-matrix loading state and railway clock animation; convert all pastel accents to red/grays |
| **D — Foldable editorial UI** | `image(2).png` | Oversized bold typography, numbered panels, stacked cards, grid discipline, bottom instrument-like strip | Use for ranked journey rows, numbered alternative cards, and mode tabs; recolor all panels monochrome with selective red |

**Asset handling:** images were uploaded alongside the source Markdown. For a portable repo, copy the four originals to `docs/design/references/` and update these paths. This Markdown describes their design contribution even when the image assets are not copied.

## 3. Design tokens

### Palette (strict)

| Token | Hex | Usage |
|---|---|---|
| `red.primary` | `#E53935` | Primary CTA, active tab, urgent disruption |
| `red.deep` | `#B71C1C` | Hover/pressed, high-contrast red |
| `red.soft` | `#FBE9E7` | **Do not use**: has warm orange undertone; instead use a neutral gray surface with a red outline |
| `black` | `#101010` | Main text, lines, dark surfaces |
| `gray.900` | `#242424` | Dark card |
| `gray.700` | `#555555` | Secondary dark text |
| `gray.500` | `#888888` | Metadata |
| `gray.300` | `#C7C7C7` | Hairlines, inactive controls |
| `gray.100` | `#F2F2F2` | Light cards |
| `white` | `#FFFFFF` | Light canvas / dark-mode text |

**Implementation note:** only actual grayscale (`R=G=B`), pure white/black, and red-family values may be used. For example `#E53935` and `#B71C1C` are red. Avoid colored shadows and colored map tiles.

### Theme assignments

| Role | Light mode | Dark mode |
|---|---|---|
| Background | `#FFFFFF` | `#101010` |
| Surface | `#F2F2F2` | `#242424` |
| Primary text | `#101010` | `#FFFFFF` |
| Secondary text | `#555555` | `#C7C7C7` |
| Border | `#C7C7C7` | `#555555` |
| Action / active | `#E53935` | `#E53935` |
| Alert | Red icon + outline + label | Red icon + outline + label |

No status should rely on color alone; include warning triangle, severity text, or a clear icon.

### Typography — Helvetica (the Swiss typeface)

**One family: Helvetica** — the typeface of the International Typographic Style this design comes from. Every element (display, headings, body, data, labels, chips, buttons) is set in it; hierarchy comes from weight and size only — never a second font, never italics.

- Stack (web/wasm): `font-family: "Helvetica Now", "Helvetica Neue", Helvetica, Arial, sans-serif;` — fallbacks must stay grotesk; never a serif or a geometric sans.
- **Display:** Helvetica Bold/Black, 44–64 sp on mobile, larger on desktop; very tight tracking for hero titles.
- **Headings:** 24–32 sp Bold; maintain clear hierarchy.
- **Body:** 15–17 sp Regular, high readability.
- **Data numbers:** 30–48 sp Bold, tabular numerals for arrival time, delays, and prices.
- **Micro labels:** 10–12 sp Bold, uppercase, tracking +1.2–2 px; avoid micro labels for essential accessible information.
- Licensing/performance: system Helvetica (macOS, iOS, most desktops) covers most targets; a licensed **Helvetica Now** may be bundled for brand-critical surfaces only. Where Helvetica is unavailable, the Arial fallback preserves the grotesk character. Do not ship fonts taken from the reference images.

### Geometry and spacing

- Base spacing unit: **4 dp**; typical gaps 8 / 12 / 16 / 24 / 32.
- Cards: primarily square-cornered or subtly rounded (4–8 dp); use fully circular elements only for clock, location button, and iconic controls.
- Dividers: 1 dp neutral gray or black; precise alignment.
- Layout: 12-column desktop / single-column mobile; 16–20 dp mobile horizontal gutters.
- Tap targets: at least 44–48 dp. No tiny text-only interactive targets.
- Use shadow sparingly; prefer borders, contrast, and spacing.

## 4. Brand and opening motion — ALT → ALTERNATIVE

### First frame

A perfectly centered **square** with a thin black or red border. Inside, the letters **ALT** are bold, black (light theme) or white (dark theme), aligned precisely.

```text
             ┌───────────────┐
             │               │
             │      ALT      │
             │               │
             └───────────────┘
```

### Animation sequence (suggested 1.2–1.8 s total)

1. **0–300 ms:** square and `ALT` appear with subtle opacity/scale transition.
2. **300–850 ms:** square widens or its right border travels outward; `ALT` stays anchored while the remaining letters reveal.
3. **850–1250 ms:** final word **ALTERNATIVE** locks into place with a thin red underline or red square accent.
4. **1250–1800 ms:** crossfade/slide into the starting-location interface.

The brand must read **ALTERNATIVE**, not `ALTTERNATIVE`; reveal `ERNATIVE` after `ALT`. Preserve visual continuity from square to wordmark. Do not require animation to finish before screen-reader access; offer reduced-motion instant reveal.

### Brand treatment

- Primary logo: **ALT** in an outlined square.
- Expanded wordmark: **ALTERNATIVE**, uppercase.
- Optional supporting line: **YOUR JOURNEY. ANOTHER WAY.**
- No trains as literal logo marks unless used as a tiny supporting pictogram.

## 5. Screen architecture and visual wireframes

### Screen 0 — Welcome / Splash

- Full-screen white or black background.
- Centered square ALT mark, animated expansion.
- Optional tiny top-left `SWISS JOURNEY ASSISTANT` and bottom-right `01 / 04` editorial metadata.
- No premature location permission prompt.

### Screen 1 — Starting location

**Header:** expanded ALTERNATIVE wordmark, small `START YOUR JOURNEY` label, large heading **Where are you starting?**

**Three modular option cards:**

```text
┌─────────────────────────────────────┐
│ ALTERNATIVE                   01/04 │
│                                     │
│ WHERE ARE YOU                       │
│ STARTING?                           │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 01  ENTER AN ADDRESS         → │ │
│ │     Search street or place...   │ │
│ ├─────────────────────────────────┤ │
│ │ 02  RAILWAY STATION          → │ │
│ │     Find a station              │ │
│ ├─────────────────────────────────┤ │
│ │ 03  USE MY CURRENT LOCATION  ◎ │ │
│ │     Permission requested on tap │ │
│ └─────────────────────────────────┘ │
│                                     │
│  SELECTED ORIGIN: NOT SET           │
│  [ CONTINUE → ] (disabled)          │
└─────────────────────────────────────┘
```

- Red active border/indicator; gray inactive rows; thin dividers like Reference B.
- Address/station autocomplete opens an inline panel with keyboard and touch selection.
- Geolocation shows spinner → suggested station → confirm/edit.
- Confirmation card includes name and location context, with `CHANGE` action.
- Disabled Continue uses gray and text; active Continue uses red with white label.

### Screen 2 — Journey planner

- Origin summary (editable), destination autocomplete, optional departure date/time and arrive-by deadline.
- Segmented **FASTEST | SIMPLEST | CHEAPEST** control, red underline/active cell.
- Fare controls: 1st/2nd class and Half-Fare.
- Large **FIND MY ROUTE →** CTA.
- Secondary **STUCK AT A STATION?** shortcut.
- Subtle Swiss clock motif near departure time, not a decorative distraction.

### Screen 3 — Journey results

**Visual hierarchy:**

1. Header: `ALTERNATIVE / YOUR ROUTES`, origin → destination, edit action.
2. Top status line: disruption / no disruption / live status unavailable.
3. If backend confirms disruption, **outlined red warning banner** with warning triangle and clear explanatory text.
4. **MOST RECOMMENDED** featured card: oversized arrival time, travel duration, transfer count, fare if available, reliability/verification labels, concise reason, red accent stripe.
5. **VIEW OTHER OPTIONS ↓** expandable divider/button.
6. Mode tabs and optional **CHRONOLOGICAL** browsing toggle.
7. Alternatives in numbered modular cards; seven visible per batch, with scroll-based loading.

Example card structure (values are *illustrative placeholders only*, never hard-coded in production):

```text
┌─────────────────────────────────────┐
│ MOST RECOMMENDED               01  │
│                                     │
│  09:42 → 10:38                      │
│  ARRIVAL  10:38                     │
│                                     │
│  DURATION   TRANSFERS   PRICE       │
│  56 MIN     01          CHF —       │
│                                     │
│  WHY THIS ROUTE?                    │
│  [Reason from backend codes]        │
│                                     │
│  [ VIEW JOURNEY DETAILS → ]         │
└─────────────────────────────────────┘
```

- `CHF —` indicates unavailable price; do not imply zero.
- Place `GTFS_RT`, `OJP`, `DELAY_MODEL`, or `NONE` provenance in a readable small caption.
- A journey with `verified: false` needs an explicit estimated-status note.
- A risk chip uses text + icon; not green/yellow/red traffic-light colors.
- Pin recommended card outside the scroll list to avoid duplicate or reordered entries.
- When the mode changes, show the appropriate backend-ranked session; do not locally reorder incomplete batches.

### Screen 4 — Journey details / disruption impact

- Vertical stop-by-stop timeline, monochrome lines, red only on impacted leg.
- Large departure/arrival numerals; platform, delay, occupancy and confidence where available.
- `WHAT CHANGED` / `WHAT IT MEANS` / `YOUR NEXT STEP` modular panels.
- Explicit `STAY` or `REROUTE` recommendation and alternatives action.
- Show deadline feasibility, model source, and last updated timestamp.

### Screen 5 — Settings

- Minimal modular grid for theme (LIGHT / DARK / SYSTEM), language (FR / EN / DE / IT), notification preferences, and accessibility/reduced-motion.
- Persist preferences and never lose an active journey when switching theme/language.

## 6. Motion system

- Navigation: 180–300 ms fade/slide, easing smooth and restrained.
- Card expansion: 180–250 ms, preserve scroll position.
- Loading: gray dot matrix with a single red moving indicator; no colored gradients.
- Train illustration: minimal black/gray silhouette sliding along a thin red/gray track, subtle loop only while waiting.
- Swiss railway clock: minimalist black/gray dial, red second hand; do not misrepresent a decorative clock as a real departure countdown.
- Disruption arrival: red border draws in + icon appears; no aggressive flashing.
- Respect reduced-motion and system animation settings.

## 7. Interaction states and accessibility

| Component | Idle | Active | Loading | Error |
|---|---|---|---|---|
| Location search | Gray border, placeholder | Black/red border, suggestions | Gray spinner/dots | Red outline + text message |
| Current location | Red CTA | Permission request | Finding nearby stops | Denied/timeout with manual fallback |
| Mode tabs | Gray/white | Red underline/indicator | Preserve selection | Explain backend failure |
| Recommended card | Black/gray surface | Red accent | Skeleton | Unavailable explanation |
| Alternatives | Collapsed | Expanded list | 7-item skeleton/footer | Retry batch without losing scroll |

- Use text + icons for all states; never only red to indicate an error.
- Support keyboard navigation and visible focus rings.
- Announce search completion, disruption alerts, and permission errors to assistive technologies.
- Ensure adequate contrast in both modes; avoid tiny, low-contrast microtype for critical times or instructions.

## 8. Responsive behavior

- **Mobile (< 600 dp):** single-column flow, sticky bottom CTA where useful, cards fill width, accessible 48 dp controls.
- **Tablet (600–1024 dp):** centered max-width content, optional two-column form and journey detail.
- **Desktop (> 1024 dp):** max-width ~1200 px; left search/filters rail and right results, while maintaining same ordering and semantics.
- Never hide important disruptions or pricing behind hover-only interactions.

## 9. Component inventory (Compose Multiplatform)

`AltLogo`, `WelcomeAnimation`, `EditorialHeader`, `LocationMethodCard`, `LocationAutocomplete`, `NearbyStationList`, `LocationPermissionState`, `OriginConfirmation`, `JourneySearchForm`, `OptimizationTabs`, `FareControls`, `SwissClockMotif`, `DisruptionBanner`, `StatusLine`, `RecommendedJourneyCard`, `JourneyCard`, `JourneyTimeline`, `RiskChip`, `DataProvenanceLabel`, `AlternativesDisclosure`, `JourneyBatchLoader`, `DeadlinePanel`, `SettingsGrid`, `ErrorState`, `LoadingDots`.

Use a single shared token set and reusable composables. Compose animations should not block network requests. Platform adapters should not leak into visual components.

## 10. Design-to-development handoff

- **Content comes from backend:** times, stations, delays, disruptions, fares, occupancy, ranking, feasibility, recommendation reasons.
- **Content comes from localization:** headings, labels, alerts, error explanations, reason-code wording.
- **Design tokens:** centralized theme definitions for light/dark; strict four-color family rule.
- **Asset strategy:** redraw abstract clocks/trains with vector/Compose primitives; do not copy reference screenshots as production UI.
- **Do not imply server pagination is implemented** until the backend supports it. See `FEATURES_COMPLETED.md` §8.

## 11. Visual QA checklist

- [ ] `ALT` starts inside a square and expands correctly to `ALTERNATIVE`.
- [ ] No colors outside red, grayscale, black and white (including icons, maps, focus states, gradients, charts).
- [ ] The four supplied references influence typography, modular grids, numerical hierarchy and restrained motion.
- [ ] First screen clearly offers address / railway station / current location.
- [ ] Permission requested only after location-button interaction.
- [ ] Most Recommended card remains visually dominant in all modes.
- [ ] Disruption warnings have triangle icon, meaningful text, and red outline.
- [ ] View Other Options reveals results without jumping scroll position.
- [ ] Seven-result loading states and previous/next batches are visually coherent.
- [ ] Theme and all four languages work with long labels and dynamic values.
- [ ] Reduced-motion, screen-reader, keyboard and high-contrast use remain functional.

**Design principle:** Swiss precision, editorial confidence, and instant clarity when a journey goes wrong.
