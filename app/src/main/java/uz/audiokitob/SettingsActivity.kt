package uz.audiokitob

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private val keyEdits = ArrayList<EditText>()
    private lateinit var spVoice: Spinner
    private lateinit var etModel: EditText
    private lateinit var etBackup: EditText
    private lateinit var etChunk: EditText
    private lateinit var etStyle: EditText
    private lateinit var cbOcr: CheckBox
    private lateinit var etOcrModel: EditText
    private lateinit var etOcrBackup: EditText
    private lateinit var etOcrPages: EditText
    private lateinit var tvStatus: TextView
    private val voiceIds = ArrayList<String>()
    private val voiceLabels = ArrayList<String>()

    companion object {
        /** Gemini TTS ning 30 ta tayyor ovozi nomlari. Qisqa taʼriflari tilga qarab R.array.voice_traits da (shu tartibda). */
        val VOICE_NAMES = listOf(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar",
            "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        page.addView(Ui.text(this, getString(R.string.settings_note), 15f))
        for (i in 1..Prefs.MAX_KEYS) {
            val et = Ui.edit(this, "AQ... / AIza...")
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            et.setText(Prefs.getKey(this, i))
            keyEdits.add(et)
            Ui.labeled(this, page, getString(R.string.settings_key_label, i), et)
            page.addView(Ui.button(this, getString(R.string.settings_key_paste, i)) { pasteInto(et) })
        }
        page.addView(Ui.button(this, getString(R.string.settings_check_keys)) { checkKeys() })
        tvStatus = Ui.text(this, "", 15f)
        tvStatus.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        page.addView(tvStatus)

        spVoice = Spinner(this)
        buildVoiceItems()
        applyVoiceAdapter()
        Ui.labeled(this, page, getString(R.string.settings_voice), spVoice)
        page.addView(Ui.button(this, getString(R.string.settings_load_voices)) { loadVoices() })

        etModel = Ui.edit(this, Prefs.DEFAULT_MODEL)
        etModel.setText(Prefs.model(this))
        Ui.labeled(this, page, getString(R.string.settings_model), etModel)

        etBackup = Ui.edit(this, getString(R.string.settings_backup_hint))
        etBackup.setText(Prefs.backup(this))
        Ui.labeled(this, page, getString(R.string.settings_backup), etBackup)

        etChunk = Ui.edit(this, "3000")
        etChunk.inputType = InputType.TYPE_CLASS_NUMBER
        etChunk.setText(Prefs.chunk(this).toString())
        Ui.labeled(this, page, getString(R.string.settings_chunk), etChunk)

        etStyle = Ui.edit(this, getString(R.string.settings_optional))
        etStyle.setText(Prefs.style(this))
        Ui.labeled(this, page, getString(R.string.settings_style), etStyle)

        // Skanerlangan PDF (OCR)
        page.addView(Ui.heading(this, getString(R.string.settings_ocr_heading)))
        page.addView(Ui.text(this, getString(R.string.settings_ocr_note), 15f))
        cbOcr = Ui.check(this, getString(R.string.settings_ocr_enable), Prefs.ocrEnabled(this))
        page.addView(cbOcr)
        etOcrModel = Ui.edit(this, Prefs.DEFAULT_OCR_MODEL)
        etOcrModel.setText(Prefs.ocrModel(this))
        Ui.labeled(this, page, getString(R.string.settings_ocr_model), etOcrModel)
        etOcrBackup = Ui.edit(this, getString(R.string.settings_backup_hint))
        etOcrBackup.setText(Prefs.ocrBackup(this))
        Ui.labeled(this, page, getString(R.string.settings_ocr_backup), etOcrBackup)
        etOcrPages = Ui.edit(this, Prefs.DEFAULT_OCR_PAGES.toString())
        etOcrPages.inputType = InputType.TYPE_CLASS_NUMBER
        etOcrPages.setText(Prefs.ocrPages(this).toString())
        Ui.labeled(this, page, getString(R.string.settings_ocr_pages), etOcrPages)

        page.addView(Ui.button(this, getString(R.string.settings_battery)) { openBattery() })
        page.addView(Ui.button(this, getString(R.string.settings_save_back)) {
            saveAll()
            finish()
        })
        setContentView(Ui.scroll(this, page))
    }

    override fun onPause() {
        saveAll()
        super.onPause()
    }

    override fun onSupportNavigateUp(): Boolean {
        saveAll()
        finish()
        return true
    }

    // ---------------------------------------------------------------- ovozlar

    private fun buildVoiceItems() {
        voiceIds.clear()
        voiceLabels.clear()
        val js = Prefs.voicesJson(this)
        if (js.isNotEmpty()) {
            try {
                val arr = JSONArray(js)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    voiceIds.add(o.getString("id"))
                    voiceLabels.add("★ " + o.getString("label"))
                }
            } catch (t: Throwable) {
                Journal.w("Sozlamalar", "Saqlangan ovozlar roʻyxati oʻqilmadi", t)
            }
        }
        val traits = resources.getStringArray(R.array.voice_traits)
        for (i in VOICE_NAMES.indices) {
            voiceIds.add(VOICE_NAMES[i])
            voiceLabels.add(VOICE_NAMES[i] + " — " + (if (i < traits.size) traits[i] else ""))
        }
        val cur = Prefs.voice(this)
        if (!voiceIds.contains(cur)) {
            voiceIds.add(0, cur)
            voiceLabels.add(0, getString(R.string.settings_voice_saved, cur))
        }
    }

    private fun applyVoiceAdapter() {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, voiceLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spVoice.adapter = adapter
        val idx = voiceIds.indexOf(Prefs.voice(this))
        if (idx >= 0) spVoice.setSelection(idx)
    }

    private fun firstKey(): String? {
        for (e in keyEdits) {
            val k = Prefs.cleanKey(e.text.toString())
            if (k.isNotEmpty()) return k
        }
        return null
    }

    private fun loadVoices() {
        val key = firstKey()
        if (key == null) {
            tvStatus.text = getString(R.string.settings_need_key)
            return
        }
        tvStatus.text = getString(R.string.settings_voices_loading)
        Thread {
            try {
                var list = Gemini.listVoices(key, "uz-UZ")
                if (list.isEmpty()) list = Gemini.listVoices(key, "uz")
                if (list.isEmpty()) {
                    runOnUiThread {
                        tvStatus.text = getString(R.string.settings_voices_none)
                    }
                } else {
                    val arr = JSONArray()
                    for (v in list) arr.put(JSONObject().put("id", v.id).put("label", v.label))
                    Prefs.setVoicesJson(this, arr.toString())
                    val n = list.size
                    runOnUiThread {
                        buildVoiceItems()
                        applyVoiceAdapter()
                        tvStatus.text = getString(R.string.settings_voices_loaded, n)
                    }
                }
            } catch (t: Throwable) {
                Journal.w("Sozlamalar", "Ovozlarni yuklab boʻlmadi", t)
                runOnUiThread { tvStatus.text = getString(R.string.settings_voices_failed, t.message ?: t.javaClass.simpleName) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- kalitlar

    private fun pasteInto(et: EditText) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip == null || clip.itemCount == 0) {
                Toast.makeText(this, getString(R.string.settings_clip_empty), Toast.LENGTH_SHORT).show()
                return
            }
            val t = clip.getItemAt(0).coerceToText(this).toString()
            et.setText(Prefs.cleanKey(t))
            Toast.makeText(this, getString(R.string.settings_pasted), Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Journal.w("Sozlamalar", "Buferdan joylab boʻlmadi", t)
            Toast.makeText(this, getString(R.string.settings_paste_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkKeys() {
        val vals = ArrayList<Pair<Int, String>>()
        for ((i, e) in keyEdits.withIndex()) {
            val k = Prefs.cleanKey(e.text.toString())
            if (k.isNotEmpty()) vals.add(Pair(i + 1, k))
        }
        if (vals.isEmpty()) {
            tvStatus.text = getString(R.string.settings_check_need_key)
            return
        }
        tvStatus.text = getString(R.string.settings_checking)
        Journal.registerSecrets(vals.map { it.second })
        Thread {
            val sb = StringBuilder()
            for (p in vals) {
                val r = Gemini.checkKey(p.second)
                if (r.ok) {
                    val none = getString(R.string.settings_none_found)
                    sb.append(
                        getString(
                            R.string.settings_check_ok, p.first,
                            if (r.ttsModels.isEmpty()) none else r.ttsModels.joinToString(", "),
                            if (r.ocrModels.isEmpty()) none else r.ocrModels.joinToString(", ")
                        )
                    )
                } else if (r.code == 0) {
                    sb.append(getString(R.string.settings_check_net, p.first, r.message))
                } else {
                    sb.append(getString(R.string.settings_check_bad, p.first, r.code, r.message))
                }
                sb.append("\n")
                if (!r.ok) Journal.w("Sozlamalar", "Kalit " + p.first + " tekshiruvi: HTTP " + r.code + " " + r.message)
            }
            val out = sb.toString().trim()
            runOnUiThread { tvStatus.text = out }
        }.start()
    }

    // ---------------------------------------------------------------- saqlash

    private fun saveAll() {
        for ((i, e) in keyEdits.withIndex()) Prefs.setKey(this, i + 1, e.text.toString())
        val idx = spVoice.selectedItemPosition
        if (idx >= 0 && idx < voiceIds.size) Prefs.setVoice(this, voiceIds[idx])
        val m = etModel.text.toString().trim()
        Prefs.setModel(this, if (m.isEmpty()) Prefs.DEFAULT_MODEL else m)
        Prefs.setBackup(this, etBackup.text.toString().trim())
        val ch = etChunk.text.toString().trim().toIntOrNull() ?: Prefs.DEFAULT_CHUNK
        Prefs.setChunk(this, ch)
        Prefs.setStyle(this, etStyle.text.toString())
        Prefs.setOcrEnabled(this, cbOcr.isChecked)
        val om = etOcrModel.text.toString().trim()
        Prefs.setOcrModel(this, if (om.isEmpty()) Prefs.DEFAULT_OCR_MODEL else om)
        Prefs.setOcrBackup(this, etOcrBackup.text.toString().trim())
        Prefs.setOcrPages(this, etOcrPages.text.toString().trim().toIntOrNull() ?: Prefs.DEFAULT_OCR_PAGES)
        val n = Prefs.keys(this).size
        Journal.i(
            "Sozlamalar",
            "Saqlandi: kalitlar=" + n + ", ovoz=" + Prefs.voice(this) + ", model=" + Prefs.model(this) +
                ", zaxira=" + Prefs.backup(this) + ", boʻlak=" + Prefs.chunk(this) +
                ", OCR=" + Prefs.ocrEnabled(this) + " " + Prefs.ocrModel(this) + " / " + Prefs.ocrBackup(this) +
                ", OCR sahifalar=" + Prefs.ocrPages(this)
        )
    }

    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (x: Throwable) {
                Toast.makeText(this, getString(R.string.settings_open_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
