package dev.irshadm.volta.core.domain

import dev.irshadm.volta.core.testing.FakeMapsKeyChecker
import dev.irshadm.volta.core.testing.FakeMapsKeyRepository
import dev.irshadm.volta.core.testing.TEST_KEY
import dev.irshadm.volta.core.testing.failingReport
import dev.irshadm.volta.core.testing.passingReport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckAndSaveMapsKeyUseCaseTest {
    private val keys = FakeMapsKeyRepository()

    @Test fun `a passing key is trimmed and saved`() = runTest {
        val checker = FakeMapsKeyChecker(passingReport())
        CheckAndSaveMapsKeyUseCase(checker, keys)(" $TEST_KEY\n")
        assertEquals(listOf(TEST_KEY), checker.checked)
        assertEquals(TEST_KEY, keys.key.value)
    }

    @Test fun `a failing key is never saved, and the old key stays`() = runTest {
        keys.save("OLD")
        CheckAndSaveMapsKeyUseCase(FakeMapsKeyChecker(failingReport()), keys)(TEST_KEY)
        assertEquals("OLD", keys.key.value)
    }

    @Test fun `nothing saved on first failure`() = runTest {
        CheckAndSaveMapsKeyUseCase(FakeMapsKeyChecker(failingReport()), FakeMapsKeyRepository().also { assertNull(it.key.value) })(TEST_KEY)
    }
}
