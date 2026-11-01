package dev.irshadm.volta.feature.onboarding.mapskey

import dev.irshadm.volta.core.maps.KeyProblem
import dev.irshadm.volta.core.maps.MapsApi

/** Short status for the per-API list. */
internal fun KeyProblem.shortLabel(): String = when (this) {
    KeyProblem.InvalidKey -> "Key not valid"
    KeyProblem.ApiNotEnabled -> "Not enabled"
    KeyProblem.ApiNotAllowedForKey -> "Blocked by key"
    KeyProblem.AppNotAllowed -> "App not allowed"
    KeyProblem.BillingDisabled -> "No billing"
    KeyProblem.QuotaExceeded -> "Quota used up"
    KeyProblem.Network -> "No connection"
    KeyProblem.Other -> "Failed"
}

/** What happened, and the exact steps to fix it in Google Cloud Console. */
internal fun KeyProblem.explain(api: MapsApi, packageName: String, sha1: String): Pair<String, String> = when (this) {
    KeyProblem.InvalidKey -> "Google doesn't recognise this key." to
        "Copy it again from Google Cloud → APIs & Services → Credentials. It starts with AIza."
    KeyProblem.ApiNotEnabled -> "${api.label} isn't enabled for this key's project." to
        "Google Cloud → APIs & Services → Library → ${api.label} → Enable, then check again. It can take a few minutes to apply."
    KeyProblem.ApiNotAllowedForKey -> "This key's API restrictions leave out ${api.label}." to
        "Credentials → your key → API restrictions → tick ${api.label} → Save."
    KeyProblem.AppNotAllowed -> "The key is restricted to other Android apps." to
        "Credentials → your key → Android apps → add package $packageName with SHA-1 $sha1."
    KeyProblem.BillingDisabled -> "The project has no billing account." to
        "Google Cloud → Billing → link a billing account. Google Maps Platform needs one even within the free usage."
    KeyProblem.QuotaExceeded -> "This key has used up its quota for now." to
        "Wait a while, or raise the daily limit under APIs & Services → ${api.label} → Quotas."
    KeyProblem.Network -> "Couldn't reach Google." to "Check your connection and try again."
    KeyProblem.Other -> "Google refused the request." to "The details below are Google's own message."
}
