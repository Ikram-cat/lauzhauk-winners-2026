package planb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class StationSuggestion(
    val name: String,
    val address: String? = null,
    val aliases: List<String> = emptyList(),
    val kind: String? = null
) {
    internal val searchTerms = listOfNotNull(name, address) + aliases
}

/**
 * Canonical (French-form) station name -> its name in each UI language. Swiss stations are not
 * literally translated on the real SBB/CFF/FFS site, but this app's users asked for city names to
 * follow the selected language, so this table drives both display (localizedStationName) and
 * autocomplete matching (every language form is also a search alias, see [stations]).
 */
private val stationNames: Map<String, Map<Language, String>> = mapOf(
    "Lausanne" to mapOf(Language.French to "Lausanne", Language.English to "Lausanne", Language.German to "Lausanne", Language.Italian to "Lausanne"),
    "Genève" to mapOf(Language.French to "Genève", Language.English to "Geneva", Language.German to "Genf", Language.Italian to "Ginevra"),
    "Bern" to mapOf(Language.French to "Berne", Language.English to "Bern", Language.German to "Bern", Language.Italian to "Berna"),
    "Zürich HB" to mapOf(Language.French to "Zurich HB", Language.English to "Zurich HB", Language.German to "Zürich HB", Language.Italian to "Zurigo HB"),
    "Fribourg" to mapOf(Language.French to "Fribourg", Language.English to "Fribourg", Language.German to "Freiburg", Language.Italian to "Friburgo"),
    "Biel/Bienne" to mapOf(Language.French to "Bienne", Language.English to "Biel/Bienne", Language.German to "Biel", Language.Italian to "Bienne"),
    "Neuchâtel" to mapOf(Language.French to "Neuchâtel", Language.English to "Neuchâtel", Language.German to "Neuenburg", Language.Italian to "Neuchâtel"),
    "Lugano" to mapOf(Language.French to "Lugano", Language.English to "Lugano", Language.German to "Lugano", Language.Italian to "Lugano"),
    "Basel SBB" to mapOf(Language.French to "Bâle CFF", Language.English to "Basel SBB", Language.German to "Basel SBB", Language.Italian to "Basilea FFS"),
    "Luzern" to mapOf(Language.French to "Lucerne", Language.English to "Lucerne", Language.German to "Luzern", Language.Italian to "Lucerna"),
    "Sion" to mapOf(Language.French to "Sion", Language.English to "Sion", Language.German to "Sitten", Language.Italian to "Sion"),
    "Montreux" to mapOf(Language.French to "Montreux", Language.English to "Montreux", Language.German to "Montreux", Language.Italian to "Montreux"),
    "Yverdon-les-Bains" to mapOf(Language.French to "Yverdon-les-Bains", Language.English to "Yverdon-les-Bains", Language.German to "Yverdon-les-Bains", Language.Italian to "Yverdon-les-Bains"),
    "Nyon" to mapOf(Language.French to "Nyon", Language.English to "Nyon", Language.German to "Nyon", Language.Italian to "Nyon")
)

/** Looks up [name] (any spelling/language) against [stationNames]; returns [name] unchanged for anything not in the table. */
internal fun localizedStationName(name: String, language: Language): String {
    val query = stationKey(name)
    val entry = stationNames.entries.firstOrNull { (canonical, variants) ->
        stationKey(canonical) == query || variants.values.any { stationKey(it) == query }
    } ?: return name
    return entry.value[language] ?: name
}

private fun StationSuggestion.withLocalizedAliases(): StationSuggestion {
    val variants = stationNames[name]?.values.orEmpty()
    return copy(aliases = (aliases + variants).distinct())
}

private val stations = listOf(
    // https://www.lausanne-tourisme.ch/en/explore/lausanne-railway-station/lausanne-railway-station/
    StationSuggestion("Lausanne", "Place de la Gare", listOf("Lausanne Gare", "Gare de Lausanne")),
    // https://www.sbb.ch/fr/informations-voyages/gares/trouver-gare/gare-de-geneve.html
    StationSuggestion("Genève", "Place de Cornavin", listOf("Genève Cornavin", "Cornavin")),
    StationSuggestion("Bern"),
    StationSuggestion("Zürich HB", aliases = listOf("Zurich main station", "Zürich Hauptbahnhof")),
    StationSuggestion("Fribourg"),
    StationSuggestion("Biel/Bienne"),
    StationSuggestion("Neuchâtel"),
    StationSuggestion("Lugano"),
    StationSuggestion("Basel SBB"),
    StationSuggestion("Luzern"),
    StationSuggestion("Sion"),
    StationSuggestion("Montreux"),
    StationSuggestion("Yverdon-les-Bains"),
    StationSuggestion("Nyon")
).map { it.withLocalizedAliases() }

