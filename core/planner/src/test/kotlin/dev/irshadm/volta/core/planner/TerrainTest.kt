package dev.irshadm.volta.core.planner

import kotlin.test.Test
import kotlin.test.assertEquals

class TerrainTest {
    @Test
    fun noiseIsIgnoredAndRealClimbsCount() {
        val flatNoisy = (0..500).map { if (it % 2 == 0) 10.0 else 18.0 }
        assertEquals(0.0, Terrain.ascentM(flatNoisy))
        val hill = (0..100).map { it * 10.0 } + (100 downTo 0).map { it * 10.0 } + (0..50).map { it * 10.0 }
        assertEquals(1500.0, Terrain.ascentM(hill))
    }
}
