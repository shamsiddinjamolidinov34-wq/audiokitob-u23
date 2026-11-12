package uz.audiokitob

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private class Card(val root: LinearLayout, val title: TextView, val status: TextView)

    private lateinit var jobsBox: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvKeys: TextView
    private lateinit var tvJournal: TextView
    private lateinit var etTitle: EditText
    private lateinit var etText: EditText
    private val cards = HashMap<String, Card>()
    private val ui = Handler(Looper.getMainLooper())
    private val listener = Runnable { refresh() }
    private var pendingExport: File? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importUri(uri)
    }
    private val saveMp3 = registerForActivityResult(CreateDoc("audio/mpeg")) { uri: Uri? -> onSaved(uri) }
    private val saveWav = registerForActivityResult(CreateDoc("audio/x-wav")) { uri: Uri? -> onSaved(uri) }
    private val saveTxt = registerForActivityResult(CreateDoc("text/plain")) { uri: Uri? -> onSaved(uri) }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        Journal.i("UI", "Bildirishnoma ruxsati: " + granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        val page = Ui.page(this)
        page.addView(
            Ui.text(
                this,
                getString(R.string.main_intro),
                16f
            )
        )
        tvKeys = Ui.text(this, "", 15f)
        page.addView(tvKeys)
        page.addView(Ui.button(this, getString(R.string.main_pick_file)) {
            pickFile.launch(arrayOf("*/*"))
        })

        etTitle = Ui.edit(this, getString(R.string.main_title_hint))
        Ui.labeled(this, page, getString(R.string.main_title_label), etTitle)
        etText = Ui.edit(this, getString(R.string.main_text_hint), true)
        Ui.labeled(this, page, getString(R.string.main_text_label), etText)
        page.addView(Ui.button(this, getString(R.string.main_text_convert)) { onTextClicked() })

        page.addView(Ui.button(this, getString(R.string.main_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        page.addView(Ui.button(this, getString(R.string.main_journal)) {
            startActivity(Intent(this, JournalActivity::class.java))
        })
        tvJournal = Ui.text(this, "", 14f)
        page.addView(tvJournal)

        page.addView(Ui.heading(this, getString(R.string.main_jobs_heading)))
        page.addView(
            Ui.text(
                this,
                getString(R.string.main_jobs_hint),
                14f
            )
        )
        tvEmpty = Ui.text(this, getString(R.string.main_jobs_empty), 15f)
        page.addView(tvEmpty)
        jobsBox = LinearLayout(this)
        jobsBox.orientation = LinearLayout.VERTICAL
        page.addView(jobsBox, Ui.lp(this, 4))

        setContentView(Ui.scroll(this, page))

        askNotificationPermission()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        JobStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        JobStore.removeListener(listener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateInfo()
        ui.postDelayed({ JobStore.fixStale() }, 2500L)
    }

    // ---------------------------------------------------------------- kirish: fayl yoki matn

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val act = i.action
        if (act == Intent.ACTION_SEND) {
            val stream = streamOf(i)
            if (stream != null) {
                importUri(stream)
            } else {
                val t = i.getStringExtra(Intent.EXTRA_TEXT)
                if (!t.isNullOrBlank()) importText(t, i.getStringExtra(Intent.EXTRA_SUBJECT))
            }
            i.action = null
        } else if (act == Intent.ACTION_VIEW) {
            val d = i.data
            if (d != null) importUri(d)
            i.action = null
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun ensureKeys(): Boolean {
        if (Prefs.keys(this).isNotEmpty()) return true
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dlg_key_title))
            .setMessage(getString(R.string.dlg_key_msg))
            .setPositiveButton(getString(R.string.btn_open)) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
        return false
    }

    private fun importUri(uri: Uri) {
        if (!ensureKeys()) return
        val name = Util.displayName(this, uri) ?: getString(R.string.default_book_title)
        toast(getString(R.string.toast_receiving))
        val app = applicationContext
        Thread {
            var jobId: String? = null
            try {
                val job = JobStore.create(Util.stripExt(name), name)
                jobId = job.id
                val dst = JobStore.original(job.id)
                val ins = app.contentResolver.openInputStream(uri)
                if (ins == null) throw java.io.IOException(getString(R.string.err_open_file))
                ins.use { input -> dst.outputStream().use { out -> input.copyTo(out) } }
                Journal.i("Import", "Fayl nusxalandi: '" + name + "' (" + dst.length() + " bayt)")
                TtsService.start(app, job.id)
            } catch (t: Throwable) {
                Journal.e("Import", "Faylni qabul qilib boʻlmadi: " + name, t)
                val jid = jobId
                if (jid != null) JobStore.delete(jid)
                runOnUiThread { toast(getString(R.string.toast_read_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun onTextClicked() {
        val t = etText.text.toString()
        if (t.isBlank()) {
            toast(getString(R.string.toast_paste_first))
            return
        }
        importText(t, etTitle.text.toString())
    }

    private fun firstLine(s: String): String {
        val line = s.trimStart().lineSequence().firstOrNull() ?: ""
        val t = Util.snippet(line, 60)
        return if (t.isEmpty()) getString(R.string.default_text_title) else t.removeSuffix("…")
    }

    private fun importText(text: String, titleHint: String?) {
        if (!ensureKeys()) return
        val app = applicationContext
        Thread {
            var jobId: String? = null
            try {
                val cleaned = TextTools.clean(text)
                if (cleaned.isBlank()) {
                    runOnUiThread { toast(getString(R.string.toast_text_empty)) }
                } else {
                    val hint = titleHint?.trim() ?: ""
                    val ttl = if (hint.isNotEmpty()) hint else firstLine(cleaned)
                    val job = JobStore.create(ttl, "")
                    jobId = job.id
                    JobStore.source(job.id).writeText(cleaned, Charsets.UTF_8)
                    JobStore.update(job.id) {
                        it.total = cleaned.length
                        it.preview = Util.snippet(cleaned, 160)
                    }
                    Journal.i("Import", "Matn qabul qilindi: '" + ttl + "' (" + cleaned.length + " belgi)")
                    runOnUiThread {
                        etText.setText("")
                        etTitle.setText("")
                    }
                    TtsService.start(app, job.id)
                }
            } catch (t: Throwable) {
                Journal.e("Import", "Matnni qabul qilib boʻlmadi", t)
                val jid = jobId
                if (jid != null) JobStore.delete(jid)
                runOnUiThread { toast(getString(R.string.toast_text_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    // ---------------------------------------------------------------- ro'yxat

    private fun updateInfo() {
        val n = Prefs.keys(this).size
        tvKeys.text = if (n == 0) {
            getString(R.string.main_keys_none)
        } else {
            getString(R.string.main_keys_count, n, Prefs.MAX_KEYS)
        }
        val ec = Journal.errorCount()
        tvJournal.text = if (ec == 0) {
            getString(R.string.main_journal_none)
        } else {
            getString(R.string.main_journal_errors, ec, Journal.lastError().take(160))
        }
    }

    private fun refresh() {
        val jobs = JobStore.all()
        val ids = HashSet<String>()
        for (j in jobs) ids.add(j.id)
        val iter = cards.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (!ids.contains(e.key)) {
                jobsBox.removeView(e.value.root)
                iter.remove()
            }
        }
        tvEmpty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
        for ((idx, j) in jobs.withIndex()) {
            var c = cards[j.id]
            if (c == null) {
                c = makeCard(j.id)
                cards[j.id] = c
                jobsBox.addView(c.root, if (idx <= jobsBox.childCount) idx else jobsBox.childCount)
            }
            bind(c, j)
        }
        updateInfo()
    }

    private fun makeCard(id: String): Card {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 14)
        root.setPadding(p, p, p, p)
        root.setBackgroundResource(R.drawable.card_bg)
        root.isClickable = true
        root.isFocusable = true
        root.layoutParams = Ui.lp(this, 12)
        val title = Ui.text(this, "", 18f, true, 0)
        val status = Ui.text(this, "", 15f, false, 6)
        root.addView(title)
        root.addView(status)
        root.setOnClickListener { showActions(id) }
        return Card(root, title, status)
    }

    /** Matnni faqat oʻzgargandagina yangilaymiz — TalkBack fokusi va oʻqishi buzilmasligi uchun. */
    private fun bind(c: Card, j: Job) {
        if (c.title.text.toString() != j.title) c.title.text = j.title
        val st = statusText(j)
        if (c.status.text.toString() != st) c.status.text = st
    }

    private fun statusText(j: Job): String {
        val pct = if (j.total > 0) (j.offset.toLong() * 100L / j.total).toInt() else 0
        val sb = StringBuilder()
        val head = when (j.state) {
            JobState.NEW -> getString(R.string.st_new)
            JobState.PREPARING -> getString(R.string.st_preparing)
            JobState.QUEUED -> getString(R.string.st_queued)
            JobState.RUNNING -> getString(R.string.st_running)
            JobState.WAITING -> getString(R.string.st_waiting)
            JobState.PAUSED -> getString(R.string.st_paused)
            JobState.LIMIT -> getString(R.string.st_limit)
            JobState.DONE -> getString(R.string.st_done)
            JobState.FAILED -> getString(R.string.st_failed)
        }
        sb.append(head)
        if (j.state == JobState.DONE) sb.append(" — 100%")
        // Foiz faqat matn tayyor boʻlgach koʻrsatiladi (skanerlangan PDF oʻqilayotganda foiz yoʻq).
        val withPct = j.total > 0 && (j.state == JobState.RUNNING || j.state == JobState.WAITING ||
            j.state == JobState.PAUSED || j.state == JobState.LIMIT || j.state == JobState.FAILED)
        if (withPct) sb.append(" — ").append(pct).append("%")
        val msg = Msg.text(this, j.message)
        if (msg.isNotBlank()) sb.append("\n").append(msg)
        if (j.audioMs > 0L) {
            sb.append("\n").append(getString(R.string.st_audio_len, Util.fmtDuration(this, j.audioMs), j.format.uppercase()))
        }
        if (j.offset > 0 && j.state != JobState.DONE) {
            val place = Msg.text(this, j.posLabel)
            sb.append("\n")
            if (place.isNotBlank()) {
                sb.append(getString(R.string.st_position_label, place, j.offset, j.total))
            } else {
                sb.append(getString(R.string.st_position, j.offset, j.total))
            }
        }
        if (j.lastSnippet.isNotBlank() && j.state != JobState.DONE) {
            sb.append("\n").append(getString(R.string.st_last_snippet, j.lastSnippet))
        }
        if (j.skipped > 0) sb.append("\n").append(getString(R.string.st_skipped, j.skipped))
        val stopped = j.state == JobState.PAUSED || j.state == JobState.LIMIT || j.state == JobState.FAILED
        if (stopped && j.total > 0 && j.offset < j.total) {
            sb.append("\n").append(getString(R.string.st_remainder_ready))
        }
        if (stopped && j.total == 0 && JobStore.partial(j.id).exists()) {
            sb.append("\n").append(getString(R.string.st_ocr_partial_ready))
        }
        if (j.preview.isNotBlank() && j.offset == 0 && j.state != JobState.DONE) {
            sb.append("\n").append(getString(R.string.st_preview, j.preview))
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- amallar menyusi

    private fun mimeOf(j: Job): String = if (j.format == "wav") "audio/x-wav" else "audio/mpeg"

    private fun showActions(id: String) {
        val j = JobStore.get(id) ?: return
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        val working = j.state in JobStore.ACTIVE && j.state != JobState.NEW
        val hasPartial = JobStore.partial(id).exists()
        if (working) {
            labels.add(getString(R.string.act_pause))
            acts.add { TtsService.pause(this, id) }
        } else if (j.state != JobState.DONE) {
            labels.add(if (j.offset > 0 || hasPartial) getString(R.string.act_resume) else getString(R.string.act_start))
            acts.add { TtsService.start(this, id, true) }
        }
        if (j.audioBytes > 0L) {
            labels.add(getString(R.string.act_save_audio, j.format.uppercase()))
            acts.add { saveAudio(j) }
            labels.add(getString(R.string.act_share_audio))
            acts.add { shareFile(JobStore.audio(j), mimeOf(j)) }
            labels.add(getString(R.string.act_play_audio))
            acts.add { playFile(JobStore.audio(j), mimeOf(j)) }
        }
        if (j.total > 0 && j.offset < j.total && j.state != JobState.PREPARING) {
            labels.add(getString(R.string.act_save_rest))
            acts.add { exportRemainder(id, false) }
            labels.add(getString(R.string.act_share_rest))
            acts.add { exportRemainder(id, true) }
        }
        if (j.total == 0 && hasPartial && !working) {
            labels.add(getString(R.string.act_save_ocr))
            acts.add { exportPartial(id, false) }
            labels.add(getString(R.string.act_share_ocr))
            acts.add { exportPartial(id, true) }
        }
        if (j.skipped > 0) {
            labels.add(getString(R.string.act_save_skipped))
            acts.add { exportSkipped(id) }
        }
        if (!working) {
            labels.add(getString(R.string.act_delete))
            acts.add { confirmDelete(id, j.title) }
        }
        AlertDialog.Builder(this)
            .setTitle(j.title)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun saveAudio(j: Job) {
        val f = JobStore.audio(j)
        if (!f.exists()) {
            toast(getString(R.string.toast_audio_missing))
            return
        }
        pendingExport = f
        if (j.format == "wav") saveWav.launch(f.name) else saveMp3.launch(f.name)
    }

    private fun shareFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = mime
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, getString(R.string.chooser_share)))
        } catch (t: Throwable) {
            Journal.e("Export", "Ulashib boʻlmadi: " + f.name, t)
            toast(getString(R.string.toast_share_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun playFile(f: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.toast_no_player))
        } catch (t: Throwable) {
            Journal.e("Export", "Ijro etib boʻlmadi: " + f.name, t)
            toast(getString(R.string.toast_play_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    /** Audiolashtirilmagan qolgan matn — faqat oʻsha matnning oʻzi, hech narsa qoʻshilmagan TXT. Nomi = kitob nomi. */
    private fun exportRemainder(id: String, share: Boolean) {
        Thread {
            try {
                val j = JobStore.get(id)
                if (j == null) {
                    runOnUiThread { toast(getString(R.string.toast_job_missing)) }
                } else {
                    val text = JobStore.source(id).readText(Charsets.UTF_8)
                    val rest = text.substring(minOf(j.offset, text.length)).trim()
                    if (rest.isEmpty()) {
                        runOnUiThread { toast(getString(R.string.toast_no_rest)) }
                    } else {
                        val f = JobStore.remainder(j)
                        f.writeText(rest, Charsets.UTF_8)
                        runOnUiThread {
                            if (share) {
                                shareFile(f, "text/plain")
                            } else {
                                pendingExport = f
                                saveTxt.launch(f.name)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Journal.e("Export", "Qolgan matnni tayyorlab boʻlmadi", t)
                runOnUiThread { toast(getString(R.string.toast_prepare_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    /** Skanerlangan PDF: OCR bilan shu paytgacha oʻqilgan matn (TXT). Nomi = kitob nomi, hech narsa qoʻshilmagan. */
    private fun exportPartial(id: String, share: Boolean) {
        Thread {
            try {
                val j = JobStore.get(id)
                val src = JobStore.partial(id)
                if (j == null || !src.exists()) {
                    runOnUiThread { toast(getString(R.string.toast_no_ocr)) }
                } else {
                    val f = JobStore.remainder(j)
                    f.writeText(src.readText(Charsets.UTF_8), Charsets.UTF_8)
                    runOnUiThread {
                        if (share) {
                            shareFile(f, "text/plain")
                        } else {
                            pendingExport = f
                            saveTxt.launch(f.name)
                        }
                    }
                }
            } catch (t: Throwable) {
                Journal.e("Export", "OCR matnini tayyorlab boʻlmadi", t)
                runOnUiThread { toast(getString(R.string.toast_prepare_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun exportSkipped(id: String) {
        Thread {
            try {
                val j = JobStore.get(id)
                val src = JobStore.skipped(id)
                if (j == null || !src.exists()) {
                    runOnUiThread { toast(getString(R.string.toast_no_skipped)) }
                } else {
                    val f = JobStore.skippedExport(j, getString(R.string.skipped_file_suffix))
                    f.writeText(src.readText(Charsets.UTF_8), Charsets.UTF_8)
                    runOnUiThread {
                        pendingExport = f
                        saveTxt.launch(f.name)
                    }
                }
            } catch (t: Throwable) {
                Journal.e("Export", "Oʻtkazilgan qismlarni tayyorlab boʻlmadi", t)
                runOnUiThread { toast(getString(R.string.toast_prepare_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun onSaved(uri: Uri?) {
        val src = pendingExport ?: return
        if (uri == null) return
        Thread {
            try {
                val os = contentResolver.openOutputStream(uri, "w")
                if (os == null) throw java.io.IOException(getString(R.string.err_create_file))
                os.use { out -> src.inputStream().use { input -> input.copyTo(out) } }
                Journal.i("Export", "Saqlandi: " + src.name + " (" + src.length() + " bayt)")
                runOnUiThread { toast(getString(R.string.toast_saved)) }
            } catch (t: Throwable) {
                Journal.e("Export", "Saqlab boʻlmadi: " + src.name, t)
                runOnUiThread { toast(getString(R.string.toast_save_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun confirmDelete(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.act_delete))
            .setMessage(getString(R.string.dlg_delete_msg, name))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                JobStore.delete(id)
                Journal.i("UI", "Ish oʻchirildi: " + name)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val sp = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (sp.getBoolean("askedNotif", false)) return
        sp.edit().putBoolean("askedNotif", true).apply()
        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
