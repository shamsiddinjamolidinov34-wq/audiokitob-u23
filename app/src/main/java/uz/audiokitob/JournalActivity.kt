package uz.audiokitob

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/** Xato jurnali ekrani: koʻrish, ulashish, TXT sifatida saqlash, nusxalash, sinash, tozalash. */
class JournalActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvLog: TextView
    private var pendingExport: File? = null

    private val saveTxt = registerForActivityResult(CreateDoc("text/plain")) { uri: Uri? -> onSaved(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.journal_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Ui.page(this)
        tvSummary = Ui.text(this, "", 15f)
        page.addView(tvSummary)
        page.addView(Ui.button(this, getString(R.string.jr_share)) { shareLog() })
        page.addView(Ui.button(this, getString(R.string.jr_save)) { saveLog() })
        page.addView(Ui.button(this, getString(R.string.jr_copy)) { copyLog() })
        page.addView(Ui.button(this, getString(R.string.jr_refresh)) { refresh() })
        page.addView(Ui.button(this, getString(R.string.jr_selftest)) { selfTest() })
        page.addView(Ui.button(this, getString(R.string.jr_clear)) { confirmClear() })
        page.addView(Ui.heading(this, getString(R.string.jr_heading)))
        tvLog = Ui.text(this, "", 12f)
        tvLog.typeface = Typeface.MONOSPACE
        tvLog.setTextIsSelectable(true)
        page.addView(tvLog)
        setContentView(Ui.scroll(this, page))
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val n = Journal.errorCount()
        val sb = StringBuilder()
        sb.append(if (n == 0) getString(R.string.jr_none) else getString(R.string.jr_count, n))
        val last = Journal.lastErrors(5)
        if (last.isNotEmpty()) {
            sb.append(" ").append(getString(R.string.jr_last))
            for (l in last) sb.append("\n• ").append(l.take(220))
        }
        sb.append("\n").append(getString(R.string.jr_note))
        tvSummary.text = sb.toString()
        val tail = Journal.readTail(60000)
        tvLog.text = if (tail.isEmpty()) getString(R.string.jr_empty) else tail
    }

    private fun shareLog() {
        val f = Journal.exportFile()
        if (f == null) {
            toast(getString(R.string.jr_toast_prepare_failed))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, getString(R.string.jr_chooser)))
        } catch (t: Throwable) {
            Journal.e("Jurnal", "Jurnalni ulashib boʻlmadi", t)
            toast(getString(R.string.toast_share_failed, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun saveLog() {
        val f = Journal.exportFile()
        if (f == null) {
            toast(getString(R.string.jr_toast_prepare_failed))
            return
        }
        pendingExport = f
        saveTxt.launch(f.name)
    }

    private fun onSaved(uri: Uri?) {
        val src = pendingExport ?: return
        if (uri == null) return
        Thread {
            try {
                val os = contentResolver.openOutputStream(uri, "w")
                if (os == null) throw java.io.IOException(getString(R.string.err_create_file))
                os.use { out -> src.inputStream().use { input -> input.copyTo(out) } }
                runOnUiThread { toast(getString(R.string.jr_toast_saved)) }
            } catch (t: Throwable) {
                Journal.e("Jurnal", "Jurnalni saqlab boʻlmadi", t)
                runOnUiThread { toast(getString(R.string.toast_save_failed, t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }

    private fun copyLog() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.jr_clip_label), Journal.readTail(60000)))
            toast(getString(R.string.jr_toast_copied))
        } catch (t: Throwable) {
            toast(getString(R.string.jr_toast_copy_failed))
        }
    }

    private fun selfTest() {
        Journal.i("Sinov", "Sinov yozuvi (maʼlumot)")
        Journal.w("Sinov", "Sinov ogohlantirishi")
        Journal.e("Sinov", "Sinov xatosi — bu haqiqiy xato emas, jurnal ishlayotganini tekshirish uchun", RuntimeException("sinov istisnosi"))
        refresh()
        toast(getString(R.string.jr_toast_selftest))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.jr_clear))
            .setMessage(getString(R.string.jr_clear_msg))
            .setPositiveButton(getString(R.string.jr_clear_btn)) { _, _ ->
                Journal.clear()
                Journal.i("Jurnal", "Jurnal tozalandi")
                refresh()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
