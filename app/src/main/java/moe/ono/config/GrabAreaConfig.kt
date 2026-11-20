package moe.ono.config

import moe.ono.constants.Constants.PrekClickableXXX
import moe.ono.constants.Constants.PrekXXX
import moe.ono.hooks._base.BaseHookItem
import moe.ono.hooks._base.BaseClickableFunctionHookItem
import moe.ono.hooks._base.BaseSwitchFunctionHookItem

/**
 * One master gate for TNT's grab-area hooks.
 *
 * Legacy "黑壳区/*" settings are migrated to "抓取区/*" once. Per-feature preferences
 * remain stored even when the master switch is off, so users can temporarily disable
 * the whole section without losing their individual choices.
 */
object GrabAreaConfig {
    const val CATEGORY = "抓取区"
    const val MASTER_KEY = "tnt_grab_area_enabled"
    private const val LEGACY_CATEGORY = "黑壳区"

    @JvmStatic
    fun isGrabAreaPath(path: String): Boolean = path.startsWith("$CATEGORY/")

    /**
     * Migrate old feature keys and return whether the master gate is enabled.
     * A first upgrade with any previous black-shell setting enables the new section
     * to preserve the user's existing setup. A fresh install defaults to off.
     */
    @JvmStatic
    fun migrateLegacySettings(items: List<BaseHookItem>): Boolean {
        val config = ConfigManager.getDefaultConfig()
        var foundLegacySetting = false

        fun migrate(oldKey: String, newKey: String) {
            if (config.containsKey(oldKey)) {
                foundLegacySetting = true
                if (!config.containsKey(newKey)) {
                    config.edit().putBoolean(newKey, config.getBooleanOrFalse(oldKey)).apply()
                }
            }
        }

        items.forEach { item ->
            if (!isGrabAreaPath(item.path)) return@forEach
            val legacyPath = LEGACY_CATEGORY + item.path.removePrefix(CATEGORY)
            when (item) {
                is BaseSwitchFunctionHookItem ->
                    migrate(PrekXXX + legacyPath, PrekXXX + item.path)
                is BaseClickableFunctionHookItem ->
                    migrate(PrekClickableXXX + legacyPath, PrekClickableXXX + item.path)
            }
        }

        // Older TNT builds briefly exposed the inspector in Developer Options.
        migrate(
            PrekXXX + "开发者选项/QQ界面控件探测器",
            PrekXXX + "$CATEGORY/控件探测器"
        )

        if (!config.containsKey(MASTER_KEY)) {
            config.edit().putBoolean(MASTER_KEY, foundLegacySetting).apply()
        }
        return config.getBooleanOrFalse(MASTER_KEY)
    }
}
