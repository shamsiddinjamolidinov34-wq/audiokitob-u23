package uz.audiokitob

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Fon xizmati: navbatdagi ishlarni birma-bir audiolashtiradi.
 * Har bir boʻlakdan keyin audio faylga yoziladi va ish holati diskka saqlanadi — ilova oʻchsa ham
 * kelgan joyidan davom etadi. Barcha muhim hodisalar va xatolar xato jurnaliga yoziladi.
 */
class TtsService : Service() {

    companion object {
        const val ACTION_START = "uz.audiokitob.START"
        const val ACTION_PAUSE = "uz.audiokitob.PAUSE"
        const val ACTION_PAUSE_ALL = "uz.audiokitob.PAUSE_ALL"
        const val EXTRA_ID = "id"
        const val EXTRA_RESET = "reset"
        private const val NOTIF_ID = 101
        private const val MIN_CHUNK = 300

        /** OCR: bir kalit/model uchun ketma-ket soʻrovlar orasidagi eng kam tanaffus (qoʻllanmadagi 3,5 soniya). */
        private const val OCR_GAP_MS = 3500L

        /** Foydalanuvchi "Pauza" bosganini bildiruvchi xabar belgisi (til oʻzgarsa ham bir xil qoladi). */
        private val USER_PAUSE: String by lazy { Msg.make(R.string.msg_stopped) }

        @Volatile
        var running = false

        fun start(ctx: Context, id: String, reset: Boolean = false) {
            try {
                val i = Intent(ctx, TtsService::class.java)
                i.action = ACTION_START
                i.putExtra(EXTRA_ID, id)
                i.putExtra(EXTRA_RESET, reset)
                ContextCompat.startForegroundService(ctx, i)
            } catch (t: Throwable) {
                Journal.e("Service", "Fon xizmatini ishga tushirib boʻlmadi", t)
                JobStore.update(id) {
                    it.state = JobState.PAUSED
                    it.message = Msg.make(R.string.msg_start_failed, t.javaClass.simpleName)
                }
            }
        }

        fun pause(ctx: Context, id: String) {
            try {
                val i = Intent(ctx, TtsService::class.java)
                i.action = ACTION_PAUSE
                i.putExtra(EXTRA_ID, id)
                ctx.startService(i)
            } catch (t: Throwable) {
                Journal.e("Service", "Pauza buyrugʻini yuborib boʻlmadi", t)
            }
        }
    }

    private class Cfg(val voice: String, val style: String, val chunk: Int)

    private sealed class Out {
        class Audio(val pcm: ByteArray, val rate: Int, val slot: KeyPool.Slot) : Out()
        class Bad(val why: String) : Out()
        class Stop(val state: JobState, val msg: String) : Out()
    }

    /** OCR soʻrovi natijasi (TTS dagi Out dan alohida: ikkala sinf ham `when` da toʻliq sanab oʻtiladi). */
    private sealed class OcrOut {
        class Text(val text: String, val finish: String) : OcrOut()
        class Bad(val why: String) : OcrOut()
        /** Soʻrov katta (koʻp sahifa) boʻlgani uchun tarmoq koʻtarmadi: guruhni kichraytirib qayta yuborish kerak. */
        class Smaller(val why: String) : OcrOut()
        class Stop(val state: JobState, val msg: String) : OcrOut()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = java.util.LinkedList<String>()
    private var worker: Thread? = null
    private var lastStartId = 0

    @Volatile
    private var curId: String? = null

    @Volatile
    private var stopCur = false
    private var wake: PowerManager.WakeLock? = null

    /** Tilga bogʻliq xabar belgisi (diskka yoziladi, koʻrsatilganda joriy tilga aylanadi). */
    private fun tok(id: Int, vararg a: Any?): String = Msg.make(id, *a)

    /** Belgini joriy tildagi matnga aylantiradi (bildirishnoma va jurnal uchun). Oddiy matn oʻzgarmaydi. */
    private fun txt(stored: String): String = Msg.text(this, stored)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Journal.i("Service", "Xizmat yaratildi")
    }

