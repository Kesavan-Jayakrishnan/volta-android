package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.ismart.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals

class CarIdentityTest {
    private fun identity(model: String?, series: String?) = CarIdentity.of(Vehicle("VIN", series, model, null, false))

    @Test fun `known models match the name however MG words it`() {
        assertEquals(CarModel.Windsor, CarIdentity("Windsor Pro").model)
        assertEquals(CarModel.Comet, CarIdentity("COMET EV EXCLUSIVE FC").model)
        assertEquals(CarModel.ZsEv, CarIdentity("ZS EV EXCLUSIVE").model)
        assertEquals(CarModel.M9, CarIdentity("M9").model)
        assertEquals(CarModel.Cyberster, CarIdentity("Cyberster").model)
        assertEquals(CarModel.Tomahawk, CarIdentity("Hector Tomahawk EV").model)
    }

    @Test fun `the trim is dropped from the name`() {
        assertEquals(CarIdentity("Comet EV"), identity("COMET EV EXCLUSIVE FC", "E230"))
        assertEquals(CarIdentity("Windsor"), identity("Windsor", "EQ100"))
    }

    @Test fun `a series code alone is enough`() {
        assertEquals(CarIdentity("Comet EV"), identity(null, "EC32"))
        assertEquals(CarIdentity("Windsor"), identity(" ", "EQ100"))
    }

    @Test fun `the petrol ZS Astor is not the ZS EV`() {
        assertEquals(CarModel.Other, identity("ZS ASTOR VTi-TECH CVT SAVVY RED", "New ZS").model)
    }

    @Test fun `anything else keeps its name without the brand`() {
        assertEquals(CarIdentity("Hector"), identity("MG Hector", null))
        assertEquals(CarModel.Other, CarIdentity("Hector").model)
        assertEquals(CarIdentity.Unknown, identity(null, null))
        assertEquals(CarModel.Other, CarIdentity.Unknown.model)
    }
}
