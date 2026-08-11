package com.winlator.star.autosetup

import android.content.Context
import com.winlator.star.communityconfigs.*

enum class CommunityProfileSource { BANNERLATOR }
data class ResolvedCommunityProfile(val source: CommunityProfileSource, val config: ShortcutConfig, val confidence: Int)
internal interface CommunityProfileProvider { fun resolveSteam(context: Context, appId: Int): ResolvedCommunityProfile? }

object CommunityProfileResolver {
    private val providers: List<CommunityProfileProvider> = listOf(BannerlatorProfileProvider)
    fun resolveSteam(context: Context, appId: Int): ResolvedCommunityProfile? =
        providers.mapNotNull { runCatching { it.resolveSteam(context, appId) }.getOrNull() }.maxByOrNull { it.confidence }
}

private object BannerlatorProfileProvider : CommunityProfileProvider {
    override fun resolveSteam(context: Context, appId: Int): ResolvedCommunityProfile? {
        val game = CommunityConfigRepository(context).getGames().firstOrNull { it.steamAppId == appId.toString() } ?: return null
        val userSoc = DeviceIdentity.soc(); val userGpu = DeviceIdentity.gpu(context)
        val device = GameMatcher.rankDevices(game.devices, userSoc, userGpu).firstOrNull() ?: return null
        val fetched = CommunityConfigFetcher.fetchForDevice(game, device) ?: return null
        return ResolvedCommunityProfile(CommunityProfileSource.BANNERLATOR, ConfigTranslator.translate(fetched.json), if (GameMatcher.deviceMatchesUser(device, userSoc, userGpu)) 100 else 60)
    }
}
