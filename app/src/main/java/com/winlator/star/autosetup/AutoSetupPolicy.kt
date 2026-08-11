package com.winlator.star.autosetup

import com.winlator.star.communityconfigs.ConfigTranslator
import com.winlator.star.contents.ContentProfile

object AutoSetupPolicy {
    fun isInstalled(profile: ContentProfile?): Boolean = profile != null && profile.remoteUrl.isNullOrBlank()

    private val safeHints = mapOf(
        ContentProfile.ContentType.CONTENT_TYPE_PROTON to listOf("proton-10.0-4-arm64ec-unixlibs-sdk28", "ge-proton-11.0-3-arm64ec-sdk28", "proton-10.0-arm64ec"),
        ContentProfile.ContentType.CONTENT_TYPE_FEXCORE to listOf("fexcore-2608-stable-unix", "fexcore-2608-stable", "fexcore-2607-ppa-unix"),
        ContentProfile.ContentType.CONTENT_TYPE_DXVK to listOf("dxvk-gplasync-arm64ec-2.7.1-1", "dxvk-arm64ec-2.7.1", "dxvk-arm64ec-2.6.2"),
        ContentProfile.ContentType.CONTENT_TYPE_VKD3D to listOf("vkd3d-proton-arm64ec-3.0.1", "vkd3d-proton-arm64ec-2.14.1"),
        ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 to listOf("wowbox64-0.4.4", "wowbox64-0.4.3"),
        ContentProfile.ContentType.CONTENT_TYPE_BOX64 to listOf("box64-0.4.4-bionic", "box64-0.4.4", "box64-0.4.3"),
    )

    fun choose(profiles: List<ContentProfile>, type: ContentProfile.ContentType, wanted: String? = null): ContentProfile? {
        val candidates = profiles.filter { it.type == type }
        if (candidates.isEmpty()) return null
        if (!wanted.isNullOrBlank()) {
            if (type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                val wantedKey = ConfigTranslator.protonKey(wanted)
                candidates.firstOrNull { ConfigTranslator.protonKey(it.verName) == wantedKey }?.let { return it }
            }
            val wantedNorm = normalize(wanted)
            candidates.maxByOrNull { candidateScore(normalize(it.verName), wantedNorm) }
                ?.takeIf { candidateScore(normalize(it.verName), wantedNorm) > 0 }?.let { return it }
        }
        for (hint in safeHints[type].orEmpty()) {
            candidates.firstOrNull { normalize(it.verName) == normalize(hint) }?.let { return it }
        }
        return candidates.last()
    }

    private fun candidateScore(candidate: String, wanted: String): Int {
        if (candidate == wanted) return 10_000
        if (candidate.contains(wanted) || wanted.contains(candidate)) return 5_000
        val wantedNumbers = Regex("[0-9]+").findAll(wanted).map { it.value }.toList()
        val candidateNumbers = Regex("[0-9]+").findAll(candidate).map { it.value }.toSet()
        return wantedNumbers.count { it in candidateNumbers } * 100
    }

    private fun normalize(raw: String) = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
