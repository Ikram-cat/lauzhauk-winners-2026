# ALT → ALTERNATIVE · Visual design and UX

## Direction

ALT uses a Swiss editorial grid, Helvetica with Arial and sans-serif fallbacks, precise one-pixel rules, generous whitespace, and bold tabular journey times. Use red, neutral gray, white, and black in both themes. Reserve red for actions, focus, disruption outlines and the railway clock’s second hand. Include readable status text and identifiable symbols.

| Role | Light | Dark |
| --- | --- | --- |
| Canvas | `#FFFFFF` | `#101010` |
| Surface | `#F2F2F2` | `#242424` |
| Text | `#101010` | `#FFFFFF` |
| Secondary text | `#555555` | `#C7C7C7` |
| Rules | `#C7C7C7` | `#555555` |
| Red accents | `#E53935` | `#E53935` |

Use a four-pixel spacing scale, square corners and 44–48 px control targets. Keep layouts usable with long strings and visible keyboard focus.

## References

The design borrows the numerical hierarchy and thin progress lines from the sunset dashboard, the modular cells and rules from the editorial grid, the Swiss-style clock from the AI OS concept, and bold bands from the foldable editorial mockup. The [foldable journey reference](references/foldable-journey-layout.png), when available, provides the layout for full-width result bands with compact facts and transport pictograms. ALT uses the reference’s typography and separators with an unnumbered monochrome layout.

## Branding and planner

- Reuse the outlined square ALT mark beside the ALT wordmark on both desktop and mobile.
- The welcome animation holds the square `ALT` mark, then widens its frame as a red rule and the `ERNATIVE` suffix reveal together. A red square punctuates the full wordmark before the opening layer lifts. Keyboard focus dismisses the decorative layer, and reduced-motion preferences show the planner immediately.
- Cycle the welcome heading through the nineteen localized phrases on page open or reload. Keep the same phrase when rerendering or changing language.
- Place a large Swiss railway clock high in the masthead. Use sixty ticks, heavier hour markers, two dark hands, and a red paddle second hand. All hands follow current Europe/Zurich time. Format written times using 24-hour time.
- Use the sunset reference’s soft horizon as inspiration for a page-wide gray gradient with a visible red glow that builds through the results area. Add a faint monochrome dot pattern to the page background. Keep form and result surfaces opaque in both themes.
- Show FROM and TO together. Each uses a scrollable address and station autocomplete with an adjacent current-location button. Use regular weight for parenthesized locality details. Suggestions identify their type and show reliable straight-line distances when a reference exists.
- The search rail keeps the departure date and time choice visible together. It starts on the current Swiss date with NOW selected. A scheduled departure shows a 24-hour time field next to the date controls.
- Keep one Fastest/Simplest control with two circular question-mark buttons and a shared explanation panel. Highlight the selected mode and its question mark together. Switching information buttons replaces the explanation. Clicking the open button again, clicking outside, or pressing Escape closes the panel.
- Show Searching and a thin red progress line in the submit button. In the results area, show a compact outline of Switzerland with the selected endpoints and a small red train along their connecting line. Keep the train still when reduced motion is requested.

## Results, details and settings

- Keep the backend’s Most Recommended journey visually dominant above automatically visible alternatives. Use a large red pill for the recommendation label and visibly space the hour, colon and minute. Format alternatives as uniformly white bands in light mode with a consistent dark surface in dark mode.
- Place the status in a high-contrast rectangular panel directly above the recommendation. Use a neutral gradient and check icon when clear, and a red glow with a warning triangle for confirmed disruptions. Mention alternative routes only when supplied and omit the panel when conditions are unknown. Show journey durations as hours and two-digit minutes, such as `1h05`. Show nonzero delay labels and omit zero-minute annotations.
- Use traveller-facing names for walking endpoints. Hide numeric coordinate strings and fall back to a named selected endpoint when known.
- Label historical disruption rates as observations for the corridor or baseline. Show the time window, typical disruption duration and confidence when available.
- Desktop keeps search controls beside results. On narrow screens a valid submission collapses search into an editable summary with FROM → TO, the ranking mode and departure. Reopen the form on a backend error and keep it open for local validation errors.
- Offer English, Français, Italiano and Deutsch. Persist language and light, dark or system theme. Respect reduced motion through the operating system.

See [FEATURES.md](FEATURES.md) for behavior and data boundaries.
