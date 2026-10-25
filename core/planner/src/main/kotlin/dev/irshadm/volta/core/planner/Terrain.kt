package dev.irshadm.volta.core.planner

object Terrain {
    /**
     * Total climbing along [elevations], ignoring wiggles smaller than [thresholdM] (elevation samples
     * every 200 m pick up bridges, cuttings and data noise that a plain sum turns into hundreds of metres).
     */
    fun ascentM(elevations: List<Double>, thresholdM: Double = 20.0): Double {
        if (elevations.isEmpty()) return 0.0
        var total = 0.0
        var low = elevations.first()
        var high = low
        var rising = false
        for (e in elevations) {
            if (rising) {
                if (e > high) high = e
                else if (high - e >= thresholdM) { total += high - low; low = e; high = e; rising = false }
            } else {
                if (e < low) { low = e; high = e }
                else if (e - low >= thresholdM) { high = e; rising = true }
            }
        }
        if (rising) total += high - low
        return total
    }
}
