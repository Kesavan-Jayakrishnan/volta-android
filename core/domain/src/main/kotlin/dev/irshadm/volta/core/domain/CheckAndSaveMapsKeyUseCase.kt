package dev.irshadm.volta.core.domain

import dev.irshadm.volta.core.maps.KeyCheckReport
import dev.irshadm.volta.core.maps.MapsKeyChecker
import javax.inject.Inject

/**
 * Tries a key with Google and saves it only if every API the app needs accepted it. A key that fails is never
 * stored, so the app can't end up holding a key that silently breaks trip planning.
 */
class CheckAndSaveMapsKeyUseCase @Inject constructor(
    private val checker: MapsKeyChecker,
    private val keys: MapsKeyRepository,
) {
    suspend operator fun invoke(key: String): KeyCheckReport {
        val trimmed = key.trim()
        val report = checker.check(trimmed)
        if (report.passed) keys.save(trimmed)
        return report
    }
}
