package com.EDanaII.pillreminder

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon

/** Platform-tinted glyph plus Naggy's unchanged transparent launcher foreground, without its background/mask. */
internal fun Notification.Builder.withNaggyIcons(context: Context): Notification.Builder =
    setSmallIcon(R.drawable.ic_notification_naggy)
        .setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher_foreground))