internal fun stationSuggestions(input: String): List<StationSuggestion> {
    val query = stationKey(input)
    if (query.isEmpty()) return emptyList()
    val words = query.split(' ')
    return stations
        .mapNotNull { station ->
            val keys = station.searchTerms.map(::stationKey)
            val rank = when {
                query in keys -> 0
                keys.any { it.startsWith(query) } -> 1
                keys.any { key -> words.all { word -> key.split(' ').any { it.startsWith(word) } } } -> 2
                else -> return@mapNotNull null
            }
            station to rank
        }
        .sortedBy { (_, rank) -> rank }
        .map { (station, _) -> station }
}

internal fun canonicalStationInput(input: String): String {
    val query = stationKey(input)
    return stations.firstOrNull { station -> station.searchTerms.any { stationKey(it) == query } }?.name
        ?: input.trim()
}

private fun stationKey(value: String): String = value.trim().lowercase()
    .map { character ->
        when (character) {
            in "àâä" -> 'a'
            in "éèêë" -> 'e'
            in "ìíîï" -> 'i'
            in "òóôö" -> 'o'
            in "ùúûü" -> 'u'
            'ç' -> 'c'
            else -> if (character.isLetterOrDigit()) character else ' '
        }
    }
    .joinToString("")
    .split(' ')
    .filter(String::isNotEmpty)
    .joinToString(" ")

@Composable
internal fun StationField(
    label: String,
    value: String,
    language: Language,
    source: JourneySource,
    update: (String) -> Unit,
    search: () -> Unit,
    modifier: Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    var menuRequested by remember { mutableStateOf(false) }
    var remote by remember(value, source) { mutableStateOf(emptyList<StationSuggestion>()) }
    LaunchedEffect(value, menuRequested, source) {
        if (!source.demo && menuRequested && value.trim().length >= 1) {
            delay(300)
            remote = try {
                source.locations(value.trim()).map { StationSuggestion(it.name, kind = it.kind) }.distinct()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val suggestions = if (source.demo)
        stationSuggestions(value).filterNot { localizedStationName(it.name, language) == value.trim() }
    else remote
    var highlighted by remember(value) { mutableIntStateOf(-1) }
    val expanded = menuRequested && suggestions.isNotEmpty()
    fun choose(station: StationSuggestion) {
        update(localizedStationName(station.name, language))
        menuRequested = false
        highlighted = -1
    }
    fun submit() {
        val station = suggestions.getOrNull(highlighted).takeIf { expanded }
        if (station != null) choose(station) else {
            menuRequested = false
            search()
        }
    }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                update(it.take(120))
                menuRequested = true
                highlighted = -1
            },
            modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = label }
                .onFocusChanged { focus ->
                    if (!focus.isFocused) {
                        menuRequested = false
                        highlighted = -1
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        suggestions.isNotEmpty() && event.key == Key.DirectionDown -> {
                            menuRequested = true
                            highlighted = (highlighted + 1).coerceAtMost(suggestions.lastIndex)
                            true
                        }
                        expanded && event.key == Key.DirectionUp -> {
                            highlighted = (highlighted - 1).coerceAtLeast(0)
                            true
                        }
                        expanded && event.key == Key.Escape -> {
                            menuRequested = false
                            highlighted = -1
                            true
                        }
                        event.key == Key.Enter -> {
                            submit()
                            true
                        }
                        else -> false
                    }
                },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = trailing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() })
        )
        if (expanded) Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                Text(
                    language.text(if (source.demo) Copy.StationSuggestions else Copy.StopsAndAddresses),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                suggestions.forEachIndexed { index, station ->
                    val active = index == highlighted
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(localizedStationName(station.name, language))
                                val detail = station.address ?: when (station.kind) {
                                    "address" -> language.text(Copy.AddressWord)
                                    "stop" -> language.text(Copy.StopWord)
                                    else -> null
                                }
                                detail?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        onClick = { choose(station) },
                        modifier = Modifier
                            .background(if (active) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                            .semantics { selected = active }
                    )
                }
            }
        }
    }
}
