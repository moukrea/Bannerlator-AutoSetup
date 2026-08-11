package com.winlator.star.autosetup

import android.app.Activity
import android.content.Intent
import com.winlator.star.R
import com.winlator.star.XServerDisplayActivity
import com.winlator.star.communityconfigs.CommunityConfigApply
import com.winlator.star.communityconfigs.InstalledComponents
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.GPUInformation
import com.winlator.star.store.StarLaunchBridge
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.startContentDownload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import kotlin.coroutines.resume

object SteamAutoSetupCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(activity: Activity, request: SteamAutoSetupRequest, onStatus: (AutoSetupStatus) -> Unit, onResult: (AutoSetupResult) -> Unit) {
        val journal = AutoSetupJournal(activity.applicationContext)
        journal.read(request.gameKey)?.takeIf { !it.terminal && System.currentTimeMillis() - it.updatedAt < ACTIVE_SETUP_TTL_MS }?.let {
            onStatus(it); onResult(AutoSetupResult.Started(request.gameKey)); return
        }
        var status = AutoSetupStatus(request.gameKey, request.gameName, AutoSetupStage.QUEUED, activity.getString(R.string.auto_setup_queued)).also { journal.write(it); onStatus(it) }
        scope.launch {
            try {
                fun move(next: AutoSetupStage, detail: String) {
                    status = journal.transition(status, next, detail)
                    activity.runOnUiThread { onStatus(status) }
                }
                move(AutoSetupStage.RESOLVING_PROFILE, activity.getString(R.string.auto_setup_resolving_profile))
                val manager = ContainerManager(activity.applicationContext)
                findManagedShortcut(manager, request)?.let { existing ->
                    move(AutoSetupStage.LAUNCHING, activity.getString(R.string.auto_setup_reusing_environment))
                    move(AutoSetupStage.VALIDATING, activity.getString(R.string.auto_setup_waiting_for_frame))
                    launch(activity, existing, request.gameKey)
                    activity.runOnUiThread { onResult(AutoSetupResult.Started(request.gameKey)) }
                    return@launch
                }

                val communityProfile = CommunityProfileResolver.resolveSteam(activity, request.appId)
                val communityConfig = communityProfile?.config
                communityProfile?.let { profile ->
                    move(AutoSetupStage.RESOLVING_PROFILE, activity.getString(R.string.auto_setup_profile_match,
                        profile.source.name.lowercase().replaceFirstChar { it.uppercase() }, profile.confidence))
                }
                val contents = ContentsManager(activity.applicationContext)
                val catalog = loadCatalog(contents)
                move(AutoSetupStage.INSTALLING_COMPONENTS, activity.getString(R.string.auto_setup_preparing_layers))
                val runtime = requireProfile(activity.applicationContext, contents,
                    AutoSetupPolicy.choose(catalog, ContentProfile.ContentType.CONTENT_TYPE_PROTON, communityConfig?.advisories?.get("wineVersion")),
                    activity.getString(R.string.auto_setup_no_proton)) { move(AutoSetupStage.INSTALLING_COMPONENTS, it) }

                val arm64Ec = runtime.verName.contains("arm64ec", ignoreCase = true)
                val required = ArrayList<ContentProfile>()
                val translatorType = if (arm64Ec) ContentProfile.ContentType.CONTENT_TYPE_FEXCORE else ContentProfile.ContentType.CONTENT_TYPE_BOX64
                val translatorLabel = if (arm64Ec) "FEXCore" else "Box64"
                val wantedTranslator = communityConfig?.components?.firstOrNull { it.type.equals(translatorLabel, true) }?.target
                AutoSetupPolicy.choose(catalog, translatorType, wantedTranslator)?.let(required::add)
                for ((label, type) in listOf("DXVK" to ContentProfile.ContentType.CONTENT_TYPE_DXVK, "VKD3D" to ContentProfile.ContentType.CONTENT_TYPE_VKD3D)) {
                    val wanted = communityConfig?.components?.firstOrNull { it.type.equals(label, true) }?.target
                    AutoSetupPolicy.choose(catalog, type, wanted)?.let(required::add)
                }
                for (profile in required.distinctBy { ContentsManager.getEntryName(it) }) ensureInstalled(activity.applicationContext, contents, profile) {
                    move(AutoSetupStage.INSTALLING_COMPONENTS, it)
                }
                contents.syncContents()

                move(AutoSetupStage.CREATING_ENVIRONMENT, activity.getString(R.string.auto_setup_creating_environment))
                val translator = required.firstOrNull { it.type == translatorType }
                val containerData = AutoContainerFactory.build(activity.applicationContext, manager, contents, request.gameKey, request.gameName,
                    ContentsManager.getEntryName(runtime), translator?.takeIf { arm64Ec }?.let(::componentToken),
                    translator?.takeIf { !arm64Ec }?.let(::componentToken),
                    required.firstOrNull { it.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK }?.let(::componentToken),
                    required.firstOrNull { it.type == ContentProfile.ContentType.CONTENT_TYPE_VKD3D }?.let(::componentToken))
                val container = createContainer(manager, contents, containerData) ?: error(activity.getString(R.string.auto_setup_environment_failed))

                move(AutoSetupStage.APPLYING_PROFILE, activity.getString(R.string.auto_setup_creating_launcher))
                writeShortcut(activity, container, request)
                val shortcut = findCreatedShortcut(manager, container, request) ?: error(activity.getString(R.string.auto_setup_shortcut_failed))
                shortcut.putExtra("autoSetupManaged", "1"); shortcut.putExtra("autoSetupGameKey", request.gameKey); shortcut.putExtra("steamLaunchMode", "auto"); shortcut.saveData()
                if (communityConfig != null) {
                    val applied = CommunityConfigApply.apply(shortcut, communityConfig, InstalledComponents.read(activity.applicationContext), container.wineVersion, GPUInformation.isAdrenoGPU(activity.applicationContext))
                    if (!applied.ok) error(applied.message)
                }
                move(AutoSetupStage.LAUNCHING, activity.getString(R.string.auto_setup_starting_game))
                move(AutoSetupStage.VALIDATING, activity.getString(R.string.auto_setup_waiting_for_frame))
                launch(activity, shortcut, request.gameKey)
                activity.runOnUiThread { onResult(AutoSetupResult.Started(request.gameKey)) }
            } catch (t: Throwable) {
                val failed = runCatching { journal.transition(status, AutoSetupStage.FAILED, t.message ?: t.javaClass.simpleName) }
                    .getOrElse { status.copy(stage = AutoSetupStage.FAILED, detail = t.message ?: t.javaClass.simpleName).also(journal::write) }
                activity.runOnUiThread { onStatus(failed); onResult(AutoSetupResult.Failed(request.gameKey, failed.detail)) }
            }
        }
    }

    private fun findManagedShortcut(manager: ContainerManager, request: SteamAutoSetupRequest) = manager.loadShortcuts().firstOrNull {
        it.getExtra("autoSetupGameKey") == request.gameKey && it.getExtra("autoSetupManaged") == "1"
    }
    private fun findCreatedShortcut(manager: ContainerManager, container: Container, request: SteamAutoSetupRequest) = manager.loadShortcuts().firstOrNull {
        it.container.id == container.id && it.getExtra("steamAppId") == request.appId.toString()
    }
    private fun loadCatalog(contents: ContentsManager): List<ContentProfile> {
        val json = Downloader.downloadString(ContentsManager.REMOTE_PROFILES) ?: error("Compatibility-layer catalog is unavailable")
        contents.setRemoteProfiles(json)
        return ContentProfile.ContentType.values().flatMap { contents.getProfiles(it).orEmpty() }
    }
    private suspend fun requireProfile(context: android.content.Context, contents: ContentsManager, profile: ContentProfile?, error: String, move: (String) -> Unit): ContentProfile {
        val selected = profile ?: error(error); ensureInstalled(context, contents, selected, move); return selected
    }
    private suspend fun ensureInstalled(context: android.content.Context, contents: ContentsManager, profile: ContentProfile, onProgress: (String) -> Unit) {
        val key = ContentsManager.getEntryName(profile); contents.syncContents()
        if (AutoSetupPolicy.isInstalled(contents.getProfileByEntryName(key))) return
        profile.remoteUrl ?: error(context.getString(R.string.auto_setup_missing_download, profile.verName))
        startContentDownload(context.applicationContext, profile)
        val result = ContentDownloadRegistry.states.map { it[key] }.filterNotNull().onEach { state ->
            val verb = context.getString(if (state.phase == ContentDownloadPhase.INSTALLING) R.string.auto_setup_installing else R.string.auto_setup_downloading)
            onProgress(context.getString(R.string.auto_setup_component_progress, verb, profile.verName, (state.fraction * 100).toInt()))
        }.first { it.terminal }
        if (result.phase == ContentDownloadPhase.ERROR) error(result.error ?: context.getString(R.string.auto_setup_install_failed, profile.verName))
        contents.syncContents()
        if (!AutoSetupPolicy.isInstalled(contents.getProfileByEntryName(key))) error(context.getString(R.string.auto_setup_installed_unusable, profile.verName))
    }
    private suspend fun createContainer(manager: ContainerManager, contents: ContentsManager, data: JSONObject): Container? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> manager.createContainerAsync(data, contents) { if (c.isActive) c.resume(it) } }
    }
    private suspend fun writeShortcut(activity: Activity, container: Container, request: SteamAutoSetupRequest) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> StarLaunchBridge.writeShortcutAsync(activity, container, request.gameName, request.executablePath, request.coverUrl, request.appId) { success, message ->
            if (!c.isActive) return@writeShortcutAsync
            if (success) c.resume(Unit) else c.resumeWith(Result.failure(IllegalStateException(message)))
        } }
    }
    private fun componentToken(profile: ContentProfile) = ContentsManager.getEntryName(profile).substringAfter('-')
    private suspend fun launch(activity: Activity, shortcut: Shortcut, gameKey: String) = withContext(Dispatchers.Main) {
        activity.startActivity(Intent(activity, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", shortcut.container.id); putExtra("shortcut_path", shortcut.file.path); putExtra("shortcut_name", shortcut.name)
            putExtra("disableXinput", shortcut.getExtra("disableXinput", "0")); putExtra(AutoSetupRuntimeSignals.EXTRA_GAME_KEY, gameKey)
        })
    }
    private const val ACTIVE_SETUP_TTL_MS = 15L * 60L * 1000L
}
