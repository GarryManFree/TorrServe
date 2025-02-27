package ru.yourok.torrserve.atv

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.util.Log
import ru.yourok.torrserve.BuildConfig
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.atv.channels.UpdaterCards
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.server.local.TorrService
import ru.yourok.torrserve.server.models.torrent.Torrent
import ru.yourok.torrserve.ui.activities.play.PlayActivity
import ru.yourok.torrserve.utils.TorrentHelper
import java.util.Locale
import kotlin.concurrent.thread


object Utils {

    private const val FEATURE_FIRE_TV = "amazon.hardware.fire_tv"
    fun isTvBox(): Boolean {
        val pm = App.context.packageManager
        // TV for sure
        val uiModeManager = App.context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        if (pm.hasSystemFeature(FEATURE_FIRE_TV)) {
            return true
        }
        // Missing Files app (DocumentsUI) means box (some boxes still have non functional app or stub)
        if (!hasSAFChooser(pm)) {
            return true
        }
        // Legacy storage no longer works on Android 11 (level 30)
        if (Build.VERSION.SDK_INT < 30) {
            // (Some boxes still report touchscreen feature)
            if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                return true
            }
            if (pm.hasSystemFeature("android.hardware.hdmi.cec")) {
                return true
            }
            if (Build.MANUFACTURER.equals("zidoo", ignoreCase = true)) {
                return true
            }
        }
        // Default: No TV - use SAF
        return false
    }

    private fun hasSAFChooser(pm: PackageManager?): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
        } else {
            return true // VERSION.SDK_INT < KITKAT
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "video/*"
        return intent.resolveActivity(pm!!) != null
    }

    private val deviceName: String
        get() = String.format("%s (%s)", Build.MODEL, Build.PRODUCT)

    private val isHuaweiDevice: Boolean
        get() {
            val manufacturer = Build.MANUFACTURER
            val brand = Build.BRAND
            return manufacturer.lowercase(Locale.getDefault())
                .contains("huawei") || brand.lowercase(
                Locale.getDefault()
            ).contains("huawei")
        }

    val isChangHong: Boolean
        get() {
            return deviceName.lowercase().contains("changhong", ignoreCase = true)
        }

    val isAndroidTV: Boolean
        get() {
            return App.context.packageManager.hasSystemFeature("android.software.leanback") &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !isHuaweiDevice // Huawei Vision S TV on Harmony OS don't have support for content://android.media.tv/channel
        }

    val isGoogleTV: Boolean // wide posters on home
        get() {
            return App.context.packageManager.hasSystemFeature("com.google.android.tv") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }

    val isAmazonTV: Boolean
        get() {
            return App.context.packageManager.hasSystemFeature(FEATURE_FIRE_TV)
        }

    val isBrokenTCL: Boolean
        get() {
            val deviceName = deviceName
            return deviceName.contains("(tcl_m7642)")
        }

    fun buildPendingIntent(torr: Torrent): Intent {
        val vintent = Intent(App.context, PlayActivity::class.java)
        vintent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        vintent.action = Intent.ACTION_VIEW
        vintent.data = Uri.parse(TorrentHelper.getTorrentMagnet(torr))
        vintent.putExtra("action", "play")
        vintent.putExtra("hash", torr.hash)
        vintent.putExtra("title", torr.title)
        torr.poster?.let { if (it.isNotBlank()) vintent.putExtra("poster", it) }
        torr.category?.let { if (it.isNotBlank()) vintent.putExtra("category", it) }
        torr.data?.let {if (it.isNotBlank()) vintent.putExtra("data", it) }
        vintent.putExtra("save", false)
        return vintent
    }

    private var lock = Any()
    fun updateAtvCards() {
        if (isAndroidTV) {
            synchronized(lock) {
                if (lock == true)
                    return
                lock = true
            }
            TorrService.wait(5)
            if (BuildConfig.DEBUG) Log.d("*****", "updateAtvCards()")
            var lastList = emptyList<Torrent>()
            try {
                lastList = Api.listTorrent()
            } catch (_: Exception) {
            }
            UpdaterCards.updateCards()
            thread {
                while (lock == true) {
                    var torrs = emptyList<Torrent>()
                    try {
                        torrs = Api.listTorrent()
                    } catch (_: Exception) {
                    }
                    if (!equalTorrs(lastList, torrs)) {
                        lastList = torrs
                        UpdaterCards.updateCards()
                        Thread.sleep(1000)
                    } else
                        Thread.sleep(5000)
                }
            }
        }
    }

    private fun equalTorrs(lst1: List<Torrent>, lst2: List<Torrent>): Boolean {
        if (lst1.size != lst2.size)
            return false
        lst1.forEachIndexed { index, torr ->
            if (torr.hash != lst2[index].hash ||
                torr.title != lst2[index].title ||
                torr.poster != lst2[index].poster
            )
                return false
        }
        return true
    }

}
