# LauzHack 2026 challenge plan

Source reviewed on 21 November 2026. The [official challenge page](https://app.lauzhack.com/Challenges) lists AXA, JetBrains and SBB (CFF). The [organizer schedule](https://lauzhack.com/) lists the end of hacking at noon on Sunday, 22 November. Confirm the submission mechanism and cutoff in the participant portal. Every deliverable below is planned. This document extends the original Web-first master plan for the challenge submission.

## One demo for three challenges

Use a synthetic screenshot of an appointment plus typed travel context. Extract candidate location and time, let the traveler confirm or correct them, and search for journeys through Ktor. When the selected journey is affected by a cancellation or delay, explain the impact and recommend the next feasible option or say when no option meets the deadline. Demonstrate the same core workflow on Web and desktop, with Android emulator as an alternative if desktop is blocked. Use a verified live itinerary beside a clearly labeled disruption replay if a real incident is unavailable.

| ID | Official criterion or judging factor | Planned evidence and owner |
| --- | --- | --- |
| JB1 | Cross-platform KMP app with working core functionality on at least two platforms | A runs the same search and recovery scenario on Web and desktop. D records both builds, target names, results and commit. An HTML fallback preserves the Web MVP, but cannot be counted as a KMP target. |
| JB2 | Genuine shared logic, polished platform-appropriate UX, innovation and clear presentation | A demonstrates shared state, validation and recovery presentation in `commonMain` and shows platform-specific image pickers. D explains the commuter use case and shows the same behavior on both targets. |
| JB3 | Dark/light mode, localization, Compose Multiplatform, and Kotlin backend integration | A demonstrates light/dark themes and French/English resources on both targets. B provides Ktor and D records browser and desktop requests reaching the API. |
| JB4 | Shared AI or agent logic where useful | A shares screenshot extraction orchestration, typed outcomes, user confirmation and fallback in `commonMain`. B owns inference credentials and provider calls. Evaluate Koog only if it fits supported targets and the deadline. Show the shared code path. |
| JB5 | JetBrains Air extra credit, including multi-agent work and human steering | D records actual Air sessions with scoped shared and platform tasks, reviewed diffs, steering decisions and resulting PRs. Only claim Air work that happened in Air. |
| AX1 | Working multimodal app, preferably image plus text, with polished UX | A demonstrates screenshot plus typed context, field review, correction, accessible errors and manual fallback. D presents an app rather than slides alone. |
| AX2 | Frugality | A and B prioritize local crop or text extraction where feasible. Use a bounded small-model fallback for unresolved fields, with no call on the manual path. C measures provider calls, latency and cost or token use where observable on a fixed sample. Mark estimates and report the sample size and baseline. |
| AX3 | Relevant and original problem, technical challenge, human direction, teamwork and presentation | D credits each contributor and shows their work. Explain the route-aware recovery, model budget and explicit confirmation. Show the design tradeoff and the working flow with the team's own examples and wording. |
| CFF1 | Process disruption and travel information and identify impact on the individual journey | B matches disruptions to affected legs using verified service or station identifiers and effective times. C tests matched, unrelated and uncertain cases. Report source and observation time. |
| CFF2 | Clear, relevant, actionable recommendation and traveler experience | A and B show the affected service or transfer, consequence for the confirmed deadline, and what to do next. Show an alternative with departure and platform if supplied, or a clear no-feasible-route state. D demonstrates when the guidance appears. |

The official page lists no separate bonus schedule for AXA or SBB. AXA rewards polish and frugality and considers technical originality, teamwork and presentation. SBB emphasizes understandable personalized guidance and the context in which travelers receive it.

## Delivery gates

1. Preserve the T+4 public Web and Ktor journey search. A spikes desktop alongside the browser build after the shared scaffold compiles. C prepares synthetic, license-safe demo inputs.
2. By T+11, verify the public MVP and a second KMP target running the shared core. B and C deliver a repeatable disruption replay and match tests. D starts capturing verified Air evidence during development.
3. After the stable checkpoint, connect image-plus-text extraction, add French and English and both themes, measure the small-model path, and rehearse the joined scenario on both targets.
4. Before submission, D records each ID as verified or pending, with its commit, build or test command, device, and evidence. Include contribution credits and note if live disruptions, cost estimates, or bonus evidence remain unverified.

## Data boundaries

Treat screenshots as potentially personal. Use synthetic samples for demos and tests, collect only required fields, keep provider credentials on Ktor, and avoid saving or logging images. A candidate extraction may be unknown or wrong. Ask the traveler to confirm it before searching. A mock incident or unmatched identifier never establishes a live cancellation. Keep sourced facts, derived impacts and recommendations distinguishable.
