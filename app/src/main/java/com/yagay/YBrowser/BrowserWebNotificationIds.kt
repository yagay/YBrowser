package com.yagay.YBrowser

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTML notification.tag replaces a notification with the same origin+tag.
 * Notifications WITHOUT tags are distinct even on the same website.
 */
internal class BrowserWebNotificationIds {
    private val anonymous = IdentityHashMap<Any, Int>()
    private val counter = AtomicInteger(100_000_000)

    @Synchronized
    fun id(origin: String, tag: String?, notification: Any): Int {
        if (!tag.isNullOrBlank()) {
            return ((origin + "|" + tag).hashCode() and Int.MAX_VALUE)
                .coerceAtLeast(1)
        }
        return anonymous.getOrPut(notification) {
            counter.updateAndGet { value ->
                if (value >= 999_999_999) 100_000_000 else value + 1
            }
        }
    }

    @Synchronized
    fun forget(notification: Any) {
        anonymous.remove(notification)
    }
}
