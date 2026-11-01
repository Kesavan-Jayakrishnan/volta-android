package dev.irshadm.volta.ui.plan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanUiStateTest {
    @Test fun `no key asks for one before planning`() {
        assertTrue(PlanUiState(hasMapsKey = false).needsMapsKey)
    }

    @Test fun `no key still asks after a failed search`() {
        assertTrue(PlanUiState(hasMapsKey = false, phase = PlanPhase.Failed("No Google Maps key")).needsMapsKey)
    }

    @Test fun `planning in progress is left alone`() {
        assertFalse(PlanUiState(hasMapsKey = false, phase = PlanPhase.Working("Finding routes")).needsMapsKey)
    }

    @Test fun `a saved key shows the form`() {
        assertFalse(PlanUiState(hasMapsKey = true).needsMapsKey)
    }
}
