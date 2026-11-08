package dev.irshadm.volta.feature.onboarding

import dev.irshadm.volta.core.domain.PinCheck
import dev.irshadm.volta.core.testing.FakeControlPinRepository
import dev.irshadm.volta.core.testing.MainDispatcherRule
import dev.irshadm.volta.feature.onboarding.pin.ControlPinViewModel
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlPinViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pins = FakeControlPinRepository()
    private val vm = ControlPinViewModel(pins)

    @Test fun `keeps only four digits`() {
        vm.onPinChange("12a345")
        assertEquals("1234", vm.state.value.pin)
    }

    @Test fun `nothing is checked until four digits are in`() {
        vm.onPinChange("123")
        vm.check()
        assertTrue(pins.checked.isEmpty())
    }

    @Test fun `an accepted PIN is saved and carries on`() {
        var carriedOn = false
        vm.onPinChange("1234")
        vm.check { carriedOn = true }
        assertEquals(listOf("1234"), pins.checked)
        assertTrue(carriedOn)
        assertTrue(pins.hasPin.value)
        assertEquals("", vm.state.value.pin)
    }

    @Test fun `a wrong PIN clears the boxes and is not retried`() {
        pins.nextResult = PinCheck.Wrong
        var carriedOn = false
        vm.onPinChange("1234")
        vm.check { carriedOn = true }
        assertEquals(PinCheck.Wrong, vm.state.value.problem)
        assertEquals("", vm.state.value.pin)
        assertEquals(1, pins.checked.size)
        assertFalse(carriedOn)
        assertFalse(pins.hasPin.value)
    }

    @Test fun `typing again clears the problem`() {
        pins.nextResult = PinCheck.Offline
        vm.onPinChange("1234")
        vm.check()
        assertEquals(PinCheck.Offline, vm.state.value.problem)
        assertEquals("1234", vm.state.value.pin)
        vm.onPinChange("123")
        assertNull(vm.state.value.problem)
    }

    @Test fun `skipping finishes the step without a PIN`() {
        vm.skip()
        assertTrue(pins.stepDone.value)
        assertFalse(pins.hasPin.value)
    }
}
