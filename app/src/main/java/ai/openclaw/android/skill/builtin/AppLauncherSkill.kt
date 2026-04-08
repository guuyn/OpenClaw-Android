package ai.openclaw.android.skill.builtin

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import ai.openclaw.android.skill.*

class AppLauncherSkill : Skill {
    override val id = "applauncher"
    override val name = "应用启动"
    override val description = "打开应用和列出已安装应用"
    override val version = "1.0.0"

    override val instructions = """
# App Launcher Skill

帮助用户打开手机上的应用。

## 用法
- 用户说"打开微信"时，调用 open 工具，传入 app_name="微信"
- 用户说"列出所有应用"时，调用 list_apps 工具
- 如果知道包名，可以直接用 package_name 参数打开
"""

    private var context: android.content.Context? = null

    override val tools: List<SkillTool> = listOf(OpenAppTool(), ListAppsTool())

    private inner class OpenAppTool : SkillTool {
        override val name = "open"
        override val description = "根据应用名称或包名打开应用"
        override val parameters = mapOf(
            "app_name" to SkillParam(
                type = "string",
                description = "应用名称，如'微信'、'QQ'",
                required = false
            ),
            "package_name" to SkillParam(
                type = "string",
                description = "应用包名，如'com.tencent.mm'",
                required = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val ctx = context ?: return SkillResult(false, "", "Context not initialized")
            val appName = params["app_name"] as? String
            val packageName = params["package_name"] as? String

            if (packageName.isNullOrBlank() && appName.isNullOrBlank()) {
                return SkillResult(false, "", "需要提供 app_name 或 package_name 参数")
            }

            try {
                val pm = ctx.packageManager

                if (!packageName.isNullOrBlank()) {
                    return launchByPackageName(ctx, pm, packageName)
                }

                // Search by app name
                return launchByAppName(ctx, pm, appName!!)
            } catch (e: Exception) {
                return SkillResult(false, "", "打开应用失败: ${e.message}")
            }
        }

        private fun launchByPackageName(
            ctx: android.content.Context,
            pm: PackageManager,
            packageName: String
        ): SkillResult {
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(launchIntent)
                return SkillResult(true, "已打开应用: $packageName")
            }
            return SkillResult(false, "", "未找到应用: $packageName")
        }

        private fun launchByAppName(
            ctx: android.content.Context,
            pm: PackageManager,
            appName: String
        ): SkillResult {
            // Query all launchable apps and match by label
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            @Suppress("DEPRECATION")
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentActivities(intent, 0)
            }

            val matches = resolveInfos.filter { info ->
                val label = info.loadLabel(pm).toString()
                label.equals(appName, ignoreCase = true) ||
                    label.contains(appName, ignoreCase = true)
            }

            if (matches.isEmpty()) {
                return SkillResult(false, "", "未找到应用: $appName")
            }

            // Prefer exact match
            val exactMatch = matches.find {
                it.loadLabel(pm).toString().equals(appName, ignoreCase = true)
            } ?: matches.first()

            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = exactMatch.activityInfo.let {
                    android.content.ComponentName(it.packageName, it.name)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ctx.startActivity(launchIntent)
            val label = exactMatch.loadLabel(pm).toString()
            return SkillResult(true, "已打开应用: $label")
        }
    }

    private inner class ListAppsTool : SkillTool {
        override val name = "list_apps"
        override val description = "列出已安装的应用"
        override val parameters = mapOf(
            "filter" to SkillParam(
                type = "string",
                description = "过滤关键词（可选）",
                required = false
            )
        )

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            val ctx = context ?: return SkillResult(false, "", "Context not initialized")
            val filter = params["filter"] as? String

            try {
                val pm = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                @Suppress("DEPRECATION")
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    pm.queryIntentActivities(intent, 0)
                }

                val apps = resolveInfos
                    .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                    .filter { (label, _) ->
                        if (filter.isNullOrBlank()) true
                        else label.contains(filter, ignoreCase = true)
                    }
                    .sortedBy { it.first.lowercase() }

                if (apps.isEmpty()) {
                    return SkillResult(true, if (filter.isNullOrBlank()) "没有找到已安装应用" else "没有匹配'$filter'的应用")
                }

                val result = apps.take(50).joinToString("\n") { (label, pkg) ->
                    "- $label ($pkg)"
                }

                val summary = if (apps.size > 50) {
                    "共 ${apps.size} 个应用，显示前 50 个:\n$result"
                } else {
                    "共 ${apps.size} 个应用:\n$result"
                }

                return SkillResult(true, summary)
            } catch (e: Exception) {
                return SkillResult(false, "", "获取应用列表失败: ${e.message}")
            }
        }
    }

    override fun initialize(context: SkillContext) {
        this.context = context.applicationContext
    }

    override fun cleanup() {
        context = null
    }
}
