package dev.irshadm.volta.feature.onboarding

import dev.irshadm.volta.core.domain.CheckAndSaveMapsKeyUseCase
import dev.irshadm.volta.core.maps.AppIdentity
import dev.irshadm.volta.core.testing.FakeMapsKeyChecker
import dev.irshadm.volta.core.testing.FakeMapsKeyRepository
import dev.irshadm.volta.core.testing.FakeSetupRepository
import dev.irshadm.volta.core.testing.MainDispatcherRule
import dev.irshadm.volta.core.testing.TEST_KEY
import dev.irshadm.volta.core.testing.failingReport
import dev.irshadm.volta.core.testing.passingReport
import dev.irshadm.volta.feature.onboarding.mapskey.KeyPhase
import dev.irshadm.volta.feature.onboarding.mapskey.MapsKeyViewModel
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapsKeyViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val keys = FakeMapsKeyRepository()
    private val setup = FakeSetupRepository()
    private val checker = FakeMapsKeyChecker(passingReport())
    private val vm by lazy { MapsKeyViewModel(CheckAndSaveMapsKeyUseCase(checker, keys), keys, setup, AppIdentity("p", "00")) }

    @Test fun `badly shaped key is caught without calling Google`() {
        vm.onInputChange("not-a-key")
        vm.check()
        assertEquals(KeyPhase.BadFormat, vm.state.value.phase)
        assertTrue(checker.checked.isEmpty())
    }

    @Test fun `passing key is saved but first run waits for Open Volta`() {
        vm.onInputChange(TEST_KEY)
        vm.check()
        assertTrue(vm.state.value.passed)
        assertEquals(TEST_KEY, keys.key.value)
        assertFalse(setup.mapsKeyStepDone.value, "the success screen stays until the user taps Open Volta")
        vm.finishFirstRun()
        assertTrue(setup.mapsKeyStepDone.value)
    }

    @Test fun `failing key is reported and not saved`() {
        checker.report = failingReport()
        vm.onInputChange(TEST_KEY)
        vm.check()
        assertIs<KeyPhase.Checked>(vm.state.value.phase)
        assertFalse(vm.state.value.passed)
        assertNull(keys.key.value)
    }

    @Test fun `editing after a check starts over`() {
        checker.report = failingReport()
        vm.onInputChange(TEST_KEY)
        vm.check()
        vm.onInputChange("$TEST_KEY ")
        assertEquals(KeyPhase.Editing, vm.state.value.phase)
    }

    @Test fun `skip finishes first run without a key`() {
        vm.finishFirstRun()
        assertTrue(setup.mapsKeyStepDone.value)
        assertNull(keys.key.value)
    }

    @Test fun `remove clears the saved key`() {
        keys.save(TEST_KEY)
        vm.removeKey()
        assertNull(keys.key.value)
        assertNull(vm.state.value.savedKey)
    }
}
