package com.pisotiyempo.desktop.data

import com.pisotiyempo.desktop.BuildInfo
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.Base64
import java.util.Properties

/** Where the desktop app keeps its files: %APPDATA%\PisoTiyempo on Windows, ~/.pisotiyempo elsewhere. */
object DesktopPaths {
    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

    val home: File = run {
        val appData = System.getenv("APPDATA")
        if (isWindows && appData != null) File(appData, "PisoTiyempo") else File(System.getProperty("user.home"), ".pisotiyempo")
    }.also { it.mkdirs() }

    val database = File(home, "piso-tiyempo.db")
    val config = File(home, "config.properties")
    val defaultExportDir = File(home, "export")
}

/**
 * Settings in a plain properties file so they're easy to edit by hand (for example to point the dashboard
 * feed at a different port). Missing values fall back to what the build was made with.
 */
class DesktopConfig {
    private val props = Properties().apply { if (DesktopPaths.config.exists()) DesktopPaths.config.inputStream().use { load(it) } }

    var apiBaseUrl: String
        get() = props.getProperty("apiBaseUrl", BuildInfo.API_BASE_URL)
        set(v) = set("apiBaseUrl", v)
    var appKey: String
        get() = props.getProperty("appKey", BuildInfo.APP_KEY)
        set(v) = set("appKey", v)
    var feedEnabled: Boolean
        get() = props.getProperty("feedEnabled", "true").toBoolean()
        set(v) = set("feedEnabled", v.toString())
    var feedPort: Int
        get() = props.getProperty("feedPort", "8765").toIntOrNull() ?: 8765
        set(v) = set("feedPort", v.toString())
    /** Comma-separated origins allowed to call the feed from a browser, e.g. http://localhost:3000. Empty = none. */
    var feedAllowOrigins: String
        get() = props.getProperty("feedAllowOrigins", "")
        set(v) = set("feedAllowOrigins", v)
    var exportEnabled: Boolean
        get() = props.getProperty("exportEnabled", "true").toBoolean()
        set(v) = set("exportEnabled", v.toString())
    var exportDir: File
        get() = File(props.getProperty("exportDir", DesktopPaths.defaultExportDir.absolutePath))
        set(v) = set("exportDir", v.absolutePath)
    var aiEnabled: Boolean
        get() = props.getProperty("aiEnabled", "true").toBoolean()
        set(v) = set("aiEnabled", v.toString())

    private fun set(key: String, value: String) {
        props.setProperty(key, value)
        DesktopPaths.config.outputStream().use { props.store(it, "Piso Tiyempo desktop settings") }
    }
}

/**
 * Protects small secrets (the sync link code) at rest. On Windows it uses DPAPI, which ties the data to the
 * signed-in Windows user; elsewhere it relies on the file living in the user's private home folder.
 */
object SecretBox {
    fun seal(plain: String): String =
        if (DesktopPaths.isWindows) "dpapi:" + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.toByteArray()))
        else "plain:$plain"

    fun open(sealed: String): String? = runCatching {
        when {
            sealed.startsWith("dpapi:") -> String(Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(sealed.removePrefix("dpapi:"))))
            sealed.startsWith("plain:") -> sealed.removePrefix("plain:")
            else -> null
        }
    }.getOrNull()
}
