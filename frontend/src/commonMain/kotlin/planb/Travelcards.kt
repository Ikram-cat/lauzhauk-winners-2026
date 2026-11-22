package planb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class Travelcard(val label: Copy) {
    STANDARD(Copy.StandardFare),
    HALF_FARE(Copy.HalfFare),
    GA(Copy.GeneralTravelcard),
    NIGHT_GA(Copy.NightTravelcard),
    SWISS_TRAVEL_PASS(Copy.SwissTravelPass),
    DAY_PASS(Copy.DayPass),
    REGIONAL(Copy.RegionalTravelcard),
    CHILD(Copy.ChildTravelcard);

    val canQuote get() = this == STANDARD || this == HALF_FARE
}

internal data class FareProfile(val firstClass: Boolean = false, val travelcard: Travelcard = Travelcard.STANDARD) {
    // fares.catalogChf carries every class/discount combination regardless of what was requested.
    fun price(journey: Journey): Double? {
        val amount = when (travelcard) {
            Travelcard.STANDARD -> journey.catalogFare(firstClass, halfFare = false)
            Travelcard.HALF_FARE -> journey.catalogFare(firstClass, halfFare = true)
            else -> null
        }
        return amount?.takeIf { it.isFinite() && it >= 0.0 }
    }
}

@Composable
internal fun TravelcardsMenu(
    language: Language,
    profile: FareProfile,
    referenceJourney: Journey?,
    close: () -> Unit,
    apply: (FareProfile) -> Unit
) {
    val copy = language::text
    var draft by remember { mutableStateOf(profile) }
    val links = LocalUriHandler.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(12.dp)) {
                Text(copy(Copy.TravelcardsTitle), style = MaterialTheme.typography.titleMedium)
                Text(copy(Copy.TravelClass), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!draft.firstClass, { draft = draft.copy(firstClass = false) }, { Text(copy(Copy.SecondClass)) })
                    FilterChip(draft.firstClass, { draft = draft.copy(firstClass = true) }, { Text(copy(Copy.FirstClass)) })
                }
                if (referenceJourney != null) Text(copy(Copy.ReferenceFareHint), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                for (pass in Travelcard.entries) {
                    val selected = draft.travelcard == pass
                    val quote = referenceJourney?.let { draft.copy(travelcard = pass).price(it) }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) {
                            draft = draft.copy(travelcard = pass)
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        RadioButton(selected, onClick = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(copy(pass.label), style = MaterialTheme.typography.bodyLarge)
                            val price = when {
                                !pass.canQuote -> copy(Copy.CoverageUnverified)
                                quote != null -> chf(quote)
                                referenceJourney == null -> copy(Copy.SearchForFare)
                                else -> copy(Copy.PriceUnavailable)
                            }
                            Text(price, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(onClick = {
                    links.openUri("https://www.sbb.ch/en/tickets-offers/travelcards/area-validity-map.html")
                }) { Text(copy(Copy.CheckCoverage)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = close) { Text(copy(Copy.CancelTravelcard)) }
                    Button(onClick = { apply(draft) }) { Text(copy(Copy.ApplyTravelcard)) }
                }
            }
    }
}
