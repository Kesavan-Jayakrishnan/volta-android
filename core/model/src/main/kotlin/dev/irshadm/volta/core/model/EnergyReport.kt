package dev.irshadm.volta.core.model

/** One charging session's energy, as stored. */
data class SessionEnergy(
    val category: ChargeCategory,
    val batteryKwh: Double,
    val gridUnits: Double? = null,     // actual units the user entered
    val costRs: Double? = null,        // actual cost the user entered
)

data class EnergyReport(
    val kwh: Map<ChargeCategory, Double>,
    val sessions: Map<ChargeCategory, Int>,
    val homeUnits: Double,
    val publicCost: Map<ChargeCategory, Double>,
    val homeCost: Double,
    val billWithoutEv: Double,
    val billWithEv: Double,
    val crossesLimit: Boolean,
    val headroomPerMonth: Double,
    val costPerHomeUnit: Double,
    val km: Double?,
) {
    val totalKwh: Double get() = kwh.values.sum()
    val totalCost: Double get() = homeCost + publicCost.values.sum()
    val costPerKm: Double? get() = km?.takeIf { it > 1 }?.let { totalCost / it }

    companion object {
        /**
         * [periodMonths] is the length of the reported period in months (e.g. 1.0, 2.0, 0.85 for 26 days).
         * Home cost = the period's share of (bill with EV − bill without EV) for a whole billing cycle.
         */
        fun compute(sessions: List<SessionEnergy>, s: EnergySettings, tariff: Tariff, periodMonths: Double, km: Double?): EnergyReport {
            val kwh = ChargeCategory.entries.associateWith { c -> sessions.filter { it.category == c }.sumOf { it.batteryKwh } }
            val count = ChargeCategory.entries.associateWith { c -> sessions.count { it.category == c } }
            val homeUnits = sessions.filter { it.category == ChargeCategory.HOME }.sumOf { it.gridUnits ?: (it.batteryKwh / s.homeEfficiency) }
            val publicCost = mapOf(
                ChargeCategory.PUBLIC_AC to sessions.filter { it.category == ChargeCategory.PUBLIC_AC }.sumOf { it.costRs ?: (it.batteryKwh / s.publicAcEfficiency * s.publicAcRatePerKwh) },
                ChargeCategory.PUBLIC_DC to sessions.filter { it.category == ChargeCategory.PUBLIC_DC }.sumOf { it.costRs ?: (it.batteryKwh / s.dcEfficiency * s.dcRatePerKwh) },
            )
            val months = periodMonths.coerceAtLeast(0.1)
            val evUnitsPerCycle = homeUnits / months * tariff.billingMonths
            val without = tariff.billForCycle(s.householdUnitsPerCycle)
            val with = tariff.billForCycle(s.householdUnitsPerCycle + evUnitsPerCycle)
            val cycleCost = (with - without).coerceAtLeast(0.0)
            val homeCost = cycleCost * months / tariff.billingMonths
            val totalPerCycle = s.householdUnitsPerCycle + evUnitsPerCycle
            return EnergyReport(
                kwh = kwh, sessions = count, homeUnits = homeUnits, publicCost = publicCost, homeCost = homeCost,
                billWithoutEv = without, billWithEv = with,
                crossesLimit = tariff.headroomPerMonth(totalPerCycle) < 0 && tariff.headroomPerMonth(s.householdUnitsPerCycle) >= 0,
                headroomPerMonth = tariff.headroomPerMonth(totalPerCycle),
                costPerHomeUnit = if (evUnitsPerCycle > 0) cycleCost / evUnitsPerCycle else 0.0,
                km = km,
            )
        }
    }
}