    override fun onDestroy() {
        running = false
        stopCur = true
        Gemini.cancelActive()
        releaseWake()
        Journal.i("Service", "Xizmat toʻxtadi")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lock) { lastStartId = startId }
        try {
            goForeground(getString(R.string.notif_working))
        } catch (t: Throwable) {
            Journal.e("Service", "startForeground xatosi (fon ruxsati/bildirishnoma sozlamalarini tekshiring)", t)
        }
        val act = intent?.action
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent == null) {
            recover()
        } else if (act == ACTION_START && id != null) {
            enqueue(id, intent.getBooleanExtra(EXTRA_RESET, false))
        } else if (act == ACTION_PAUSE && id != null) {
            pauseJob(id)
        } else if (act == ACTION_PAUSE_ALL) {
            pauseAll()
        }
        ensureWorker()
        return START_STICKY
    }

    // ---------------------------------------------------------------- navbat

    private fun recover() {
        val ids = ArrayList<String>()
        for (j in JobStore.all()) if (j.state in JobStore.ACTIVE) ids.add(j.id)
        Journal.w("Service", "Xizmat qayta ishga tushdi (tizim oʻldirgan boʻlishi mumkin). Tiklanadigan ishlar: " + ids.size)
        for (x in ids) enqueue(x, false)
    }

    private fun enqueue(id: String, reset: Boolean) {
        if (reset) KeyPool.clearStates(applicationContext)
        var added = false
        synchronized(lock) {
            if (curId != id && !queue.contains(id)) {
                queue.add(id)
                added = true
            }
        }
        if (added) {
            JobStore.update(id) {
                if (it.state != JobState.RUNNING) {
                    it.state = JobState.QUEUED
                    it.message = tok(R.string.msg_queued)
                }
            }
            Journal.i("Service", "Navbatga qoʻshildi: " + id + (if (reset) " (kalit holati tiklandi)" else ""))
        }
    }

    private fun pauseJob(id: String) {
        if (curId == id) {
            stopCur = true
            Gemini.cancelActive()
            return
        }
        var removed = false
        synchronized(lock) { removed = queue.remove(id) }
        if (removed) {
            JobStore.update(id) {
                it.state = JobState.PAUSED
                it.message = tok(R.string.msg_unqueued)
            }
        }
    }

    private fun pauseAll() {
        stopCur = true
        Gemini.cancelActive()
        val ids = ArrayList<String>()
        synchronized(lock) {
            ids.addAll(queue)
            queue.clear()
        }
        for (x in ids) {
            JobStore.update(x) {
                it.state = JobState.PAUSED
                it.message = USER_PAUSE
            }
        }
    }

    private fun ensureWorker() {
        var idle = false
        var exitId = 0
        synchronized(lock) {
            if (worker == null) {
                if (queue.isEmpty()) {
                    idle = true
                    exitId = lastStartId
                } else {
                    val t = Thread({ workLoop() }, "tts-worker")
                    worker = t
                    t.start()
                }
            }
        }
        if (idle) finishService(exitId)
    }

    private fun finishService(exitId: Int) {
        if (stopSelfResult(exitId)) {
            try {
                stopForeground(true)
            } catch (t: Throwable) {
            }
        }
    }

    private fun workLoop() {
        acquireWake()
        var exitId = 0
        try {
            while (true) {
                var id: String? = null
                synchronized(lock) {
                    id = queue.poll()
                    if (id == null) {
                        worker = null
                        exitId = lastStartId
                    }
                }
                val cur = id ?: break
                runOne(cur)
            }
        } catch (t: Throwable) {
            Journal.e("Service", "Ishchi oqim kutilmagan xato bilan tugadi", t)
            synchronized(lock) {
                worker = null
                exitId = lastStartId
            }
        } finally {
            releaseWake()
            val ex = exitId
            handler.post { finishService(ex) }
        }
    }

    private fun runOne(id: String) {
        stopCur = false
        curId = id
        val j0 = JobStore.get(id)
        if (j0 == null) {
            curId = null
            return
        }
        try {
            process(id)
        } catch (t: Throwable) {
            Journal.e("Job", "Kutilmagan xato: '" + j0.title + "'", t)
            val m = tok(R.string.msg_unexpected, t.javaClass.simpleName + ": " + (t.message ?: ""))
            JobStore.update(id) {
                it.state = JobState.FAILED
                it.message = m
            }
            notifyResult(getString(R.string.notif_failed_title, j0.title), txt(m), id)
        } finally {
            curId = null
        }
    }

    // ---------------------------------------------------------------- asosiy ish

    private fun prepare(id: String, job: Job): Boolean {
        JobStore.update(id) {
            it.state = JobState.PREPARING
            it.message = tok(R.string.msg_preparing)
        }
        updateNotif(job.title, getString(R.string.msg_preparing))
        val orig = JobStore.original(id)
        if (!orig.exists()) {
            fail(id, tok(R.string.msg_orig_missing), null)
            return false
        }
        // Skanerlangan PDF: oʻqilgan sahifalar shu keshda saqlanadi (limit tugasa yoki ilova oʻchsa ham qayta oʻqilmaydi).
        val cache = OcrCache(JobStore.ocrDir(id))
        val ocr: OcrSupport? = if (Prefs.ocrEnabled(this)) {
            OcrSupport(cache) { need, total -> ocrRun(id, orig, cache, need, total) }
        } else {
            null
        }
        val ex = try {
            Extractors.extract(applicationContext, orig, job.srcName, ocr) { p ->
                JobStore.update(id) { it.message = p }
                updateNotif(job.title, txt(p))
            }
        } catch (e: OcrInterrupted) {
            savePartial(id, e.partial)
            val m = if (e.msg == USER_PAUSE) e.msg else tok(R.string.msg_ocr_interrupted, txt(e.msg), e.done, e.total)
            Journal.w("OCR", "Toʻxtadi (" + e.state.name + "): " + txt(m))
            finishStop(id, e.state, m, true)
            return false
        } catch (e: ExtractException) {
            fail(id, e.token(), e)
            return false
        } catch (t: Throwable) {
            fail(id, tok(R.string.msg_read_failed_x, t.javaClass.simpleName + ": " + (t.message ?: "")), t)
            return false
        }
        try {
            JobStore.source(id).writeText(ex.text, Charsets.UTF_8)
            TextTools.saveMarks(JobStore.marks(id), ex.marks)
        } catch (t: Throwable) {
            fail(id, tok(R.string.msg_save_text_failed, t.message ?: ""), t)
            return false
        }
        JobStore.update(id) {
            it.total = ex.text.length
            it.preview = Util.snippet(ex.text, 160)
            it.message = ex.warning ?: ""
        }
        val warn = ex.warning
        if (warn != null) Journal.w("Job", txt(warn))
        if (ex.unreadable.isNotEmpty()) recordUnreadable(id, ex)
        try {
            orig.delete()
        } catch (t: Throwable) {
        }
        cache.clear()
        try {
            JobStore.partial(id).delete()
        } catch (t: Throwable) {
        }
        Journal.i("Job", "Matn tayyor: " + ex.text.length + " belgi, joy belgilari: " + ex.marks.size)
        return true
    }

    // ---------------------------------------------------------------- skanerlangan PDF: sahifalarni AI (Gemini Vision) bilan oʻqish

    /** OCR toʻxtab qolganda shu paytgacha oʻqilgan matn (raqamli + OCR) alohida faylga yoziladi: uni TXT sifatida olish mumkin. */
    private fun savePartial(id: String, partial: Extracted?) {
        if (partial == null || partial.text.isBlank()) return
        try {
            JobStore.partial(id).writeText(partial.text, Charsets.UTF_8)
        } catch (t: Throwable) {
            Journal.w("OCR", "Qisman matnni saqlab boʻlmadi", t)
        }
    }

    /** OCR bilan ham oʻqib boʻlmagan sahifalar oʻtkazib yuborilganlar roʻyxatiga yoziladi: hech narsa jim yoʻqolmaydi. */
    private fun recordUnreadable(id: String, ex: Extracted) {
        val n = ex.unreadable.size
        try {
            val sb = StringBuilder()
            for (u in ex.unreadable) {
                sb.append(getString(R.string.skip_ocr_line, u.first, u.second)).append("\n\n")
            }
            JobStore.skipped(id).appendText(sb.toString(), Charsets.UTF_8)
            JobStore.update(id) { it.skipped += n }
            Journal.w("OCR", "Oʻqib boʻlmagan sahifalar: " + n)
        } catch (t: Throwable) {
            Journal.w("OCR", "Oʻqib boʻlmagan sahifalarni yozib boʻlmadi", t)
        }
    }

    private fun setPreparing(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.PREPARING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.PREPARING
            it.message = msg
        }
        updateNotif(j.title, msg)
    }

    /** Sahifani JPEG ga aylantiradi. Xotira yetmasa rasm kichraytiriladi; boʻlmasa null. */
    private fun renderPage(r: PageRenderer, page: Int, maxSide: Int): ByteArray? {
        var side = maxSide
        var tries = 0
        while (tries < 3) {
            try {
                return r.jpeg(page, side)
            } catch (e: OutOfMemoryError) {
                Journal.w("OCR", "Sahifa " + page + ": xotira yetmadi, rasm kichraytirilmoqda (" + side + " px)")
                side = side * 2 / 3
            } catch (t: Throwable) {
                Journal.e("OCR", "Sahifa " + page + " rasmga aylanmadi", t)
                return null
            }
            tries++
        }
        return null
    }

    /**
     * Skanerlangan (matnsiz, buzilgan yoki juda qisqa) sahifalarni Gemini Vision orqali oʻqib, keshga yozadi.
     * Ketma-ket sahifalar guruhlanadi (odatda 4 tadan; Sozlamalarda oʻzgartiriladi). Raqamli matni yetarli sahifalar bu yerga kelmaydi.
     * Toʻxtab qolsa (limit, pauza, tarmoq, xato) OcrInterrupted tashlanadi; oʻqilgan sahifalar keshda qoladi va
     * "Davom ettirish" bosilganda shu joydan davom etadi.
     */
    private fun ocrRun(id: String, pdf: File, cache: OcrCache, need: List<Int>, totalPages: Int) {
        cache.clearBad()
        val doneBefore = cache.donePages(false)
        val todo = ArrayList<Int>()
        for (p in need) if (!doneBefore.contains(p)) todo.add(p)
        Journal.i(
            "OCR",
            "Boshlandi: skaner sahifalar " + need.size + ", oldin oʻqilgan " + (need.size - todo.size) + ", qolgan " + todo.size
        )
        if (todo.isEmpty()) return

        val needSet = HashSet<Int>(need)
        fun doneCount(): Int {
            var c = 0
            for (p in cache.donePages(false)) if (needSet.contains(p)) c++
            return c
        }

        fun stopWith(state: JobState, msg: String): OcrInterrupted = OcrInterrupted(state, msg, null, doneCount(), need.size)

        val pool = KeyPool(applicationContext, true)
        pool.reload()
        if (pool.keyCount() == 0) throw stopWith(JobState.FAILED, tok(R.string.msg_no_keys))

        var batchMax = Prefs.ocrPages(this)
        var size = batchMax
        // Tarmoq katta soʻrovni koʻtarmasa, guruh vaqtincha kichrayadi; har bir muvaffaqiyatdan keyin ikki baravar tiklanadi.
        var netCap = batchMax
        val renderer: PageRenderer = try {
            PageRenderer.open(pdf)
        } catch (t: Throwable) {
            Journal.e("OCR", "PDF ni rasmga aylantirish uchun ochib boʻlmadi", t)
            val m = if (t is SecurityException) tok(R.string.err_pdf_password) else tok(R.string.msg_ocr_render_failed, Gemini.describe(t))
            throw stopWith(JobState.FAILED, m)
        }

        val lastCall = HashMap<String, Long>()
        val jpegCache = HashMap<Int, ByteArray>()
        val forceSingle = HashSet<Int>()
        var badStreak = 0
        var markerFails = 0
        var idx = 0

        fun markBad(page: Int, why: String) {
            cache.putBad(page, why)
            jpegCache.remove(page)
            badStreak++
            Journal.e("OCR", "Sahifa " + page + " oʻqilmadi: " + why)
        }

        try {
            while (idx < todo.size) {
                if (stopCur || JobStore.get(id) == null) throw stopWith(JobState.PAUSED, USER_PAUSE)
                refreshWake()

                // Navbatdagi ketma-ket sahifalar guruhi (raqamlari ketma-ket boʻlishi shart: tartib buzilmasligi uchun).
                val firstPage = todo[idx]
                var n = 1
                if (!forceSingle.contains(firstPage)) {
                    while (n < size && idx + n < todo.size) {
                        val nextPage = todo[idx + n]
                        if (nextPage != todo[idx + n - 1] + 1 || forceSingle.contains(nextPage)) break
                        n++
                    }
                }

                // Sahifalarni rasmga aylantirish
                val jpegs = ArrayList<ByteArray>()
                var k = 0
                while (k < n) {
                    val pg = todo[idx + k]
                    var b: ByteArray? = jpegCache[pg]
                    if (b == null) {
                        b = renderPage(renderer, pg, PageRenderer.MAX_SIDE)
                        if (b != null) jpegCache[pg] = b
                    }
                    if (b == null) break
                    jpegs.add(b)
                    k++
                }
                if (k == 0) {
                    // Birinchi sahifaning oʻzi rasmga aylanmadi: oʻtkazib yuboriladi.
                    markBad(firstPage, "rasmga aylantirib boʻlmadi")
                    idx++
                    if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                    continue
                }
                n = k

                val batch = ArrayList<Int>()
                for (j in 0 until n) batch.add(todo[idx + j])
                val lastPage = batch[n - 1]
                val status = if (n > 1) {
                    tok(R.string.msg_ocr_progress, firstPage, lastPage, totalPages)
                } else {
                    tok(R.string.msg_ocr_progress_one, firstPage, totalPages)
                }

                val reply = ocrCall(id, pool, batch, jpegs, lastCall, status)
                when (reply) {
                    is OcrOut.Stop -> throw stopWith(reply.state, reply.msg)
                    is OcrOut.Smaller -> {
                        netCap = maxOf(1, n / 2)
                        size = minOf(batchMax, netCap)
                        Journal.w(
                            "OCR",
                            "Sahifalar " + firstPage + "–" + lastPage + ": tarmoq katta soʻrovni koʻtarmadi (" + reply.why + "); guruh " + size + " sahifagacha kichraytiriladi"
                        )
                    }
                    is OcrOut.Bad -> {
                        if (n > 1) {
                            size = maxOf(1, n / 2)
                            Journal.w(
                                "OCR",
                                "Sahifalar " + firstPage + "–" + lastPage + " oʻqilmadi (" + reply.why + "); guruh " + size + " sahifagacha kichraytiriladi"
                            )
                        } else {
                            markBad(firstPage, reply.why)
                            idx++
                            size = minOf(batchMax, netCap)
                            if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                        }
                    }
                    is OcrOut.Text -> {
                        val fin = reply.finish
                        val complete = fin.isEmpty() || fin == "STOP"
                        var accepted = false
                        if (n > 1) {
                            val parts = if (complete) OcrParse.split(reply.text, batch) else null
                            if (parts != null) {
                                val cleaned = LinkedHashMap<Int, String>()
                                var withText = 0
                                for (pg in batch) {
                                    val t = TextTools.cleanOcrPage(parts[pg] ?: "")
                                    cleaned[pg] = t
                                    if (t.isNotBlank()) withText++
                                }
                                // Boshqa sahifalarda matn bor-u, bir sahifa boʻsh chiqsa — u alohida qayta tekshiriladi
                                // (model sahifani jim tashlab ketgan boʻlishi mumkin; kitobdan sahifa yoʻqolmasligi kerak).
                                val empties = ArrayList<Int>()
                                for (pg in batch) {
                                    val t = cleaned[pg] ?: ""
                                    if (t.isBlank() && withText > 0) {
                                        empties.add(pg)
                                    } else {
                                        cache.putText(pg, pg, t)
                                    }
                                }
                                idx += n
                                if (empties.isNotEmpty()) {
                                    for (e in empties) forceSingle.add(e)
                                    todo.addAll(idx, empties)
                                    Journal.i("OCR", "Boʻsh chiqqan sahifalar alohida tekshiriladi: " + empties.joinToString(", "))
                                }
                                accepted = true
                            } else {
                                markerFails++
                                if (markerFails >= 3 && batchMax > 1) {
                                    batchMax = 1
                                    Journal.w("OCR", "Model sahifa belgilariga ketma-ket amal qilmadi — endi har bir sahifa alohida yuboriladi")
                                }
                                size = minOf(batchMax, maxOf(1, n / 2))
                                Journal.w(
                                    "OCR",
                                    "Sahifalar " + firstPage + "–" + lastPage + ": javob mos kelmadi (finishReason=" + fin + "); guruh " + size + " sahifagacha kichraytiriladi"
                                )
                            }
                        } else {
                            val t = TextTools.cleanOcrPage(reply.text)
                            if (t.isBlank() && !complete) {
                                markBad(firstPage, "finishReason=" + fin)
                                idx++
                                size = minOf(batchMax, netCap)
                                if (badStreak >= 3) throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_3_bad))
                            } else {
                                if (!complete) Journal.w("OCR", "Sahifa " + firstPage + " toʻliq emas boʻlishi mumkin (finishReason=" + fin + ")")
                                cache.putText(firstPage, firstPage, t)
                                idx++
                                accepted = true
                            }
                        }
                        if (accepted) {
                            for (pg in batch) jpegCache.remove(pg)
                            netCap = minOf(batchMax, netCap * 2)
                            size = minOf(batchMax, netCap)
                            badStreak = 0
                        }
                    }
                }
            }
        } finally {
            renderer.close()
        }
        Journal.i("OCR", "Tugadi: oʻqilgan sahifalar " + doneCount() + " / " + need.size)
        // Birorta sahifa oʻqilmagan-u, sabab xato boʻlsa — bu "matn yoʻq" degani emas: ish pauzaga qoʻyiladi, "Davom ettirish" qayta urinadi.
        if (doneCount() == 0 && cache.bads().isNotEmpty()) {
            throw stopWith(JobState.PAUSED, tok(R.string.msg_ocr_none_read))
        }
    }

    /** Bitta OCR soʻrovi: kalit tanlash, limit/tarmoq kutish, qayta urinishlar (TTS dagi synth bilan bir xil qoidalar). */
    private fun ocrCall(
        id: String,
        pool: KeyPool,
        pages: List<Int>,
        jpegs: List<ByteArray>,
        lastCall: HashMap<String, Long>,
        status: String
    ): OcrOut {
        var netFails = 0
        var serverFails = 0
        var badFails = 0
        while (true) {
            if (stopCur || JobStore.get(id) == null) return OcrOut.Stop(JobState.PAUSED, USER_PAUSE)
            if (!Util.isOnline(this)) {
                waitStatus(id, tok(R.string.msg_wait_internet))
                sleepMs(8000L)
                continue
            }
            pool.reload()
            val pick = pool.pick(System.currentTimeMillis())
            if (pick is KeyPool.Pick.None) {
                if (pool.modelCount() == 0) return OcrOut.Stop(JobState.FAILED, tok(R.string.msg_ocr_no_model))
                if (pool.allKeysDead()) return OcrOut.Stop(JobState.FAILED, tok(R.string.msg_all_keys_dead, pool.limitMessage()))
                return OcrOut.Stop(JobState.LIMIT, tok(R.string.msg_text, pool.limitMessage()))
            }
            if (pick is KeyPool.Pick.Wait) {
                waitStatus(id, tok(R.string.msg_wait_limit, pick.ms / 1000L + 1L))
                sleepMs(minOf(pick.ms + 500L, 20000L))
                continue
            }
            val slot = (pick as KeyPool.Pick.Use).slot
            // Bir kalit/model uchun ketma-ket soʻrovlar orasida kamida OCR_GAP_MS tanaffus (daqiqalik limitga urilmaslik uchun).
            val sid = slot.keySlot.toString() + "|" + slot.model
            val gap = OCR_GAP_MS - (System.currentTimeMillis() - (lastCall[sid] ?: 0L))
            if (gap > 0L) sleepMs(gap)
            if (stopCur) return OcrOut.Stop(JobState.PAUSED, USER_PAUSE)
            setPreparing(id, status)
            val r = Gemini.recognize(slot.key, slot.model, pages, jpegs) { stopCur }
            lastCall[sid] = System.currentTimeMillis()
            when (r.kind) {
                Gemini.Kind.OK -> {
                    pool.success(slot)
                    return OcrOut.Text(r.text ?: "", r.finish ?: "")
                }
                Gemini.Kind.CANCELLED -> return OcrOut.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.QUOTA_DAY -> {
                    pool.exhaust(slot)
                    Journal.w("Limit", "OCR: kalit " + slot.keySlot + " / " + slot.model + ": kunlik limit tugadi (HTTP " + r.code + "). " + r.message)
                }
                Gemini.Kind.QUOTA_MIN -> {
                    pool.cooldown(slot, r.retryMs)
                    Journal.w(
                        "Limit",
                        "OCR: kalit " + slot.keySlot + " / " + slot.model + ": daqiqalik limit, " + (r.retryMs / 1000L) + " s kutiladi. " + r.message
                    )
                }
                Gemini.Kind.AUTH -> handleAuth(pool, slot, r)
                Gemini.Kind.REGION -> {
                    Journal.e("Region", "HTTP " + r.code + ": " + r.message)
                    return OcrOut.Stop(JobState.FAILED, tok(R.string.msg_region, r.message))
                }
                Gemini.Kind.MODEL -> handleOcrModel(pool, slot, r)
                Gemini.Kind.SERVER -> {
                    serverFails++
                    Journal.w("Tarmoq", "OCR server xatosi HTTP " + r.code + ": " + r.message + " (urinish " + serverFails + ")")
                    if (r.code == 503) {
                        // 503 "high demand": model serverda band. Bu sahifaning aybi emas — sahifa "oʻqilmadi" deb belgilanmaydi.
                        // Model hamma kalitlarda dam oladi, navbat zaxira modelga oʻtadi; hammasi band boʻlsa — kutiladi.
                        val w = pool.overload(slot.model)
                        Journal.w("Model", "OCR modeli band: " + slot.model + " — " + (w / 1000L) + " s dam oladi, zaxira modelga oʻtiladi")
                        if (serverFails >= 6) return OcrOut.Stop(JobState.PAUSED, tok(R.string.msg_ocr_server_busy, r.code))
                    } else {
                        if (serverFails >= 3) return OcrOut.Bad("server xatosi " + r.code)
                        sleepMs(4000L * serverFails)
                    }
                }
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", "OCR tarmoq xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    // Server javob bermadi (oʻqish muddati tugadi): model band — u dam oladi, navbat zaxira modelga oʻtadi.
                    val lm = r.message.lowercase()
                    if (lm.startsWith("sockettimeoutexception") && !lm.contains("connect")) {
                        val w = pool.overload(slot.model)
                        Journal.w("Model", "OCR modeli javob bermadi: " + slot.model + " — " + (w / 1000L) + " s dam oladi, zaxira modelga oʻtiladi")
                    }
                    // Bir nechta sahifa = katta soʻrov (bir necha MB). Ketma-ket 2 marta tarmoq xatosi boʻlsa — guruh kichraytiriladi.
                    if (netFails >= 2 && pages.size > 1) return OcrOut.Smaller(r.message)
                    if (netFails >= 6) return OcrOut.Stop(JobState.PAUSED, tok(R.string.msg_net_repeat, r.message))
                    waitStatus(id, tok(R.string.msg_net_retry, netFails))
                    sleepMs(minOf(60000L, 5000L * netFails))
                }
                Gemini.Kind.NO_AUDIO, Gemini.Kind.BAD_CHUNK -> {
                    badFails++
                    Journal.w("Gemini", "OCR natijasi yaroqsiz: " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return OcrOut.Bad(r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    private fun handleOcrModel(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        Journal.e("Model", "OCR modeli topilmadi yoki ishlamayapti: " + slot.model + " (HTTP " + r.code + "). " + r.message)
        pool.deadModel(slot.model)
        val alt = Gemini.findVisionModel(slot.key, pool.deadModels())
        if (alt != null) {
            Journal.i("Model", "Muqobil OCR modeli topildi: " + alt)
            if (slot.model == Prefs.ocrModel(this)) Prefs.setOcrModel(this, alt) else Prefs.setOcrBackup(this, alt)
        } else {
            Journal.w("Model", "Muqobil OCR modeli topilmadi")
        }
    }

    private fun prepareAudioFile(id: String, job: Job) {
        val f = JobStore.audio(job)
        try {
            if (f.exists()) {
                if (f.length() > job.audioBytes) {
                    RandomAccessFile(f, "rw").use { it.setLength(job.audioBytes) }
                    Journal.i("Audio", "Yozilib ulgurmagan oxirgi qism kesib tashlandi (" + f.length() + " → " + job.audioBytes + ")")
                } else if (f.length() < job.audioBytes) {
                    val len = f.length()
                    JobStore.update(id) { it.audioBytes = len }
                    Journal.w("Audio", "Audio fayl kutilganidan qisqa: " + len + " < " + job.audioBytes)
                }
            } else if (job.audioBytes > 0L) {
                Journal.w("Audio", "Oldingi audio fayl topilmadi; yangi qism alohida yoziladi")
                JobStore.update(id) {
                    it.audioBytes = 0L
                    it.message = tok(R.string.msg_audio_prev_missing)
                }
            }
        } catch (t: Throwable) {
            Journal.e("Audio", "Audio faylni tayyorlab boʻlmadi", t)
        }
    }

    private fun process(id: String) {
        val job0 = JobStore.get(id) ?: return
        if (!JobStore.source(id).exists()) {
            if (!prepare(id, job0)) return
        }
        val text = try {
            JobStore.source(id).readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            fail(id, tok(R.string.msg_text_read_failed, t.message ?: ""), t)
            return
        }
        var job = JobStore.get(id) ?: return
        if (job.offset >= text.length) {
            finishDone(id)
            return
        }
        val marks = TextTools.loadMarks(JobStore.marks(id))
        val cfg = Cfg(Prefs.voice(this), Prefs.style(this), Prefs.chunk(this))
        val pool = KeyPool(applicationContext)
        pool.reload()
        if (pool.keyCount() == 0) {
            fail(id, tok(R.string.msg_no_keys), null)
            return
        }
        prepareAudioFile(id, job)
        job = JobStore.get(id) ?: return
        Journal.i(
            "Job",
            "Boshlandi/davom: '" + job.title + "' belgi " + job.offset + "/" + text.length +
                ", ovoz " + cfg.voice + ", boʻlak " + cfg.chunk + ", kalitlar " + pool.keyCount()
        )
        JobStore.update(id) {
            it.state = JobState.RUNNING
            it.message = tok(R.string.msg_starting)
        }

        val sink = SinkHolder(id)
        var curMax = cfg.chunk
        var skipStreak = 0
        var offset = job.offset
        try {
            while (true) {
                if (stopCur) {
                    finishStop(id, JobState.PAUSED, USER_PAUSE, true)
                    break
                }
                if (JobStore.get(id) == null) break
                refreshWake()
                while (offset < text.length && text[offset].isWhitespace()) offset++
                if (offset >= text.length) {
                    finishDone(id)
                    break
                }
                val end = TextTools.nextEnd(text, offset, curMax)
                val raw = text.substring(offset, end)
                val speech = TextTools.forSpeech(raw)
                if (!TextTools.hasSpeakable(speech)) {
                    offset = end
                    JobStore.update(id) { it.offset = end }
                    continue
                }

                val first = synth(id, pool, cfg, speech)
                var res: Out = first
                if (first is Out.Audio && speech.length > 400) {
                    val secs0 = first.pcm.size / (2.0 * first.rate)
                    if (implausible(speech, secs0)) {
                        Journal.w(
                            "Audio",
                            "Shubhali davomiylik: " + speech.length + " belgi → " + Util.fmt1(secs0) + " s; boʻlaklanadi"
                        )
                        res = Out.Bad("audio davomiyligi matnga mos emas (" + Util.fmt1(secs0) + " s)")
                    }
                }
                val rr = res
                when (rr) {
                    is Out.Audio -> {
                        val err = sink.append(rr.pcm, rr.rate)
                        if (err != null) {
                            finishStop(id, JobState.FAILED, err, false)
                            Journal.e("Audio", txt(err))
                            break
                        }
                        val ms = rr.pcm.size * 1000L / (2L * rr.rate)
                        val cur = JobStore.get(id)
                        if (cur == null) break
                        val bytes = JobStore.audio(cur).length()
                        val label = TextTools.labelAt(marks, end - 1)
                        offset = end
                        JobStore.update(id) {
                            it.offset = end
                            it.audioMs += ms
                            it.audioBytes = bytes
                            it.chunksDone++
                            it.lastSnippet = Util.tail(raw, 140)
                            it.posLabel = label
                            it.state = JobState.RUNNING
                            it.message = ""
                        }
                        curMax = cfg.chunk
                        skipStreak = 0
                        val secs = rr.pcm.size / (2.0 * rr.rate)
                        Journal.i(
                            "Chunk",
                            "OK " + offset + "/" + text.length + ": " + speech.length + " belgi → " + Util.fmt1(secs) +
                                " s; kalit " + rr.slot.keySlot + "; " + rr.slot.model
                        )
                        val pct = (offset.toLong() * 100L / text.length).toInt()
                        updateNotif(cur.title, getString(R.string.notif_progress, pct, Util.fmtDuration(this, cur.audioMs + ms)))
                    }
                    is Out.Bad -> {
                        val size = end - offset
                        if (size > MIN_CHUNK) {
                            curMax = maxOf(MIN_CHUNK, size / 2)
                            Journal.w("Job", "Qism boʻlaklanmoqda: " + size + " → " + curMax + " belgi. Sabab: " + rr.why)
                        } else {
                            recordSkipped(id, raw, marks, offset, rr.why)
                            offset = end
                            JobStore.update(id) {
                                it.offset = end
                                it.skipped++
                                it.message = tok(R.string.msg_chunk_skipped)
                            }
                            curMax = cfg.chunk
                            skipStreak++
                            if (skipStreak >= 3) {
                                finishStop(id, JobState.PAUSED, tok(R.string.msg_3_skipped), true)
                                break
                            }
                        }
                    }
                    is Out.Stop -> {
                        finishStop(id, rr.state, rr.msg, true)
                        break
                    }
                }
            }
        } finally {
            sink.close()
        }
    }

    private fun implausible(speech: String, secs: Double): Boolean {
        val eff = TextTools.effectiveLen(speech)
        val cps = eff / maxOf(secs, 0.01)
        return cps > 40.0 || cps < 3.0
    }

    private fun recordSkipped(id: String, raw: String, marks: List<Mark>, offset: Int, why: String) {
        val label = txt(TextTools.labelAt(marks, offset))
        try {
            val head = (if (label.isNotEmpty()) getString(R.string.skip_line_label, label, offset, why) else getString(R.string.skip_line, offset, why)) + "\n"
            JobStore.skipped(id).appendText(head + raw.trim() + "\n\n", Charsets.UTF_8)
        } catch (t: Throwable) {
            Journal.e("Job", "Oʻtkazilgan qismni faylga yozib boʻlmadi", t)
        }
        Journal.e(
            "Job",
            "Qism audiolashtirilmadi va oʻtkazib yuborildi (belgi " + offset + "): " + why + " | matn: " + Util.snippet(raw, 80)
        )
    }

    // ---------------------------------------------------------------- Gemini soʻrovi, kalit almashtirish, qayta urinishlar

    private fun synth(id: String, pool: KeyPool, cfg: Cfg, speech: String): Out {
        var netFails = 0
        var serverFails = 0
        var badFails = 0
        while (true) {
            if (stopCur || JobStore.get(id) == null) return Out.Stop(JobState.PAUSED, USER_PAUSE)
            if (!Util.isOnline(this)) {
                waitStatus(id, tok(R.string.msg_wait_internet))
                sleepMs(8000L)
                continue
            }
            pool.reload()
            val pick = pool.pick(System.currentTimeMillis())
            if (pick is KeyPool.Pick.None) {
                if (pool.modelCount() == 0) {
                    return Out.Stop(JobState.FAILED, tok(R.string.msg_no_model))
                }
                if (pool.allKeysDead()) {
                    return Out.Stop(JobState.FAILED, tok(R.string.msg_all_keys_dead, pool.limitMessage()))
                }
                return Out.Stop(JobState.LIMIT, tok(R.string.msg_text, pool.limitMessage()))
            }
            if (pick is KeyPool.Pick.Wait) {
                waitStatus(id, tok(R.string.msg_wait_limit, pick.ms / 1000L + 1L))
                sleepMs(minOf(pick.ms + 500L, 20000L))
                continue
            }
            val slot = (pick as KeyPool.Pick.Use).slot
            setRunning(id, tok(R.string.msg_synth_running, slot.keySlot))
            val r = Gemini.synthesize(slot.key, slot.model, cfg.voice, speech, cfg.style) { stopCur }
            when (r.kind) {
                Gemini.Kind.OK -> {
                    pool.success(slot)
                    val pcm = r.pcm
                    if (pcm != null) return Out.Audio(pcm, r.rate, slot)
                    badFails++
                    if (badFails >= 2) return Out.Bad("boʻsh audio")
                }
                Gemini.Kind.CANCELLED -> return Out.Stop(JobState.PAUSED, USER_PAUSE)
                Gemini.Kind.QUOTA_DAY -> {
                    pool.exhaust(slot)
                    Journal.w("Limit", "Kalit " + slot.keySlot + " / " + slot.model + ": kunlik limit tugadi (HTTP " + r.code + "). " + r.message)
                }
                Gemini.Kind.QUOTA_MIN -> {
                    pool.cooldown(slot, r.retryMs)
                    Journal.w(
                        "Limit",
                        "Kalit " + slot.keySlot + " / " + slot.model + ": daqiqalik limit, " + (r.retryMs / 1000L) + " s kutiladi. " + r.message
                    )
                }
                Gemini.Kind.AUTH -> handleAuth(pool, slot, r)
                Gemini.Kind.REGION -> {
                    Journal.e("Region", "HTTP " + r.code + ": " + r.message)
                    return Out.Stop(JobState.FAILED, tok(R.string.msg_region, r.message))
                }
                Gemini.Kind.MODEL -> handleModel(pool, slot, r)
                Gemini.Kind.SERVER -> {
                    serverFails++
                    Journal.w("Tarmoq", "Server xatosi HTTP " + r.code + ": " + r.message + " (urinish " + serverFails + ")")
                    if (serverFails >= 3) return Out.Bad("server xatosi " + r.code)
                    sleepMs(4000L * serverFails)
                }
                Gemini.Kind.NETWORK -> {
                    netFails++
                    Journal.w("Tarmoq", "Tarmoq xatosi: " + r.message + " (urinish " + netFails + "/6)")
                    if (netFails >= 6) {
                        return Out.Stop(JobState.PAUSED, tok(R.string.msg_net_repeat, r.message))
                    }
                    waitStatus(id, tok(R.string.msg_net_retry, netFails))
                    sleepMs(minOf(60000L, 5000L * netFails))
                }
                Gemini.Kind.NO_AUDIO, Gemini.Kind.BAD_CHUNK -> {
                    badFails++
                    Journal.w("Gemini", "Natija yaroqsiz: " + r.message + " (urinish " + badFails + ")")
                    if (badFails >= 2) return Out.Bad(r.message)
                    sleepMs(1500L)
                }
            }
        }
    }

    private fun handleAuth(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        val chk = Gemini.checkKey(slot.key)
        if (chk.ok) {
            pool.deadSlot(slot)
            Journal.e("Kalit", "Kalit " + slot.keySlot + ": " + slot.model + " modeliga ruxsat yoʻq (HTTP " + r.code + "): " + r.message)
        } else if (chk.code == 0) {
            Journal.w("Kalit", "Kalit " + slot.keySlot + " tekshirilmadi (tarmoq): " + chk.message)
            sleepMs(5000L)
        } else {
            pool.deadKey(slot)
            Journal.e("Kalit", "Kalit " + slot.keySlot + " yaroqsiz (HTTP " + chk.code + "): " + chk.message)
        }
    }

    private fun handleModel(pool: KeyPool, slot: KeyPool.Slot, r: Gemini.Result) {
        Journal.e("Model", "Model topilmadi yoki ishlamayapti: " + slot.model + " (HTTP " + r.code + "). " + r.message)
        pool.deadModel(slot.model)
        val alt = Gemini.findTtsModel(slot.key, pool.deadModels())
        if (alt != null) {
            Journal.i("Model", "Muqobil TTS modeli topildi: " + alt)
            if (slot.model == Prefs.model(this)) Prefs.setModel(this, alt) else Prefs.setBackup(this, alt)
        } else {
            Journal.w("Model", "Muqobil TTS modeli topilmadi")
        }
    }

    // ---------------------------------------------------------------- audio fayl yozish (MP3, zaxira — WAV)

    private inner class SinkHolder(val id: String) {
        private var fos: FileOutputStream? = null

        /** Muvaffaqiyatli boʻlsa null, aks holda xato matni. */
        fun append(pcm: ByteArray, rate: Int): String? {
            var job = JobStore.get(id) ?: return tok(R.string.msg_job_deleted)
            if (job.format == "mp3") {
                try {
                    val bytes = Mp3Writer.encode(pcm, rate)
                    var o = fos
                    if (o == null) {
                        o = FileOutputStream(JobStore.audio(job), true)
                        fos = o
                    }
                    o.write(bytes)
                    o.flush()
                    try {
                        o.fd.sync()
                    } catch (t: Throwable) {
                    }
                    return null
                } catch (t: Throwable) {
                    Journal.e("Mp3", "MP3 kodlash/yozish xatosi", t)
                    if (job.audioBytes > 0L) {
                        return tok(R.string.msg_mp3_failed, t.javaClass.simpleName + ": " + (t.message ?: ""))
                    }
                    close()
                    try {
                        JobStore.audio(job).delete()
                    } catch (x: Throwable) {
                    }
                    JobStore.update(id) {
                        it.format = "wav"
                        it.message = tok(R.string.msg_mp3_fallback)
                    }
                    job = JobStore.get(id) ?: return tok(R.string.msg_job_deleted)
                }
            }
            try {
                WavWriter.append(JobStore.audio(job), pcm, rate)
                return null
            } catch (t: Throwable) {
                Journal.e("Wav", "Audio faylga yozib boʻlmadi", t)
                return tok(R.string.msg_audio_write_failed, t.message ?: t.javaClass.simpleName)
            }
        }

        fun close() {
            try {
                fos?.close()
            } catch (t: Throwable) {
            }
            fos = null
        }
    }

    // ---------------------------------------------------------------- holat, bildirishnomalar

    private fun fail(id: String, msg: String, t: Throwable?) {
        Journal.e("Job", txt(msg), t)
        finishStop(id, JobState.FAILED, msg, false)
    }

    private fun finishDone(id: String) {
        val j0 = JobStore.get(id) ?: return
        val dur = Util.fmtDuration(this, j0.audioMs)
        val msg = if (j0.skipped > 0) tok(R.string.msg_done_skipped, j0.skipped) else tok(R.string.msg_done)
        JobStore.update(id) {
            it.state = JobState.DONE
            it.offset = it.total
            it.message = msg
        }
        Journal.i("Job", "Tugadi: '" + j0.title + "', audio " + dur + (if (j0.skipped > 0) ", oʻtkazib yuborilgan qismlar: " + j0.skipped else ""))
        val text = if (j0.skipped > 0) {
            getString(R.string.notif_done_text_skipped, dur, j0.skipped)
        } else {
            getString(R.string.notif_done_text, dur)
        }
        notifyResult(getString(R.string.notif_done_title, j0.title), text, id)
    }

    private fun finishStop(id: String, state: JobState, msg: String, logIt: Boolean) {
        val j0 = JobStore.get(id) ?: return
        JobStore.update(id) {
            it.state = state
            it.message = msg
        }
        val userPause = state == JobState.PAUSED && msg == USER_PAUSE
        if (userPause) {
            Journal.i("Job", "Toʻxtatildi: '" + j0.title + "' (" + j0.offset + "/" + j0.total + ")")
            return
        }
        if (logIt) {
            Journal.log(if (state == JobState.FAILED) "E" else "W", "Job", "'" + j0.title + "' — " + state.name + ": " + txt(msg))
        }
        val head = when (state) {
            JobState.LIMIT -> getString(R.string.notif_limit_title, j0.title)
            JobState.FAILED -> getString(R.string.notif_failed_title, j0.title)
            else -> getString(R.string.notif_stopped_title, j0.title)
        }
        notifyResult(head, txt(msg), id)
    }

    private fun waitStatus(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.WAITING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.WAITING
            it.message = msg
        }
        updateNotif(j.title, msg)
    }

    private fun setRunning(id: String, msg: String) {
        val j = JobStore.get(id) ?: return
        if (j.state == JobState.RUNNING && j.message == msg) return
        JobStore.update(id) {
            it.state = JobState.RUNNING
            it.message = msg
        }
    }

    private fun sleepMs(ms: Long) {
        var left = ms
        while (left > 0L && !stopCur) {
            val s = if (left < 250L) left else 250L
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                return
            }
            left -= s
        }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val w = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiokitob:work")
            w.setReferenceCounted(false)
            w.acquire(6L * 60L * 60L * 1000L)
            wake = w
        } catch (t: Throwable) {
            Journal.w("Service", "WakeLock olib boʻlmadi", t)
        }
    }

    private fun refreshWake() {
        try {
            wake?.acquire(6L * 60L * 60L * 1000L)
        } catch (t: Throwable) {
        }
    }

    private fun releaseWake() {
        try {
            val w = wake
            if (w != null && w.isHeld) w.release()
        } catch (t: Throwable) {
        }
        wake = null
    }

    private fun pFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun buildNotification(rawTitle: String, rawText: String): Notification {
        val title = txt(rawTitle)
        val text = txt(rawText)
        val openI = Intent(this, MainActivity::class.java)
        openI.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 0, openI, pFlags())
        val pauseI = Intent(this, TtsService::class.java)
        pauseI.action = ACTION_PAUSE_ALL
        val pause = PendingIntent.getService(this, 1, pauseI, pFlags())
        return NotificationCompat.Builder(this, "progress")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.act_pause), pause)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(getString(R.string.app_name), text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(title: String, text: String) {
        try {
            val n = buildNotification(title, text)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        } catch (t: Throwable) {
        }
    }

    private fun notifyResult(title: String, rawText: String, jobId: String) {
        try {
            val text = txt(rawText)
            val openI = Intent(this, MainActivity::class.java)
            openI.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open = PendingIntent.getActivity(this, 2, openI, pFlags())
            val n = NotificationCompat.Builder(this, "result")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(1000 + (jobId.hashCode() and 0xFFFF), n)
        } catch (t: Throwable) {
            Journal.w("Bildirishnoma", "Natija bildirishnomasini yuborib boʻlmadi", t)
        }
    }
}
