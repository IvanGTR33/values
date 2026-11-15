package com.example.carsettings

import android.content.Context
import dev.mobile.dadb.AdbKeyPair
import dev.mobile.dadb.Dadb
import java.io.File

/** Выполняет shell-команды через ADB-подключение к самому устройству (localhost:5555). */
object Shell {
    private var dadb: Dadb? = null

    @Synchronized
    private fun ensure(ctx: Context): Dadb {
        dadb?.let { return it }
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        val d = Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub))
        dadb = d
        return d
    }

    @Synchronized
    fun reset() {
        try { dadb?.close() } catch (_: Exception) {}
        dadb = null
    }

    fun run(ctx: Context, cmd: String): String =
        ensure(ctx).shell(cmd).allOutput.trim()
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun get(c: Context, k: String): String? = sp(c).getString(k, null)
    fun put(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun remove(c: Context, k: String) = sp(c).edit().remove(k).apply()
    fun clear(c: Context) = sp(c).edit().clear().apply()
}

/** Настройки запуска приложения: mode 0 = обычно, 1 = окно, 2 = другой дисплей. */
data class Cfg(
    var mode: Int = 0, var x: Int = 0, var y: Int = 0,
    var w: Int = 800, var h: Int = 480, var display: Int = 1
) {
    fun enc() = "$mode,$x,$y,$w,$h,$display"

    companion object {
        fun dec(s: String?): Cfg {
            val p = s?.split(",")?.mapNotNull { it.toIntOrNull() }
            return if (p != null && p.size == 6) Cfg(p[0], p[1], p[2], p[3], p[4], p[5]) else Cfg()
        }
    }
}
