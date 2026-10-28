package com.EDanaII.pillreminder

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/** App-observed lifecycle only; PROMOTED means startForeground returned, not that audio policy agrees. */
internal data class FocusServiceSnapshot(
    val state: String = "UNKNOWN",
    val promotedAtMs: Long = -1,
    val foregroundType: Int = -1,
    val startId: Int = -1
)

/** One bounded snapshot per existing request. No enumeration of other apps or extra permissions. */
internal object AudioFocusDiagnostics {
    private fun <T> read(block: () -> T): T? = try { block() } catch (_: RuntimeException) { null }

    fun capture(context: Context, service: () -> FocusServiceSnapshot): (Long, Long) -> String {
        val lifecycle = read(service) ?: FocusServiceSnapshot()
        val importance = read {
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        } ?: -1
        val interactive = read { context.getSystemService(PowerManager::class.java).isInteractive }
        val keyguardLocked = read { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
        val credentialUnlocked = read { DirectBootAlarmStore.isUnlocked(context) }
        val targetSdk = read { context.applicationInfo.targetSdkVersion } ?: -1
        return { requestAtMs, burstStartedAtMs ->
            "attempt=1 requestAtMs=$requestAtMs callMs=${SystemClock.elapsedRealtime() - requestAtMs} " +
                "burstAgeMs=${requestAtMs - burstStartedAtMs} " +
                "serviceState=${lifecycle.state} startId=${lifecycle.startId} fgsType=${lifecycle.foregroundType} " +
                "sincePromotionMs=${if (lifecycle.promotedAtMs >= 0) requestAtMs - lifecycle.promotedAtMs else -1} " +
                "importance=$importance pid=${Process.myPid()} uid=${Process.myUid()} " +
                "sdk=${Build.VERSION.SDK_INT} targetSdk=$targetSdk " +
                "interactive=${interactive ?: "unknown"} keyguardLocked=${keyguardLocked ?: "unknown"} " +
                "credentialUnlocked=${credentialUnlocked ?: "unknown"}"
        }
    }
}
