package com.example.carsettings

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var body: LinearLayout

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun sh(cmd: String, done: (String) -> Unit) {
        Thread {
            val r = try { Shell.run(this, cmd) } catch (e: Exception) {
                Shell.reset(); "Ошибка: ${e.message}"
            }
            runOnUiThread { done(r) }
        }.start()
    }

    private fun text(s: String, size: Float = 16f) = TextView(this).apply {
        text = s; textSize = size; setPadding(dp(12), dp(8), dp(12), dp(8)); setTextIsSelectable(true)
    }

    private fun btn(s: String, f: () -> Unit) = Button(this).apply { text = s; setOnClickListener { f() } }

    private fun field(hint: String, v: String, number: Boolean = false) = EditText(this).apply {
        setHint(hint); setText(v); setSingleLine()
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun screen(title: String, build: (LinearLayout) -> Unit) {
        body.removeAllViews()
        body.addView(text(title, 20f))
        build(body)
    }

    private fun confirm(title: String, msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("Подтвердить") { _, _ -> ok() }
            .setNegativeButton("Отмена", null).show()
    }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this)
        val items = listOf<Pair<String, () -> Unit>>(
            "Приложения" to { showApps() }, "Дисплеи" to { showDisplays() },
            "Система" to { showSystem() }, "Меню" to { showQuick() }
        )
        items.forEach { (t, f) ->
            tabs.addView(btn(t, f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tabs)
        root.addView(ScrollView(this).apply { addView(body) })
        setContentView(root)
        showSystem()
    }

    // ---------- Приложения ----------
    private fun showApps() = screen("Приложения") { b ->
        val pm = packageManager
        val modes = listOf("обычно", "окно", "дисплей")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
            .forEach { (pkg, label) ->
                val c = Cfg.dec(Prefs.get(this, "app_$pkg"))
                b.addView(btn("$label  [${modes[c.mode]}]") { editApp(pkg, label) })
            }
    }

    private fun editApp(pkg: String, label: String) {
        val c = Cfg.dec(Prefs.get(this, "app_$pkg"))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val mode = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Обычный запуск", "Оконный режим", "На другой дисплей"))
            setSelection(c.mode)
        }
        val x = field("x", c.x.toString(), true); val y = field("y", c.y.toString(), true)
        val w = field("ширина", c.w.toString(), true); val h = field("высота", c.h.toString(), true)
        val d = field("ID дисплея", c.display.toString(), true)
        listOf<android.view.View>(mode, text("Окно: x, y, ширина, высота"), x, y, w, h, text("Дисплей (см. вкладку «Дисплеи»)"), d)
            .forEach { box.addView(it) }
        fun read() = Cfg(mode.selectedItemPosition, x.text.toString().toIntOrNull() ?: 0,
            y.text.toString().toIntOrNull() ?: 0, w.text.toString().toIntOrNull() ?: 800,
            h.text.toString().toIntOrNull() ?: 480, d.text.toString().toIntOrNull() ?: 1)
        AlertDialog.Builder(this).setTitle(label).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Сохранить") { _, _ -> Prefs.put(this, "app_$pkg", read().enc()); showApps() }
            .setNeutralButton("Запустить") { _, _ -> val cfg = read(); Prefs.put(this, "app_$pkg", cfg.enc()); launch(pkg, cfg) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun launch(pkg: String, c: Cfg) {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return toast("Нет launch-Activity")
        when (c.mode) {
            0 -> startActivity(intent)
            1 -> {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val o = ActivityOptions.makeBasic().setLaunchBounds(Rect(c.x, c.y, c.x + c.w, c.y + c.h))
                startActivity(intent, o.toBundle())
            }
            else -> {
                val cn = intent.component?.flattenToShortString() ?: return toast("Нет компонента")
                sh("am start -n $cn --display ${c.display}") { toast(it.take(200)) }
            }
        }
    }

    // ---------- Дисплеи ----------
    private fun showDisplays() = screen("Дисплеи") { b ->
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays.forEach { d ->
            val p = Point(); d.getRealSize(p)
            b.addView(text("ID ${d.displayId}: ${d.name} — ${p.x}x${p.y}, flags=${d.flags}"))
        }
        val out = text("")
        b.addView(btn("Полный список через shell (включая скрытые)") {
            sh("dumpsys display | grep -E 'mDisplayId|mBaseDisplayInfo|DisplayDeviceInfo'") { out.text = it }
        })
        b.addView(out)
    }

    // ---------- Система ----------
    private val freeformKeys = listOf("enable_freeform_support" to "1", "force_resizable_activities" to "1")

    private fun showSystem() = screen("Система") { b ->
        val out = text("")
        b.addView(text("Один раз с компьютера выполните: adb tcpip 5555\n" +
            "После перезагрузки магнитолы это нужно повторить. При первом подключении подтвердите запрос ключа."))
        b.addView(btn("Проверить ADB-подключение") {
            sh("getprop ro.build.version.release") { out.text = if (it.startsWith("Ошибка")) it else "Подключено, Android $it" }
        })
        b.addView(btn("Показать текущее состояние") {
            sh("settings get global enable_freeform_support; settings get global force_resizable_activities; wm size; wm density") { out.text = it }
        })
        b.addView(text("Шаг 1. Оконный режим (freeform) и изменение размера окон", 17f))
        b.addView(btn("Применить…") {
            val cmds = freeformKeys.joinToString("\n") { "settings put global ${it.first} ${it.second}" }
            confirm("Будут выполнены команды", cmds + "\n\nМожет понадобиться перезагрузка.") {
                applyGlobal(freeformKeys) { out.text = it }
            }
        })
        b.addView(text("Шаг 2. Размер экрана и плотность (необязательно, масштаб интерфейса)", 17f))
        val size = field("размер, например 1920x720", ""); val dens = field("плотность, например 240", "", true)
        b.addView(size); b.addView(dens)
        b.addView(btn("Применить…") {
            val cmds = listOfNotNull(
                size.text.toString().takeIf { it.isNotBlank() }?.let { "wm size $it" },
                dens.text.toString().takeIf { it.isNotBlank() }?.let { "wm density $it" })
            if (cmds.isEmpty()) return@btn toast("Заполните хотя бы одно поле")
            confirm("Будут выполнены команды", cmds.joinToString("\n") +
                "\n\nНеверные значения могут сделать экран неудобным. Откат: кнопка ниже или adb shell wm size reset") {
                Prefs.put(this, "wm_changed", "1")
                sh(cmds.joinToString(" ; ")) { out.text = it.ifBlank { "Готово" } }
            }
        })
        b.addView(text("Возврат к стоку", 17f))
        b.addView(btn("Вернуть всё как было…") {
            confirm("Вернуть к стоку", "Будут восстановлены значения, которые были до изменений через это приложение " +
                "(freeform, размер, плотность), и удалены сохранённые настройки приложения.") { stockReset { out.text = it } }
        })
        b.addView(out)
    }

    private fun applyGlobal(kv: List<Pair<String, String>>, done: (String) -> Unit) {
        Thread {
            val r = try {
                kv.forEach { (k, v) ->
                    if (Prefs.get(this, "orig_$k") == null)
                        Prefs.put(this, "orig_$k", Shell.run(this, "settings get global $k"))
                    Shell.run(this, "settings put global $k $v")
                }
                "Готово. Если окна не заработали, перезагрузите магнитолу."
            } catch (e: Exception) { Shell.reset(); "Ошибка: ${e.message}" }
            runOnUiThread { done(r) }
        }.start()
    }

    private fun stockReset(done: (String) -> Unit) {
        Thread {
            val log = StringBuilder()
            try {
                if (Prefs.get(this, "wm_changed") != null) {
                    Shell.run(this, "wm size reset ; wm density reset"); log.append("Размер и плотность сброшены\n")
                }
                freeformKeys.forEach { (k, _) ->
                    val o = Prefs.get(this, "orig_$k") ?: return@forEach
                    Shell.run(this, if (o == "null" || o.isBlank()) "settings delete global $k" else "settings put global $k $o")
                    log.append("$k восстановлено ($o)\n")
                }
                Prefs.clear(this)
                log.append("Готово. Рекомендуется перезагрузка.")
            } catch (e: Exception) { Shell.reset(); log.append("Ошибка: ${e.message}") }
            runOnUiThread { done(log.toString()) }
        }.start()
    }

    // ---------- Инженерное меню / кренометр ----------
    private fun showQuick() = screen("Инженерное меню и кренометр") { b ->
        listOf("eng" to "Инженерное меню", "tilt" to "Кренометр").forEach { (key, title) ->
            val f = field("пакет/Activity", Prefs.get(this, "q_$key") ?: "")
            b.addView(text(title, 17f)); b.addView(f)
            b.addView(btn("Сохранить") { Prefs.put(this, "q_$key", f.text.toString().trim()); toast("Сохранено") })
            b.addView(btn("Открыть") { sh("am start -n ${f.text.toString().trim()}") { toast(it.take(200)) } })
        }
        val last = text("Последний захват: ${Prefs.get(this, "last_focus") ?: "—"}")
        b.addView(text("Как найти компонент: нажмите кнопку ниже, за 10 секунд откройте нужный экран штатным способом, " +
            "вернитесь сюда и скопируйте результат в поле выше.", 14f))
        b.addView(btn("Захватить текущее окно через 10 с") {
            toast("Откройте нужный экран…")
            Handler(Looper.getMainLooper()).postDelayed({
                sh("dumpsys window | grep mCurrentFocus") { r ->
                    val m = Regex("([\\w.]+/[\\w.\$]+)\\}").find(r)
                    Prefs.put(this, "last_focus", m?.groupValues?.get(1) ?: r)
                    toast("Захвачено: ${m?.groupValues?.get(1) ?: r}".take(150))
                }
            }, 10000)
        })
        b.addView(btn("Копировать последний захват") {
            val v = Prefs.get(this, "last_focus") ?: return@btn toast("Пока пусто")
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("component", v))
            last.text = "Последний захват: $v"; toast("Скопировано")
        })
        b.addView(last)
    }
}
