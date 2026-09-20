package com.yagay.YBrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat

enum class BrowserMediaCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    STOP,
}

data class BrowserMediaState(
    val title: String,
    val url: String,
    val playing: Boolean,
    val durationMs: Long = -1L,
    val positionMs: Long = 0L,
)

object BrowserMediaRuntime {
    private var commandHandler: ((BrowserMediaCommand) -> Unit)? = null

    fun bind(handler: (BrowserMediaCommand) -> Unit) {
        commandHandler = handler
    }

    fun unbind(handler: ((BrowserMediaCommand) -> Unit)? = null) {
        if (handler == null || commandHandler === handler) {
            commandHandler = null
        }
    }

    fun dispatch(command: BrowserMediaCommand) {
        commandHandler?.invoke(command)
    }

    fun update(context: Context, state: BrowserMediaState?) {
        if (state == null) {
            clear(context)
            return
        }
        val intent = Intent(context, BrowserMediaService::class.java)
            .setAction(BrowserMediaService.ACTION_UPDATE)
            .putExtra(BrowserMediaService.EXTRA_TITLE, state.title)
            .putExtra(BrowserMediaService.EXTRA_URL, state.url)
            .putExtra(BrowserMediaService.EXTRA_PLAYING, state.playing)
            .putExtra(BrowserMediaService.EXTRA_DURATION, state.durationMs)
            .putExtra(BrowserMediaService.EXTRA_POSITION, state.positionMs)
        ContextCompat.startForegroundService(context, intent)
    }

    fun clear(context: Context) {
        runCatching {
            context.startService(
                Intent(context, BrowserMediaService::class.java)
                    .setAction(BrowserMediaService.ACTION_CLEAR),
            )
        }
    }
}

class BrowserMediaService : Service() {
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var currentState: BrowserMediaState? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "网页媒体播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "YBrowser 后台网页音频和视频控制"
                setShowBadge(false)
            },
        )

        mediaSession = MediaSession(this, "YBrowserMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    BrowserMediaRuntime.dispatch(BrowserMediaCommand.PLAY)
                }

                override fun onPause() {
                    BrowserMediaRuntime.dispatch(BrowserMediaCommand.PAUSE)
                }

                override fun onStop() {
                    BrowserMediaRuntime.dispatch(BrowserMediaCommand.STOP)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE -> {
                val state = BrowserMediaState(
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                        .ifBlank { "网页媒体" },
                    url = intent.getStringExtra(EXTRA_URL).orEmpty(),
                    playing = intent.getBooleanExtra(EXTRA_PLAYING, false),
                    durationMs = intent.getLongExtra(EXTRA_DURATION, -1L),
                    positionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
                )
                currentState = state
                updateMediaSession(state)
                startForeground(NOTIFICATION_ID, buildNotification(state))
            }

            ACTION_TOGGLE -> BrowserMediaRuntime.dispatch(BrowserMediaCommand.TOGGLE)
            ACTION_STOP -> BrowserMediaRuntime.dispatch(BrowserMediaCommand.STOP)

            ACTION_CLEAR -> {
                currentState = null
                mediaSession.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentState = null
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateMediaSession(state: BrowserMediaState) {
        mediaSession.isActive = true
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, state.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, hostFromUrl(state.url))
                .apply {
                    if (state.durationMs > 0L) {
                        putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
                    }
                }
                .build(),
        )

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (state.playing) {
                        PlaybackState.STATE_PLAYING
                    } else {
                        PlaybackState.STATE_PAUSED
                    },
                    state.positionMs.coerceAtLeast(0L),
                    if (state.playing) 1f else 0f,
                )
                .build(),
        )
    }

    private fun buildNotification(state: BrowserMediaState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BrowserMediaService::class.java)
                .setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, BrowserMediaService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = Notification.Action.Builder(
            if (state.playing) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            },
            if (state.playing) "暂停" else "播放",
            toggleIntent,
        ).build()
        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "停止",
            stopIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title)
            .setContentText(hostFromUrl(state.url))
            .setContentIntent(openIntent)
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun hostFromUrl(url: String): String {
        return runCatching { android.net.Uri.parse(url).host.orEmpty() }
            .getOrDefault("")
            .ifBlank { "YBrowser" }
    }

    companion object {
        const val ACTION_UPDATE = "com.yagay.YBrowser.media.UPDATE"
        const val ACTION_TOGGLE = "com.yagay.YBrowser.media.TOGGLE"
        const val ACTION_STOP = "com.yagay.YBrowser.media.STOP"
        const val ACTION_CLEAR = "com.yagay.YBrowser.media.CLEAR"

        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_POSITION = "position"

        private const val CHANNEL_ID = "ybrowser_media"
        private const val NOTIFICATION_ID = 2401
    }
}

