package dev.irshadm.volta.core.model

/** Planner buffers and polling. */
data class TripSettings(
    val minArrivalAtChargerPercent: Int = 10,
    val minArrivalAtDestinationPercent: Int = 15,
    val maxChargeTargetPercent: Int = 80,
    val maxDetourKm: Int = 5,
    val tripPollIntervalMinutes: Int = 5,
    val replanDeviationPercent: Int = 5,
) {
    fun validate(): List<String> = buildList {
        if (minArrivalAtChargerPercent !in 0..50) add("Charger arrival buffer must be 0–50 %")
        if (minArrivalAtDestinationPercent !in 0..80) add("Destination buffer must be 0–80 %")
        if (maxChargeTargetPercent !in 50..100) add("Charge target must be 50–100 %")
        if (maxChargeTargetPercent <= minArrivalAtChargerPercent) add("Charge target must exceed the charger arrival buffer")
        if (maxDetourKm !in 0..50) add("Max detour must be 0–50 km")
        if (tripPollIntervalMinutes < MIN_POLL_MINUTES) add("Polling must be at least $MIN_POLL_MINUTES minutes")
        if (replanDeviationPercent !in 1..30) add("Re-plan threshold must be 1–30 %")
    }

    companion object {
        const val MIN_POLL_MINUTES = 5
    }
}

enum class NetworkPreference { OFF, PREFER, ONLY }

/** Charger filters. DC CCS2 only is fixed for v1. */
data class ChargerPreferences(
    val networkPreference: NetworkPreference = NetworkPreference.PREFER,
    val preferredNetworks: Set<String> = emptySet(),
    /** Never planned as a stop, whatever the preference mode. */
    val avoidedNetworks: Set<String> = setOf("KSEB"),
) {
    val dcCcs2Only: Boolean get() = true

    companion object {
        val KNOWN_NETWORKS = listOf(
            "Tata Power", "Statiq", "ChargeZone", "Jio-bp", "MG eHub", "Zeon", "Relux", "GLIDA",
            "GOEC", "chargeMOD", "Iongrid", "KSEB", "EESL", "ANERT",
        )

        /** The network named in a charger's name ("GO EC Charging Station" → GOEC), ignoring case and spaces. */
        fun networkOf(name: String): String? = name.replace(" ", "").let { n -> KNOWN_NETWORKS.firstOrNull { n.contains(it.replace(" ", ""), ignoreCase = true) } }
    }
}
