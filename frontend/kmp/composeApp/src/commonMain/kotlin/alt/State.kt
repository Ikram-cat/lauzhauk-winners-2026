package alt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

const val PAGE_SEARCH = 0
const val PAGE_RESULTS = 1

class AppState(private val scope: CoroutineScope) {
    val api = AltApi(defaultApiBase())

    var lang by mutableStateOf(Lang.EN)
    val welcome = pickWelcome()
    var dark by mutableStateOf(false)
    var page by mutableStateOf(PAGE_SEARCH)

    var fromText by mutableStateOf("")
    var toText by mutableStateOf("")
    var fromSel by mutableStateOf<Location?>(null)
    var toSel by mutableStateOf<Location?>(null)
    var fromSug by mutableStateOf<List<Location>>(emptyList())
    var toSug by mutableStateOf<List<Location>>(emptyList())
    var fromSearching by mutableStateOf(false)
    var toSearching by mutableStateOf(false)

    var dateText by mutableStateOf(todayZurichDate())
    var timeText by mutableStateOf("")
    var arriveBy by mutableStateOf(false)

    var preference by mutableStateOf("FASTEST")
    var firstClass by mutableStateOf(false)
    var halfFare by mutableStateOf(false)

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<JourneyResponse?>(null)
    var simActive by mutableStateOf(false)

    private var lastQuery: SearchQuery? = null
    private var searchJob: Job? = null
    private var fromJob: Job? = null
    private var toJob: Job? = null

    fun t(key: String): String = s(key, lang)

    fun onFromChange(text: String) {
        fromText = text
        fromSel = null
        fromJob?.cancel()
        if (text.trim().length < 3) { fromSug = emptyList(); fromSearching = false; return }
        fromSearching = true
        fromJob = scope.launch {
            delay(280)
            runCatching { api.locations(text.trim(), 12) }
                .onSuccess { if (fromText == text) fromSug = it.stopsOnly() }
                .onFailure { if (fromText == text) fromSug = emptyList() }
            if (fromText == text) fromSearching = false
        }
    }

    fun onToChange(text: String) {
        toText = text
        toSel = null
        toJob?.cancel()
        if (text.trim().length < 3) { toSug = emptyList(); toSearching = false; return }
        toSearching = true
        toJob = scope.launch {
            delay(280)
            runCatching { api.locations(text.trim(), 12) }
                .onSuccess { if (toText == text) toSug = it.stopsOnly() }
                .onFailure { if (toText == text) toSug = emptyList() }
            if (toText == text) toSearching = false
        }
    }

    fun pickFrom(loc: Location) { fromSel = loc; fromText = loc.name; fromSug = emptyList() }
    fun pickTo(loc: Location) { toSel = loc; toText = loc.name; toSug = emptyList() }

    fun swap() {
        val f = fromText; fromText = toText; toText = f
        val fs = fromSel; fromSel = toSel; toSel = fs
        fromSug = emptyList(); toSug = emptyList()
    }

    fun switchLang(l: Lang) { lang = l }
    fun toggleTheme() { dark = !dark }

    private fun timestamp(date: String, time: String): String? {
        if (date.isBlank() && time.isBlank()) return null
        if (time.isBlank()) return null
        if (date.isBlank()) return "INVALID"
        val iso = "${date.trim()}T${time.trim()}"
        return localToInstantIso(iso) ?: "INVALID"
    }

    fun search(simulate: Boolean = false) {
        if (fromText.isBlank() || toText.isBlank()) { error = t("enterboth"); return }
        if (fromText.trim().equals(toText.trim(), ignoreCase = true)) { error = t("sameod"); return }
        val sameId = fromSel?.id != null && fromSel?.id == toSel?.id
        if (sameId) { error = t("sameod"); return }
        var ts = timestamp(dateText, timeText)
        if (ts == "INVALID") { error = t("badtime"); return }
        if (arriveBy && ts == null) { error = t("badtime"); return }
        if (simulate && !arriveBy && ts == null) {
            // Deterministic demo departure: for imminent departures the live GTFS-RT feed
            // supersedes the synthetic incident and the simulation shows nothing. Tomorrow
            // 07:00 is outside the live feed's window and the incident fires reliably.
            dateText = tomorrowZurichDate()
            timeText = "07:00"
            ts = timestamp(dateText, timeText)
        }

        val q = SearchQuery(
            origin = fromText.trim(),
            destination = toText.trim(),
            departure = if (!arriveBy) ts else null,
            arrivalDeadline = if (arriveBy) ts else null,
            preference = if (preference == "CHEAPEST") "FASTEST" else preference,
            firstClass = firstClass,
            halfFare = halfFare,
            demoPerturbation = if (simulate) 12345L else null,
        )
        lastQuery = q
        loading = true
        error = null
        searchJob?.cancel()
        searchJob = scope.launch {
            try {
                val res = api.search(q)
                result = res
                simActive = res.demoPerturbation?.active == true
                page = PAGE_RESULTS
            } catch (e: ApiException) {
                error = "${e.code} — ${e.userMessage}"
            } catch (e: Throwable) {
                error = e.message ?: "Network error"
            } finally {
                loading = false
            }
        }
    }

    fun goBack() { page = PAGE_SEARCH }

    fun retry() {
        val q = lastQuery
        if (q != null) {
            loading = true; error = null
            searchJob?.cancel()
            searchJob = scope.launch {
                try {
                    result = api.search(q); simActive = result?.demoPerturbation?.active == true
                    page = PAGE_RESULTS
                }
                catch (e: ApiException) { error = "${e.code} — ${e.userMessage}" }
                catch (e: Throwable) { error = e.message ?: "Network error" }
                finally { loading = false }
            }
        } else search()
    }

    fun simulate() = search(simulate = true)

    private fun todayText(): String = todayZurichDate()
}

/** Stations only — addresses are excluded from autocomplete by design. */
private fun List<Location>.stopsOnly(): List<Location> =
    filter { it.kind.equals("STOP", ignoreCase = true) }
        .map { it.copy(name = cleanStationName(it.name)) }
        .take(6)

/** "Zürich HB (Zürich)" -> "Zürich HB" (parent-town suffix always stripped, SBB-style). */
private fun cleanStationName(name: String): String {
    val m = Regex("^(.+) \\([^)]+\\)$").find(name) ?: return name
    return m.destructured.component1().trim()
}

private fun tomorrowZurichDate(): String = try {
    LocalDate.parse(todayZurichDate()).plus(DatePeriod(days = 1)).toString()
} catch (e: Exception) {
    todayZurichDate()
}