internal const val WEBVIEW_MEDIA_MONITOR_SCRIPT = """
(() => {
  if (window.__ybrowserMediaMonitorInstalled) {
    window.__ybrowserMediaReport?.();
    return;
  }
  window.__ybrowserMediaMonitorInstalled = true;

  const ybrowserUsesBackgroundVideoVisibilityFix =
    /(^|\\.)youtube(?:-nocookie)?\\.com$/.test(location.hostname);
  if (ybrowserUsesBackgroundVideoVisibilityFix) {
    try {
      Object.defineProperties(document, {
        hidden: { get: () => false },
        visibilityState: { get: () => "visible" }
      });
    } catch (_) {}
    window.addEventListener("visibilitychange", (event) => {
      event.stopImmediatePropagation();
    }, true);
  }

  let current = null;
  let lastReport = 0;

  const candidates = () => Array.from(document.querySelectorAll("video,audio"))
    .filter((el) => el && el.isConnected);

  const choose = () => {
    if (current && current.isConnected && !current.ended) return current;
    current = candidates().sort((a, b) => {
      const playing = Number(a.paused) - Number(b.paused);
      if (playing !== 0) return playing;
      return (b.clientWidth * b.clientHeight) - (a.clientWidth * a.clientHeight);
    })[0] || null;
    return current;
  };

  const report = (force = false) => {
    const media = choose();
    if (!media || !window.YBrowserMediaNative) return;
    const now = Date.now();
    if (!force && now - lastReport < 1000) return;
    lastReport = now;
    const duration = Number.isFinite(media.duration) ? Math.round(media.duration * 1000) : -1;
    const position = Number.isFinite(media.currentTime) ? Math.round(media.currentTime * 1000) : 0;
    window.YBrowserMediaNative.onMediaState(JSON.stringify({
      title: document.title || "网页媒体",
      url: location.href,
      playing: !media.paused && !media.ended,
      durationMs: duration,
      positionMs: position
    }));
  };

  document.addEventListener("play", (event) => {
    if (event.target instanceof HTMLMediaElement) {
      current = event.target;
      report(true);
    }
  }, true);
  document.addEventListener("pause", (event) => {
    if (event.target instanceof HTMLMediaElement) {
      current = event.target;
      report(true);
    }
  }, true);
  document.addEventListener("ended", (event) => {
    if (event.target instanceof HTMLMediaElement) {
      current = event.target;
      report(true);
    }
  }, true);
  document.addEventListener("loadedmetadata", (event) => {
    if (event.target instanceof HTMLMediaElement) {
      current = event.target;
      report(true);
    }
  }, true);
  document.addEventListener("timeupdate", () => report(false), true);

  window.__ybrowserMediaCommand = (command) => {
    const media = choose();
    if (!media) return false;
    if (command === "play") {
      media.play().catch(() => {});
    } else if (command === "pause") {
      media.pause();
    } else if (command === "toggle") {
      if (media.paused || media.ended) media.play().catch(() => {});
      else media.pause();
    } else if (command === "stop") {
      media.pause();
      try { media.currentTime = 0; } catch (_) {}
    }
    setTimeout(() => report(true), 100);
    return true;
  };

  window.__ybrowserMediaReport = () => report(true);
  report(true);
})()
"""
