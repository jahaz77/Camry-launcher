package com.camry.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import java.text.Normalizer

data class AppEntry(val label: String, val component: ComponentName, val icon: Drawable)

object AppUtils {

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    fun launchables(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(launcherIntent(), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                AppEntry(
                    it.loadLabel(pm).toString(),
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                    it.loadIcon(pm)
                )
            }
            .sortedBy { normalize(it.label) }
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Busca una app instalada cuyo nombre contenga alguna de las palabras dadas. */
    fun findByLabel(ctx: Context, vararg keys: String): ComponentName? {
        val pm = ctx.packageManager
        val wanted = keys.map { normalize(it) }
        val hit = pm.queryIntentActivities(launcherIntent(), 0).firstOrNull { r ->
            if (r.activityInfo.packageName == ctx.packageName) return@firstOrNull false
            val label = normalize(r.loadLabel(pm).toString())
            wanted.any { label.contains(it) }
        } ?: return null
        return ComponentName(hit.activityInfo.packageName, hit.activityInfo.name)
    }

    fun launch(ctx: Context, cn: ComponentName): Boolean = try {
        ctx.startActivity(
            launcherIntent().setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        )
        true
    } catch (e: Exception) {
        false
    }

    fun launchPackage(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
