package dev.irshadm.volta.core.testing

import dev.irshadm.volta.core.maps.ApiCheck
import dev.irshadm.volta.core.maps.KeyCheckReport
import dev.irshadm.volta.core.maps.KeyProblem
import dev.irshadm.volta.core.maps.MapsApi

/** A well-formed key that is obviously not real. */
const val TEST_KEY = "AIzaTEST0000000000000000000000000000000"

fun passingReport() = KeyCheckReport(MapsApi.entries.associateWith { ApiCheck.Passed })

fun failingReport(api: MapsApi = MapsApi.Routes, problem: KeyProblem = KeyProblem.ApiNotEnabled) =
    KeyCheckReport(MapsApi.entries.associateWith { if (it == api) ApiCheck.Failed(problem, "from Google") else ApiCheck.Passed })
