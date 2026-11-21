# Demo script

Use this runbook after a public deployment is available. Follow the [deployment guide](DEPLOYMENT.md) to get the URLs and deployed revisions, and use the [testing guide](TESTING.md) for the full test matrix.

1. Record test time and time zone, the deployed Web and API URLs, the commits or tags, and the browser/device. Open the public Web URL in a clean browser session and verify API health over HTTPS.
2. Enter Lausanne and Genève and leave departure blank for a current search, or enter a fresh future departure in Swiss local time if needed. Check that a real itinerary is displayed and that the UI identifies the schedule provenance. Confirm the browser request targets the deployed Ktor API and CORS succeeds.
3. Explain the recommendation as earliest scheduled arrival among the eligible results. Show departure, arrival, duration, transfers, and any supplied platform. Show alternatives if returned. Record the actual observation time and the deployed revision instead of hard-coding timetable results into the script.
4. At the stable milestone, exercise a validation error and capture evidence of the empty and failure states using a controlled test environment. Present any final Plan B feature with its actual data limitations. Report unavailable features as such.
5. Retain the last verified public deployment and its recorded revision. If the provider is unavailable, use the previously verified rollback when relevant or a tested, approved local mock mode if available. Keep the mock label visible and say when the demonstration uses a recording. A mock or recording provides demo continuity. Public milestone evidence requires a verified real journey.


## Sponsor judging sequence

Show Web and the second KMP target. Use a synthetic appointment screenshot plus typed context, correct one extracted value, and confirm the journey. Show one sourced real itinerary and a clearly labeled disruption replay with an affected leg and actionable recovery. Switch locale and theme on both targets. Explain shared logic versus platform-specific code, measured inference use, and reviewed Air work and contributions. Record evidence against the [challenge IDs](CHALLENGES.md).
