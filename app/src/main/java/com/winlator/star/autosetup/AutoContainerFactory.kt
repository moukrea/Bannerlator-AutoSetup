package com.winlator.star.autosetup

import android.content.Context
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.core.DefaultVersion
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.NewContainerDefaults
import com.winlator.star.core.WineInfo
import com.winlator.star.contents.ContentsManager
import org.json.JSONObject

object AutoContainerFactory {
    fun build(
        context: Context, manager: ContainerManager, contentsManager: ContentsManager,
        gameKey: String, gameName: String, wineVersion: String, fexVersion: String?,
        boxVersion: String?, dxvkVersion: String?, vkd3dVersion: String?,
    ): JSONObject {
        val arm64Ec = WineInfo.fromIdentifier(context, contentsManager, wineVersion).isArm64EC
        val arch = if (arm64Ec) NewContainerDefaults.ARCH_ARM64EC else NewContainerDefaults.ARCH_X86_64
        val template = Container(0, manager)
        NewContainerDefaults.load(context, arch)?.let { raw -> runCatching { template.loadData(JSONObject(raw)) } }
        template.setName(context.getString(R.string.auto_setup_managed_container, gameName))
        template.setScreenSize(template.getScreenSize() ?: Container.DEFAULT_SCREEN_SIZE)
        template.setEnvVars(template.getEnvVars() ?: Container.DEFAULT_ENV_VARS)
        template.setCPUList(template.getCPUList() ?: Container.getFallbackCPUList())
        template.setCPUListWoW64(template.getCPUListWoW64() ?: Container.getFallbackCPUListWoW64())
        template.setDrives(template.getDrives() ?: Container.DEFAULT_DRIVES)
        template.setWineVersion(wineVersion)
        template.setEmulator(if (arm64Ec) "fexcore" else "box64")
        template.setFEXCoreVersion(fexVersion ?: DefaultVersion.FEXCORE)
        template.setBox64Version(boxVersion ?: if (arm64Ec) DefaultVersion.WOWBOX64 else DefaultVersion.BOX64)
        if (dxvkVersion != null || vkd3dVersion != null) {
            template.setDXWrapper(Container.DEFAULT_DXWRAPPER)
            template.setDXWrapperConfig(mergeCsv(template.getDXWrapperConfig().ifBlank { Container.DEFAULT_DXWRAPPERCONFIG }, buildMap {
                dxvkVersion?.let { put("version", it) }; vkd3dVersion?.let { put("vkd3dVersion", it) }
            }))
        }
        val adreno = GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, context)
        template.setGraphicsDriver(if (adreno) Container.DEFAULT_GRAPHICS_DRIVER else Container.GRAPHICS_DRIVER_GAMENATIVE)
        if (template.getGraphicsDriverConfig().contains("version=;")) {
            val driver = if (adreno) DefaultVersion.WRAPPER_ADRENO else DefaultVersion.WRAPPER
            template.setGraphicsDriverConfig(template.getGraphicsDriverConfig().replace("version=;", "version=$driver;"))
        }
        template.putExtra("autoSetupManaged", "1"); template.putExtra("autoSetupGameKey", gameKey); template.putExtra("autoSetupSchema", "1")
        return template.getData()
    }

    private fun mergeCsv(raw: String, updates: Map<String, String>): String {
        val values = LinkedHashMap<String, String>()
        raw.split(',').forEach { part -> val i = part.indexOf('='); if (i > 0) values[part.substring(0, i)] = part.substring(i + 1) }
        values.putAll(updates)
        return values.entries.joinToString(",") { (key, value) -> "$key=$value" }
    }
}
