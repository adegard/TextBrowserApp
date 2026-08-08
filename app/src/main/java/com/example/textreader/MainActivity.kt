package com.example.textreader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GestureDetectorCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

data class Bookmark(
    val title: String,
    val url: String,
    val blockIndex: Int
)

data class LinkItem(
    val label: String,
    val url: String
)

data class Extracted(
    val paragraphs: List<String>,
    val links: List<LinkItem>,
    val title: String?,
    val image: String?,
    val lang: String = ""
)

data class SentenceRange(
    val start: Int,
    val end: Int,
    val text: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var urlInput: EditText
    private lateinit var contentView: TextView
    private lateinit var goButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var bookmarksButton: Button
    private lateinit var ttsButton: Button
    private lateinit var menuButton: ImageButton
    private lateinit var cancelButton: ImageButton
    private lateinit var progressBar: android.widget.ProgressBar

    private lateinit var gestureDetector: GestureDetectorCompat

    private var blocks: List<String> = emptyList()
    private var currentBlockIndex = 0
    private var currentUrl: String? = null
    private var currentTitle: String = ""
    private var rawParagraphs: List<String> = emptyList()
    private var currentLinks: List<LinkItem> = emptyList()
    private var currentImageUrl: String? = null
    private var isNight = false

    private var searchEngine = "duck_lite"
    private var resultsPerPage = 10
    private var parasPerPage = 2
    private var maxChars = 2000
    private var chronologyLength = 5
    private var groqKey = ""
    private var textSize = 16f
    private var showTitle = true
    private var showPageNumber = true
    private var showCompactUrl = true
    private var ttsMode = "auto"
    private var ttsSpeed = 1.0f
    private var detectedLang = ""

    @Volatile
    private var loadGeneration = 0
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsActive = false
    private var ttsOnline = false
    private var ttsAvailable: Set<Locale> = emptySet()
    private var mediaPlayer: MediaPlayer? = null
    private var onlineSentences: List<SentenceRange> = emptyList()
    private var onlineSentenceIndex = 0
    private var onlineFailures = 0
    private var currentSpannable: SpannableString? = null
    private var headerLength = 0
    private var pageLangCode = ""

    private var pendingBackupJson: String = ""

    private var searchResults: List<Pair<String, String>> = emptyList()
    private var searchOffset = 0
    private var lastSearchEngine = "duck_lite"

    private val engineNames = mapOf(
        "duck_lite" to "DuckDuckGo Lite",
        "duck_html" to "DuckDuckGo HTML",
        "brave" to "Brave Search",
        "google" to "Google (text mode)",
        "bing" to "Bing (text mode)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        urlInput = findViewById(R.id.urlInput)
        contentView = findViewById(R.id.contentView)
        goButton = findViewById(R.id.goButton)
        prevButton = findViewById(R.id.prevButton)
        nextButton = findViewById(R.id.nextButton)
        bookmarksButton = findViewById(R.id.bookmarksButton)
        menuButton = findViewById(R.id.menuButton)
        cancelButton = findViewById(R.id.cancelButton)
        ttsButton = findViewById(R.id.ttsButton)
        progressBar = findViewById(R.id.progressBar)

        loadPrefs()

        gestureDetector = GestureDetectorCompat(
            this,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null) return false

                    val dx = e2.x - e1.x
                    val threshold = 80

                    if (dx > threshold) {
                        previousBlock()
                        return true
                    } else if (dx < -threshold) {
                        nextBlock()
                        return true
                    }

                    return false
                }
            }
        )

        contentView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        goButton.setOnClickListener {
            val input = urlInput.text.toString().trim()
            if (input.isNotEmpty()) handleInput(input)
        }

        bookmarksButton.setOnClickListener { showBookmarksDialog() }
        prevButton.setOnClickListener { previousBlock() }
        nextButton.setOnClickListener { nextBlock() }
        cancelButton.setOnClickListener { urlInput.text.clear() }
        ttsButton.setOnClickListener { toggleTts() }

        setupMenu()

        initTts()

        cleanPdfCache()

        applyTheme()
        showHome()
    }

    // ========= SETUP / MENU =========

    private fun setupMenu() {
        menuButton.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menu.add(0, 1, 0, "Home")
            popup.menu.add(0, 2, 1, "Links")
            popup.menu.add(0, 3, 2, "History")
            popup.menu.add(0, 4, 3, "Settings")
            popup.menu.add(0, 5, 4, "I'm feeling lucky")
            popup.menu.add(0, 6, 5, "Share link")
            popup.menu.add(0, 7, 6, "Ask AI")
            popup.menu.add(0, 8, 7, "Save bookmark")
            popup.menu.add(0, 13, 8, "Remove bookmark")
            popup.menu.add(0, 9, 9, "A+ bigger text")
            popup.menu.add(0, 10, 10, "A− smaller text")
            popup.menu.add(0, 11, 11, "Toggle theme")
            popup.menu.add(0, 12, 12, "Exit")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showHome()
                    2 -> showLinksDialog()
                    3 -> showHistoryDialog()
                    4 -> showSettingsDialog()
                    5 -> feelingLucky()
                    6 -> shareCurrent()
                    7 -> askAi()
                    8 -> {
                        addOrUpdateBookmark()
                        toast("Bookmark saved")
                    }
                    13 -> removeCurrentBookmark()
                    9 -> changeTextSize(2f)
                    10 -> changeTextSize(-2f)
                    11 -> toggleTheme()
                    12 -> finish()
                }
                true
            }
            popup.show()
        }
    }

    // ========= HOME =========

    private fun showHome() {
        stopTtsOnNewPage()
        currentUrl = null
        rawParagraphs = emptyList()
        currentLinks = emptyList()
        currentImageUrl = null
        val msg = """
            ═══ TEXT READER ═══
            Type a URL or search, tap Go.
            ◀ ▶ or swipe to turn pages.
            ⋮ menu: Links · History · Settings · AI
        """.trimIndent()
        showMessage(msg)
    }

    // ========= INPUT HANDLING =========

    private fun handleInput(input: String) {
        val lower = input.lowercase()
        when {
            lower.startsWith("ifl ") -> feelingLucky(input.substringAfter(' '))
            lower.startsWith("http://") || lower.startsWith("https://") -> loadUrl(input)
            else -> {
                val url = normalizeUrl(input)
                if (url != null) loadUrl(url) else searchAndSelect(input)
            }
        }
    }

    private fun normalizeUrl(t: String): String? {
        val trimmed = t.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if ("." in trimmed) return "https://$trimmed"
        return null
    }

    // ========= LOAD / PRESENT =========

    private fun loadUrl(url: String) {
        val gen = ++loadGeneration
        currentUrl = url
        setLoading(true)
        showMessage("Loading…")
        thread {
            try {
                val resolved = resolveRedirect(url)
                if (gen != loadGeneration) return@thread
                currentUrl = resolved
                if (resolved.lowercase().endsWith(".pdf")) {
                    loadPdfInternal(resolved, gen)
                } else {
                    val html = httpGet(resolved)
                    if (gen != loadGeneration) return@thread
                    val ext = extractSinglePage(html, resolved)
                    runOnUiThread {
                        if (gen != loadGeneration) return@runOnUiThread
                        presentArticle(ext, resolved)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen != loadGeneration) return@runOnUiThread
                    showMessage("Error: ${e.message}")
                }
            }
        }
    }

    private fun presentArticle(ext: Extracted, url: String) {
        stopTtsOnNewPage()
        setLoading(false)
        pageLangCode = ext.lang
        currentTitle = ext.title ?: url
        currentLinks = ext.links
        currentImageUrl = ext.image
        rawParagraphs = ext.paragraphs
        val pages = buildTextPages(ext.paragraphs)
        blocks = pages.map { it.joinToString("\n\n") }
        if (blocks.isEmpty()) blocks = listOf("[No readable text]")
        currentBlockIndex = 0
        renderBlock()
        addToHistory(currentTitle, url)
        urlInput.setText(url)
        maybeOfferBookmarkRestore()
    }

    private fun buildTextPages(paragraphs: List<String>): List<List<String>> {
        if (paragraphs.isEmpty()) return listOf(listOf("[No readable text]"))
        val processed = mutableListOf<String>()
        for (para in paragraphs) {
            if (para.length <= maxChars) {
                processed.add(para)
            } else {
                var i = 0
                while (i < para.length) {
                    processed.add(para.substring(i, minOf(i + maxChars, para.length)))
                    i += maxChars
                }
            }
        }
        val pages = mutableListOf<List<String>>()
        for (i in processed.indices step parasPerPage) {
            pages.add(processed.subList(i, minOf(i + parasPerPage, processed.size)))
        }
        return pages
    }

    private fun buildHeader(): String {
        val lines = mutableListOf<String>()
        if (showTitle && currentTitle.isNotEmpty()) lines.add("▌$currentTitle")
        if (showCompactUrl && !currentUrl.isNullOrBlank()) {
            lines.add("🔗 ${shortenMiddle(currentUrl!!, 44)}")
        }
        if (showPageNumber) {
            val remaining = blocks.size - currentBlockIndex - 1
            lines.add("— Block ${currentBlockIndex + 1}/${blocks.size} · $remaining left —")
        }
        return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n\n"
    }

    private fun renderBlock() {
        if (blocks.isEmpty()) return
        contentView.text = buildHeader() + blocks[currentBlockIndex]
        currentSpannable = null
    }

    private fun renderBlockWithSpannable() {
        if (blocks.isEmpty()) return
        val header = buildHeader()
        headerLength = header.length
        val full = SpannableString(header + blocks[currentBlockIndex])
        currentSpannable = full
        contentView.text = full
    }

    private fun shortenMiddle(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        if (maxLen < 10) return text.take(maxLen)
        val keep = (maxLen - 3) / 2
        return text.take(keep) + "…" + text.takeLast(keep)
    }

    private fun changeTextSize(delta: Float) {
        textSize = (textSize + delta).coerceIn(10f, 28f)
        savePrefs()
        contentView.textSize = textSize
        toast("Text size: ${textSize.toInt()}sp")
    }

    // ========= TTS =========

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val avail = try { tts?.getAvailableLanguages() ?: emptySet() } catch (e: Exception) { emptySet() }
                ttsAvailable = avail
                val defaultOk = try {
                    val r = tts?.setLanguage(Locale.getDefault())
                    r != TextToSpeech.LANG_MISSING_DATA &&
                        r != TextToSpeech.LANG_NOT_SUPPORTED
                } catch (e: Exception) {
                    false
                }
                ttsReady = defaultOk || avail.isNotEmpty()
                if (ttsReady) {
                    val chosen = pickBestTtsLocale(avail, defaultOk)
                    if (chosen != null) {
                        try { tts?.language = chosen } catch (e: Exception) {}
                        detectedLang = ttsLocaleCode(chosen)
                    }
                    try { tts?.setSpeechRate(ttsSpeed) } catch (e: Exception) {}
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            onTtsUtteranceDone()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {}
                        override fun onRangeStart(
                            utteranceId: String?,
                            start: Int,
                            end: Int,
                            frame: Int
                        ) {
                            runOnUiThread { highlightTtsRange(start, end) }
                        }
                    })
                }
            }
        }
    }

    private fun toggleTts() {
        if (ttsActive) stopTts() else startTts()
    }

    private fun startTts() {
        if (blocks.isEmpty()) {
            toast("Nothing to read")
            return
        }
        val useLocal = ttsReady && ttsMode != "online"
        if (!useLocal && ttsMode == "local") {
            toast("No local TTS engine installed")
            return
        }
        if (!useLocal && !ttsReady && ttsMode != "online") {
            toast("No local TTS engine — using online voice")
        }
        ttsActive = true
        ttsButton.text = "TTS ■"
        if (useLocal) {
            ttsOnline = false
            speakBlock(currentBlockIndex)
        } else {
            ttsOnline = true
            speakOnlineBlock(currentBlockIndex)
        }
    }

    private fun stopTts() {
        ttsActive = false
        ttsOnline = false
        tts?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        onlineSentences = emptyList()
        onlineSentenceIndex = 0
        onlineFailures = 0
        clearTtsHighlight()
        ttsButton.text = "TTS"
    }

    private fun stopPlayback() {
        tts?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun restartAtCurrentBlock() {
        if (ttsOnline) speakOnlineBlock(currentBlockIndex) else speakBlock(currentBlockIndex)
    }

    // ---- local TTS ----

    private fun speakBlock(index: Int) {
        if (!ttsActive || ttsOnline) return
        if (index !in blocks.indices) {
            runOnUiThread {
                stopTts()
                toast("Finished reading.")
            }
            return
        }
        runOnUiThread {
            currentBlockIndex = index
            renderBlockWithSpannable()
        }
        applyLocalTtsLang(currentTtsLang())
        tts?.speak(blocks[index], TextToSpeech.QUEUE_FLUSH, null, "block$index")
    }

    private fun onTtsUtteranceDone() {
        if (!ttsActive || ttsOnline) return
        speakBlock(currentBlockIndex + 1)
    }

    // ---- online TTS (Google Translate voice, no engine needed) ----

    private fun speakOnlineBlock(index: Int) {
        if (!ttsActive || !ttsOnline) return
        if (index !in blocks.indices) {
            runOnUiThread {
                stopTts()
                toast("Finished reading.")
            }
            return
        }
        onlineSentences = splitSentences(blocks[index])
        onlineSentenceIndex = 0
        onlineFailures = 0
        runOnUiThread {
            currentBlockIndex = index
            renderBlockWithSpannable()
        }
        playOnlineSentence()
    }

    private fun splitSentences(text: String): List<SentenceRange> {
        val result = mutableListOf<SentenceRange>()
        var i = 0
        while (i < text.length) {
            var s = i
            while (s < text.length && text[s].isWhitespace()) s++
            if (s >= text.length) break
            var e = s
            while (e < text.length && text[e] != '.' && text[e] != '!' &&
                text[e] != '?' && text[e] != '\n'
            ) e++
            var end = e
            if (end < text.length && text[end] != '\n') end++
            result.add(SentenceRange(s, end, text.substring(s, end).trim()))
            i = end
        }
        return if (result.isEmpty()) {
            listOf(SentenceRange(0, text.length, text.trim()))
        } else result
    }

    private fun playOnlineSentence() {
        if (!ttsActive || !ttsOnline) return
        if (onlineSentenceIndex >= onlineSentences.size) {
            speakOnlineBlock(currentBlockIndex + 1)
            return
        }
        val sentence = onlineSentences[onlineSentenceIndex]
        speakChunks(sentence, chunkText(sentence.text, 180), 0)
    }

    private fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + maxLen, text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start + maxLen / 2) end = space
            }
            var s = start
            while (s < text.length && text[s].isWhitespace()) s++
            var e = end
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) chunks.add(text.substring(s, e))
            start = maxOf(end, s + 1)
        }
        return if (chunks.isEmpty()) listOf(text) else chunks
    }

    private fun speakChunks(sentence: SentenceRange, chunks: List<String>, ci: Int) {
        if (!ttsActive || !ttsOnline) return
        if (ci >= chunks.size) {
            onOnlineSentenceDone()
            return
        }
        val index = onlineSentenceIndex
        val chunk = chunks[ci]
        thread {
            try {
                Thread.sleep(300)
                val mp3 = fetchOnlineSpeech(chunk)
                if (!ttsActive || !ttsOnline) return@thread
                runOnUiThread { highlightOnlineSentence(index) }
                playMp3(mp3) { speakChunks(sentence, chunks, ci + 1) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (ttsActive && ttsOnline) {
                        onlineFailures++
                        if (onlineFailures > 4) {
                            stopTts()
                            toast("Online voice error: ${e.message}")
                        } else {
                            speakChunks(sentence, chunks, ci + 1)
                        }
                    }
                }
            }
        }
    }

    private fun onOnlineSentenceDone() {
        if (!ttsActive || !ttsOnline) return
        onlineSentenceIndex++
        onlineFailures = 0
        playOnlineSentence()
    }

    private fun playMp3(bytes: ByteArray, onDone: () -> Unit) {
        val file = File.createTempFile("tts", ".mp3", cacheDir)
        runOnUiThread {
            if (!ttsActive || !ttsOnline) {
                file.delete()
                return@runOnUiThread
            }
            try {
                file.writeBytes(bytes)
                mediaPlayer?.release()
                val mp = MediaPlayer()
                mediaPlayer = mp
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener {
                    file.delete()
                    onDone()
                }
                mp.setOnErrorListener { _, _, _ ->
                    file.delete()
                    stopTts()
                    toast("Voice playback error")
                    true
                }
                mp.setOnPreparedListener {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ttsSpeed != 1.0f) {
                        try {
                            mp.playbackParams = mp.playbackParams.setSpeed(ttsSpeed)
                        } catch (e: Exception) {}
                    }
                    it.start()
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                file.delete()
                stopTts()
                toast("Voice error: ${e.message}")
            }
        }
    }

    private fun fetchOnlineSpeech(text: String): ByteArray {
        val langs = listOf(currentTtsLang(), "en").distinct()
        val hosts = listOf(
            "https://translate.googleapis.com/translate_tts?client=gtx&ie=UTF-8&tl=%s&q=%s",
            "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=%s&q=%s"
        )
        var lastError: Exception? = null
        for (lang in langs) {
            for (template in hosts) {
                try {
                    val q = URLEncoder.encode(text, "UTF-8")
                    val url = String.format(template, lang, q)
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Accept", "audio/mpeg")
                    conn.setRequestProperty("Referer", "https://translate.google.com/")
                    val code = conn.responseCode
                    if (code == 200) {
                        return conn.inputStream.use { it.readBytes() }
                    }
                    lastError = IOException("HTTP $code")
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IOException("Online voice failed")
    }

    private fun currentTtsLang(): String {
        if (pageLangCode.isNotBlank()) return pageLangCode
        val text = if (currentBlockIndex in blocks.indices) {
            blocks[currentBlockIndex]
        } else ""
        val guess = detectTextLang(text)
        return guess.ifBlank { ttsLangCode() }
    }

    private fun applyLocalTtsLang(code: String) {
        if (tts == null) return
        val lang = code.lowercase().substringBefore("-")
        val loc = localeForLang(ttsAvailable, code, lang) ?: return
        try {
            val r = tts?.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                val best = pickBestTtsLocale(ttsAvailable, ttsReady)
                if (best != null) tts?.setLanguage(best)
            } else if (detectedLang.isBlank() || detectedLang.startsWith(lang)) {
                detectedLang = ttsLocaleCode(loc)
            }
        } catch (e: Exception) {}
    }

    private fun localeForLang(avail: Set<Locale>, code: String, lang: String): Locale? {
        val region = code.substringAfter("-", "").lowercase()
        if (avail.isNotEmpty()) {
            return avail.firstOrNull {
                it.language.equals(lang, true) && region.isNotBlank() &&
                    it.country.equals(region, true)
            } ?: avail.firstOrNull { it.language.equals(lang, true) }
                ?: pickBestTtsLocale(avail, ttsReady)
        }
        return try {
            if (region.length == 2) Locale(lang, region.uppercase()) else Locale(code)
        } catch (e: Exception) {
            null
        }
    }

    private fun pickBestTtsLocale(
        avail: Set<Locale>,
        defaultOk: Boolean
    ): Locale? {
        if (avail.isNotEmpty()) {
            val def = Locale.getDefault()
            return avail.firstOrNull { it.language == def.language && it.country == def.country }
                ?: avail.firstOrNull { it.language == def.language }
                ?: avail.firstOrNull { it.language == "en" }
                ?: avail.firstOrNull()
        }
        return if (defaultOk) Locale.getDefault() else null
    }

    private fun ttsLocaleCode(locale: Locale): String {
        val lang = locale.language.lowercase()
        return if (locale.country.isNotBlank()) {
            "${lang}-${locale.country.lowercase()}"
        } else lang
    }

    private fun detectTextLang(text: String): String {
        val t = text.take(2000)
        fun cnt(re: Regex) = re.findAll(t).count()
        val cyrillic = cnt(Regex("[\\u0400-\\u04FF]"))
        val greek = cnt(Regex("[\\u0370-\\u03FF]"))
        val cjk = cnt(Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF]"))
        val kana = cnt(Regex("[\\u3040-\\u30FF]"))
        val hangul = cnt(Regex("[\\uAC00-\\uD7AF]"))
        val arabic = cnt(Regex("[\\u0600-\\u06FF]"))
        val hebrew = cnt(Regex("[\\u0590-\\u05FF]"))
        val devanagari = cnt(Regex("[\\u0900-\\u097F]"))
        val thai = cnt(Regex("[\\u0E00-\\u0E7F]"))
        val scripts = cyrillic + greek + cjk + kana + hangul + arabic + hebrew + devanagari + thai
        if (scripts > 5) {
            return when {
                kana > 0 -> "ja"
                hangul > 0 -> "ko"
                cjk > 0 -> "zh"
                cyrillic > 0 -> "ru"
                greek > 0 -> "el"
                arabic > 0 -> "ar"
                hebrew > 0 -> "he"
                devanagari > 0 -> "hi"
                thai > 0 -> "th"
                else -> "en"
            }
        }
        val lower = t.lowercase()
        val stops = mapOf(
            "en" to listOf("the", "and", "that", "with", "this", "you", "for", "are", "was", "have"),
            "es" to listOf("que", "de", "la", "el", "y", "los", "las", "una", "para", "con"),
            "fr" to listOf("les", "des", "que", "une", "pour", "avec", "dans", "est", "sur", "pas"),
            "de" to listOf("der", "die", "und", "das", "ist", "mit", "nicht", "ein", "eine", "fur"),
            "it" to listOf("che", "per", "con", "una", "non", "alla", "sono", "come", "piu", "dell"),
            "pt" to listOf("que", "uma", "para", "com", "dos", "das", "nao", "sao", "mais", "como"),
            "nl" to listOf("van", "het", "een", "voor", "niet", "met", "zijn", "als", "ook", "aan"),
            "pl" to listOf("nie", "na", "z", "to", "sie", "jest", "w", "do", "jest", "i")
        )
        var best = ""
        var bestScore = -1
        for ((lang, words) in stops) {
            val score = words.count { w -> Regex("\\b$w\\b").containsMatchIn(lower) }
            if (score > bestScore) {
                bestScore = score
                best = lang
            }
        }
        return if (bestScore >= 2) best else ""
    }

    private fun ttsLangCode(): String {
        if (detectedLang.isNotBlank()) return detectedLang
        return ttsLocaleCode(Locale.getDefault())
    }

    private fun highlightOnlineSentence(index: Int) {
        if (index !in onlineSentences.indices) return
        val sp = currentSpannable ?: return
        val range = onlineSentences[index]
        val sStart = headerLength + range.start
        val sEnd = headerLength + range.end
        if (sEnd > sp.length) return
        for (span in sp.getSpans(0, sp.length, BackgroundColorSpan::class.java)) {
            sp.removeSpan(span)
        }
        sp.setSpan(
            BackgroundColorSpan(TTS_HIGHLIGHT_COLOR),
            sStart,
            sEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun highlightTtsRange(start: Int, end: Int) {
        val sp = currentSpannable ?: return
        if (start < 0 || end < start) return
        val hs = headerLength + start
        val he = headerLength + end
        if (he > sp.length) return
        var sStart = hs
        while (sStart > headerLength && !isSentenceBreak(sp[sStart - 1])) sStart--
        var sEnd = he
        while (sEnd < sp.length && !isSentenceBreak(sp[sEnd - 1])) sEnd++
        for (span in sp.getSpans(0, sp.length, BackgroundColorSpan::class.java)) {
            sp.removeSpan(span)
        }
        sp.setSpan(
            BackgroundColorSpan(TTS_HIGHLIGHT_COLOR),
            sStart,
            sEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun clearTtsHighlight() {
        val sp = currentSpannable ?: return
        for (span in sp.getSpans(0, sp.length, BackgroundColorSpan::class.java)) {
            sp.removeSpan(span)
        }
    }

    private fun isSentenceBreak(c: Char): Boolean =
        c == '.' || c == '!' || c == '?' || c == '\n'

    private fun stopTtsOnNewPage() {
        if (ttsActive) stopTts()
    }

    private fun nextBlock() {
        if (currentBlockIndex < blocks.size - 1) {
            currentBlockIndex++
            if (ttsActive) {
                stopPlayback()
                restartAtCurrentBlock()
            } else {
                renderBlock()
            }
        } else {
            tryLoadNextPart()
        }
    }

    private fun previousBlock() {
        if (currentBlockIndex > 0) {
            currentBlockIndex--
            if (ttsActive) {
                stopPlayback()
                restartAtCurrentBlock()
            } else {
                renderBlock()
            }
        }
    }

    // ========= NEXT PART (pagination) =========

    private fun tryLoadNextPart() {
        val base = currentUrl ?: return
        val current = rawParagraphs
        setLoading(true)
        thread {
            try {
                val m = Regex("/page/(\\d+)").find(base)
                val (pageNum, root) = if (m != null) {
                    Pair(m.groupValues[1].toInt(), base.substringBefore("/page/"))
                } else {
                    Pair(1, base.trimEnd('/'))
                }
                val nextUrl = "$root/page/${pageNum + 1}"
                val html = httpGet(nextUrl)
                val ext = extractSinglePage(html, nextUrl)
                val existing = current.toSet()
                val added = ext.paragraphs.filter { it !in existing }
                if (added.isEmpty()) {
                    runOnUiThread {
                        setLoading(false)
                        toast("You've reached the end.")
                    }
                    return@thread
                }
                runOnUiThread {
                    setLoading(false)
                    rawParagraphs = rawParagraphs + added
                    currentTitle = ext.title ?: currentTitle
                    val startAt = rawParagraphs.size - added.size
                    val pages = buildTextPages(rawParagraphs)
                    blocks = pages.map { it.joinToString("\n\n") }
                    currentUrl = nextUrl
                    currentBlockIndex = minOf(startAt / parasPerPage, blocks.size - 1)
                    renderBlock()
                    toast("Loaded next part (${added.size} more paragraphs)")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    setLoading(false)
                    toast("You've reached the end.")
                }
            }
        }
    }

    // ========= SEARCH =========

    private fun searchAndSelect(query: String) {
        val gen = ++loadGeneration
        setLoading(true)
        showMessage("Searching ${engineNames[searchEngine]}…")
        thread {
            try {
                val results = search(query)
                runOnUiThread {
                    if (gen != loadGeneration) return@runOnUiThread
                    if (results.isEmpty()) showMessage("No results.")
                    else {
                        searchResults = results
                        searchOffset = 0
                        showResultsDialog()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen != loadGeneration) return@runOnUiThread
                    showMessage("Error: ${e.message}")
                }
            }
        }
    }

    private fun search(q: String): List<Pair<String, String>> {
        val order = when (searchEngine) {
            "duck_html" -> listOf("duck_html", "duck_lite", "bing", "google", "brave")
            "brave" -> listOf("brave", "bing", "google", "duck_lite", "duck_html")
            "google" -> listOf("google", "bing", "brave", "duck_lite", "duck_html")
            "bing" -> listOf("bing", "google", "brave", "duck_lite", "duck_html")
            else -> listOf("duck_lite", "duck_html", "bing", "google", "brave")
        }
        for (eng in order) {
            try {
                val r = searchOne(q, eng)
                if (r.isNotEmpty()) {
                    lastSearchEngine = eng
                    return r
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    private fun searchOne(q: String, eng: String): List<Pair<String, String>> =
        when (eng) {
            "duck_html" -> searchDuckHtml(q)
            "brave" -> searchBrave(q)
            "google" -> searchGoogleText(q)
            "bing" -> searchBing(q)
            else -> searchDuckLite(q)
        }

    private fun fetchDoc(url: String): Document =
        Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()

    private fun searchDuckLite(q: String): List<Pair<String, String>> {
        val doc = fetchDoc("https://lite.duckduckgo.com/lite/?q=${urlencode(q)}")
        val results = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a.result-link")) {
            val title = a.text().trim()
            val href = unwrapGenericRedirect(a.attr("href"))
            if (title.isNotEmpty() && !isAdOrTracker(href)) results.add(title to href)
        }
        return results
    }

    private fun searchDuckHtml(q: String): List<Pair<String, String>> {
        val doc = fetchDoc("https://duckduckgo.com/html/?q=${urlencode(q)}")
        val results = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a.result__a")) {
            val title = a.text().trim()
            val href = unwrapGenericRedirect(a.attr("href"))
            if (title.isNotEmpty() && !isAdOrTracker(href)) results.add(title to href)
        }
        return results
    }

    private fun searchBrave(q: String): List<Pair<String, String>> {
        val doc = fetchDoc("https://search.brave.com/search?q=${urlencode(q)}&source=web")
        val results = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a.result-header")) {
            val title = a.text().trim()
            val href = unwrapGenericRedirect(a.attr("href"))
            if (title.isNotEmpty() && !isAdOrTracker(href)) results.add(title to href)
        }
        return results
    }

    private fun searchBing(q: String): List<Pair<String, String>> {
        val doc = fetchDoc("https://www.bing.com/search?q=${urlencode(q)}&form=MSNVS")
        val results = mutableListOf<Pair<String, String>>()
        for (a in doc.select("li.b_algo h2 a")) {
            val title = a.text().trim()
            val href = unwrapGenericRedirect(a.attr("href"))
            if (title.isNotEmpty() && !isAdOrTracker(href)) results.add(title to href)
        }
        return results
    }

    private fun searchGoogleText(q: String): List<Pair<String, String>> {
        return try {
            val target = "https://www.google.com/search?q=${urlencode(q)}"
            val doc = fetchDoc("https://textise.net/showtext.aspx?strURL=${urlencode(target)}")
            val results = mutableListOf<Pair<String, String>>()
            for (a in doc.select("a[href]")) {
                val href = a.attr("abs:href")
                if (href.isEmpty() || "http" !in href) continue
                val title = a.text().trim()
                if (title.isNotEmpty() && !isAdOrTracker(href)) results.add(title to href)
            }
            if (results.isEmpty()) searchDuckLite(q) else results
        } catch (_: Exception) {
            searchDuckLite(q)
        }
    }

    private fun showResultsDialog() {
        val page = searchResults.drop(searchOffset).take(resultsPerPage)
        val titles = mutableListOf<String>()
        if (searchOffset > 0) titles.add("◂ Previous results")
        titles.addAll(page.map { it.first })
        if (searchOffset + page.size < searchResults.size) titles.add("▸ Next results")
        val prevHeader = if (searchOffset > 0) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Results · ${engineNames[lastSearchEngine]}")
            .setItems(titles.toTypedArray()) { _, which ->
                when {
                    prevHeader == 1 && which == 0 -> {
                        searchOffset -= resultsPerPage
                        showResultsDialog()
                    }
                    which == titles.size - 1 && searchOffset + page.size < searchResults.size -> {
                        searchOffset += resultsPerPage
                        showResultsDialog()
                    }
                    else -> {
                        val idx = which - prevHeader
                        if (idx in page.indices) {
                            val url = page[idx].second
                            urlInput.setText(url)
                            loadUrl(url)
                        }
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun feelingLucky() {
        val q = urlInput.text.toString().trim()
        if (q.isEmpty()) {
            toast("Type a query first")
            return
        }
        feelingLucky(q)
    }

    private fun feelingLucky(q: String) {
        val gen = ++loadGeneration
        setLoading(true)
        showMessage("I'm feeling lucky…")
        thread {
            try {
                val results = search(q)
                if (results.isEmpty()) {
                    runOnUiThread {
                        if (gen != loadGeneration) return@runOnUiThread
                        showMessage("No results.")
                    }
                } else {
                    val url = results.first().second
                    runOnUiThread {
                        if (gen != loadGeneration) return@runOnUiThread
                        urlInput.setText(url)
                        loadUrl(url)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen != loadGeneration) return@runOnUiThread
                    showMessage("Error: ${e.message}")
                }
            }
        }
    }

    // ========= URL / REDIRECT HELPERS =========

    private fun resolveRedirect(url: String): String {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "HEAD"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                conn.responseCode
                conn.url.toString()
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        activeConnection = conn
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            if (activeConnection === conn) activeConnection = null
        }
    }

    private fun unwrapDuckduckgoRedirect(url: String): String {
        var u = url.trim()
        if (u.startsWith("//duckduckgo.com/l/?")) u = "https:$u"
        return try {
            val parsed = URL(u)
            if ("duckduckgo.com" in parsed.host && parsed.path.startsWith("/l")) {
                val uddg = queryParam(parsed.query, "uddg") ?: return u
                URLDecoder.decode(uddg, "UTF-8")
            } else {
                u
            }
        } catch (_: Exception) {
            u
        }
    }

    private fun stripDuckduckgoTracking(url: String): String {
        return try {
            val p = URL(url)
            if ("duckduckgo.com" in p.host) {
                val clean = URL(p.protocol, p.host, p.port, p.path).toString()
                clean
            } else {
                url
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun unwrapGenericRedirect(url: String): String =
        stripDuckduckgoTracking(unwrapDuckduckgoRedirect(url))

    private fun queryParam(query: String?, key: String): String? {
        if (query.isNullOrBlank()) return null
        for (part in query.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv[0] == key) return if (kv.size == 2) kv[1] else ""
        }
        return null
    }

    private fun isAdOrTracker(url: String): Boolean {
        val host = try {
            URL(url).host.lowercase()
        } catch (_: Exception) {
            return false
        }
        val bad = listOf(
            "doubleclick", "adservice", "adsystem", "tracking",
            "analytics", "pixel", "googlesyndication"
        )
        return bad.any { host.contains(it) }
    }

    // ========= HTML PARSING =========

    private fun extractSinglePage(html: String, base: String): Extracted {
        val doc = Jsoup.parse(html, base)
        doc.select("script, style, nav, footer, header, form, aside").remove()
        val lang = normalizeLang(doc.select("html[lang]").first()?.attr("lang"))

        val preBlocks = doc.select("pre")
        if (preBlocks.isNotEmpty()) {
            val paragraphs = mutableListOf<String>()
            for (pre in preBlocks) {
                val ps = pre.select("p")
                if (ps.isNotEmpty()) {
                    for (p in ps) {
                        val t = cleanParagraph(p.text())
                        if (t.length > 5) paragraphs.add(t)
                    }
                } else {
                    val t = cleanParagraph(pre.text())
                    if (t.length > 20) paragraphs.add(t)
                }
            }
            return Extracted(paragraphs, collectLinks(doc, base), extractTitle(doc), fetchMainImage(doc, base), lang)
        }

        var main: Element = doc.body() ?: doc
        var bestSize = 0
        for (candidate in doc.select("article, main, div")) {
            val size = candidate.text().length
            if (size > bestSize) {
                bestSize = size
                main = candidate
            }
        }

        val paragraphs = mutableListOf<String>()
        for (p in main.select("p, li")) {
            val t = cleanParagraph(p.text())
            if (t.length > 20) paragraphs.add(t)
        }

        return Extracted(paragraphs, collectLinks(main, base), extractTitle(doc), fetchMainImage(doc, base), lang)
    }

    private fun normalizeLang(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim().lowercase()
        val comma = s.indexOf(',')
        if (comma > 0) s = s.substring(0, comma).trim()
        val m = Regex("^([a-z]{2,3})([-_][a-z0-9]{2,4})?").find(s)
        if (m == null) return ""
        return m.value.lowercase().replace("_", "-")
    }

    private fun collectLinks(root: Element, base: String): List<LinkItem> {
        val links = mutableListOf<LinkItem>()
        for (a in root.select("a[href]")) {
            val abs = a.absUrl("href")
            if (abs.isEmpty()) continue
            val href = unwrapGenericRedirect(abs)
            if (isAdOrTracker(href)) continue
            val label = a.text().trim()
            links.add(LinkItem(if (label.isNotEmpty()) label else href, href))
        }
        return links
    }

    private fun extractTitle(doc: Document): String? {
        val t = doc.title().trim()
        return if (t.isEmpty()) null else t
    }

    private fun fetchMainImage(doc: Document, base: String): String? {
        val og = doc.selectFirst("meta[property=og:image]")
        if (og != null) {
            val c = og.attr("content")
            if (c.isNotEmpty()) return absUrl(base, c)
        }
        val img = doc.selectFirst("img[src]")
        if (img != null) {
            val s = img.attr("src")
            if (s.isNotEmpty()) return absUrl(base, s)
        }
        return null
    }

    private fun absUrl(base: String, href: String): String =
        try {
            URL(URL(base), href).toString()
        } catch (_: Exception) {
            href
        }

    private fun cleanParagraph(text: String): String =
        text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()

    // ========= PDF =========

    private fun loadPdfInternal(url: String, gen: Int) {
        val cached = readPdfCache(url)
        if (cached != null) {
            runOnUiThread {
                if (gen != loadGeneration) return@runOnUiThread
                presentArticle(Extracted(cached.paragraphs, emptyList(), cached.title, null, cached.lang), url)
            }
            return
        }
        try {
            PDFBoxResourceLoader.init(applicationContext)
            val file = File.createTempFile("tbrowser", ".pdf", cacheDir)
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                activeConnection = conn
                try {
                    conn.inputStream.use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                } finally {
                    if (activeConnection === conn) activeConnection = null
                }
                if (gen != loadGeneration) return

                val paragraphs = mutableListOf<String>()
                var title: String? = null
                PDDocument.load(file).use { doc ->
                    val info = doc.documentInformation
                    val metaTitle = info.title
                    if (!metaTitle.isNullOrBlank()) title = metaTitle.trim()
                    val stripper = PDFTextStripper()
                    for (i in 1..doc.numberOfPages) {
                        stripper.startPage = i
                        stripper.endPage = i
                        val t = cleanParagraph(stripper.getText(doc))
                        if (t.isNotEmpty()) paragraphs.add(t)
                    }
                }

                if (paragraphs.isEmpty()) {
                    runOnUiThread {
                        if (gen != loadGeneration) return@runOnUiThread
                        showMessage("[PDF contains no extractable text]")
                    }
                    return
                }
                writePdfCache(url, title, paragraphs)
                val ext = Extracted(paragraphs, emptyList(), title, null)
                runOnUiThread {
                    if (gen != loadGeneration) return@runOnUiThread
                    presentArticle(ext, url)
                }
            } finally {
                file.delete()
            }
        } catch (e: Exception) {
            runOnUiThread {
                if (gen != loadGeneration) return@runOnUiThread
                showMessage("PDF error: ${e.message}")
            }
        }
    }

    // ========= PDF CACHE =========

    private data class PdfCache(
        val title: String?,
        val paragraphs: List<String>,
        val lang: String,
        val url: String
    )

    private fun pdfCacheDir(): File =
        File(filesDir, "pdf_cache").apply { if (!exists()) mkdirs() }

    private fun pdfCacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readPdfCache(url: String): PdfCache? {
        return try {
            val file = File(pdfCacheDir(), pdfCacheKey(url) + ".json")
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("paragraphs") ?: return null
            val paragraphs = (0 until arr.length()).map { arr.getString(it) }
            PdfCache(
                root.optString("title", "").ifBlank { null },
                paragraphs,
                root.optString("lang", ""),
                root.optString("url", url)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writePdfCache(url: String, title: String?, paragraphs: List<String>, lang: String = "") {
        try {
            val file = File(pdfCacheDir(), pdfCacheKey(url) + ".json")
            val obj = JSONObject()
                .put("url", url)
                .put("title", title ?: "")
                .put("lang", lang)
                .put("paragraphs", JSONArray(paragraphs))
            file.writeText(obj.toString())
        } catch (e: Exception) {}
    }

    private fun cleanPdfCache() {
        thread {
            try {
                val bookmarked = loadBookmarks().map { it.url }.toSet()
                val dir = pdfCacheDir()
                for (f in dir.listFiles() ?: return@thread) {
                    if (f.name.endsWith(".json")) {
                        val keep = try {
                            JSONObject(f.readText()).optString("url", "") in bookmarked
                        } catch (e: Exception) {
                            false
                        }
                        if (!keep) f.delete()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    // ========= AI (GROQ) =========

    private fun askAi() {
        val input = EditText(this)
        input.hint = "Question (blank = summarize this page)"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(this)
            .setTitle("Ask AI (Groq)")
            .setView(input)
            .setPositiveButton("Ask") { _, _ ->
                val q = input.text.toString().trim()
                val prompt = if (q.isBlank()) {
                    val text = rawParagraphs.joinToString("\n").take(3000)
                    "Summarize the following text concisely:\n\n$text"
                } else {
                    q
                }
                doAiRequest(prompt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doAiRequest(prompt: String) {
        setLoading(true)
        showMessage("Asking AI…")
        thread {
            val answer = aiQuery(prompt)
            runOnUiThread { showAiResult(answer) }
        }
    }

    private fun aiQuery(prompt: String): String {
        if (groqKey.isBlank()) {
            return "AI ERROR:\nNo Groq API key set. Use ⋮ Menu → Settings → Groq API key."
        }
        return try {
            val payload = JSONObject()
                .put("model", "llama-3.1-8b-instant")
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                )
            val conn = URL("https://api.groq.com/openai/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $groqKey")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            }
            conn.disconnect()
            if (code !in 200..299) return "AI ERROR:\nHTTP $code: ${body.take(300)}"
            val json = JSONObject(body)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            "AI ERROR:\n${e.message}"
        }
    }

    private fun showAiResult(answer: String) {
        val tv = TextView(this)
        tv.setTextColor(if (isNight) getColor(R.color.nightText) else getColor(R.color.dayText))
        tv.textSize = textSize
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(16), dp(16), dp(16), dp(16))
        AlertDialog.Builder(this)
            .setTitle("AI answer")
            .setView(tv)
            .setPositiveButton("OK", null)
            .show()
        tv.text = answer
    }

    // ========= LINKS / HISTORY / SHARE =========

    private fun showLinksDialog() {
        if (currentLinks.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("No links on this page.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = currentLinks.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Links (${currentLinks.size})")
            .setItems(labels) { _, which ->
                val url = currentLinks[which].url
                urlInput.setText(url)
                loadUrl(url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHistoryDialog() {
        val hist = loadHistory()
        if (hist.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("No history yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val titles = hist.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(titles) { _, which ->
                val url = hist[which].second
                urlInput.setText(url)
                loadUrl(url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun shareCurrent() {
        val url = currentUrl ?: run {
            toast("Nothing to share yet")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, currentTitle)
            putExtra(Intent.EXTRA_TEXT, "$currentTitle\n$url")
        }
        startActivity(Intent.createChooser(intent, "Share link"))
    }

    // ========= PREFERENCES =========

    private fun prefs() = getSharedPreferences("browser", Context.MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        searchEngine = p.getString("engine", "duck_lite") ?: "duck_lite"
        resultsPerPage = p.getInt("results_per_page", 10)
        parasPerPage = p.getInt("paras_per_page", 2)
        maxChars = p.getInt("max_chars", 2000)
        chronologyLength = p.getInt("chronology_length", 5)
        groqKey = p.getString("groq_key", "") ?: ""
        isNight = p.getBoolean("night", false)
        textSize = p.getFloat("text_size", 16f)
        showTitle = p.getBoolean("show_title", true)
        showPageNumber = p.getBoolean("show_page_number", true)
        showCompactUrl = p.getBoolean("show_compact_url", true)
        ttsMode = p.getString("tts_mode", "auto") ?: "auto"
        ttsSpeed = p.getFloat("tts_speed", 1.0f)
    }

    private fun savePrefs() {
        prefs().edit()
            .putString("engine", searchEngine)
            .putInt("results_per_page", resultsPerPage)
            .putInt("paras_per_page", parasPerPage)
            .putInt("max_chars", maxChars)
            .putInt("chronology_length", chronologyLength)
            .putString("groq_key", groqKey)
            .putBoolean("night", isNight)
            .putFloat("text_size", textSize)
            .putBoolean("show_title", showTitle)
            .putBoolean("show_page_number", showPageNumber)
            .putBoolean("show_compact_url", showCompactUrl)
            .putString("tts_mode", ttsMode)
            .putFloat("tts_speed", ttsSpeed)
            .apply()
    }

    private fun loadHistory(): MutableList<Pair<String, String>> {
        val raw = prefs().getString("history", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("|||").mapNotNull {
            val p = it.split("::")
            if (p.size == 2) Pair(p[0], p[1]) else null
        }.toMutableList()
    }

    private fun addToHistory(title: String, url: String) {
        val hist = loadHistory().filter { it.second != url }.toMutableList()
        hist.add(0, Pair(if (title.isBlank()) url else title, url))
        val trimmed = hist.take(chronologyLength)
        val encoded = trimmed.joinToString("|||") { "${it.first}::${it.second}" }
        prefs().edit().putString("history", encoded).apply()
    }

    // ========= SETTINGS =========

    private fun showSettingsDialog() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<Int>()
        fun add(label: String, action: Int) {
            items.add(label)
            actions.add(action)
        }
        add("Search engine: ${engineNames[searchEngine]}", 0)
        add("Results per page: $resultsPerPage", 1)
        add("Paragraphs per page: $parasPerPage", 2)
        add("Max chars per block: $maxChars", 3)
        add("Groq API key: ${if (groqKey.isBlank()) "NOT SET" else "SET"}", 4)
        add("Chronology length: $chronologyLength", 5)
        add("Text size: ${textSize.toInt()}sp", 6)
        add("Show page title: ${if (showTitle) "on" else "off"}", 7)
        add("Show page number: ${if (showPageNumber) "on" else "off"}", 8)
        add("Show compact URL: ${if (showCompactUrl) "on" else "off"}", 9)
        add("Voice: ${ttsModeLabel()}", 10)
        add("Voice language: ${langDisplay()}", 11)
        add("Voice speed: ${speedLabel()}", 12)
        add("Export data (settings + bookmarks + history)", 13)
        add("Import data from backup", 14)
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (actions[which]) {
                    0 -> pickEngine()
                    1 -> askInt("Results per page", resultsPerPage, 5, 100) { resultsPerPage = it }
                    2 -> askInt("Paragraphs per page", parasPerPage, 1, 20) { parasPerPage = it }
                    3 -> askInt("Max chars per block", maxChars, 500, 10000) { maxChars = it }
                    4 -> askGroqKey()
                    5 -> askInt("Chronology length", chronologyLength, 3, 50) { chronologyLength = it }
                    6 -> askInt("Text size (sp)", textSize.toInt(), 10, 28) {
                        textSize = it.toFloat()
                        contentView.textSize = textSize
                    }
                    7 -> {
                        showTitle = !showTitle
                        savePrefs()
                        renderBlock()
                    }
                    8 -> {
                        showPageNumber = !showPageNumber
                        savePrefs()
                        renderBlock()
                    }
                    9 -> {
                        showCompactUrl = !showCompactUrl
                        savePrefs()
                        renderBlock()
                    }
                    10 -> pickTtsMode()
                    11 -> toast("Language is auto-detected from the TTS engine")
                    12 -> pickTtsSpeed()
                    13 -> exportData()
                    14 -> importData()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun ttsModeLabel(): String = when (ttsMode) {
        "auto" -> "auto (offline, online fallback)"
        "local" -> "offline engine only"
        "online" -> "online voice always"
        else -> ttsMode
    }

    private fun pickTtsMode() {
        val modes = arrayOf(
            "Auto — offline engine, online fallback",
            "Offline only — requires a TTS engine",
            "Online always — no engine needed"
        )
        val current = when (ttsMode) {
            "local" -> 1
            "online" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle("Voice mode")
            .setSingleChoiceItems(modes, current) { _, which ->
                ttsMode = when (which) {
                    1 -> "local"
                    2 -> "online"
                    else -> "auto"
                }
                savePrefs()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun langDisplay(): String {
        val code = currentTtsLang()
        return when {
            pageLangCode.isNotBlank() -> "$code (page)"
            ttsReady -> "$code (auto)"
            else -> "$code (system)"
        }
    }

    private fun speedLabel(): String =
        if (ttsSpeed == 1.0f) "1.0× (normal)" else "${ttsSpeed}×"

    private fun pickTtsSpeed() {
        val speeds = arrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val labels = speeds.map {
            if (it == 1.0f) "1.0× (normal)" else "${it}×"
        }.toTypedArray()
        val current = speeds.indexOfFirst { it == ttsSpeed }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Voice speed")
            .setSingleChoiceItems(labels, current) { _, which ->
                ttsSpeed = speeds[which]
                savePrefs()
                try { tts?.setSpeechRate(ttsSpeed) } catch (e: Exception) {}
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ========= BACKUP (settings + bookmarks + history) =========
    private fun exportData() {
        pendingBackupJson = buildBackupJson()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "textreader_backup.json")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    private fun importData() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, IMPORT_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            EXPORT_REQUEST -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(pendingBackupJson.toByteArray())
                    }
                    toast("Backup exported")
                } catch (e: Exception) {
                    toast("Export failed: ${e.message}")
                }
            }
            IMPORT_REQUEST -> {
                try {
                    val text = contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readText() ?: return
                    applyBackupJson(text)
                    toast("Backup imported")
                } catch (e: Exception) {
                    toast("Import failed: ${e.message}")
                }
            }
        }
    }

    private fun buildBackupJson(): String {
        val settings = JSONObject()
            .put("engine", searchEngine)
            .put("results_per_page", resultsPerPage)
            .put("paras_per_page", parasPerPage)
            .put("max_chars", maxChars)
            .put("chronology_length", chronologyLength)
            .put("groq_key", groqKey)
            .put("night", isNight)
            .put("text_size", textSize.toDouble())
            .put("show_title", showTitle)
            .put("show_page_number", showPageNumber)
            .put("show_compact_url", showCompactUrl)
            .put("tts_mode", ttsMode)
            .put("tts_speed", ttsSpeed.toDouble())
        val bms = JSONArray()
        for (b in loadBookmarks()) {
            bms.put(JSONObject().put("title", b.title).put("url", b.url).put("block", b.blockIndex))
        }
        val hist = JSONArray()
        for (h in loadHistory()) {
            hist.put(JSONObject().put("title", h.first).put("url", h.second))
        }
        return JSONObject()
            .put("app", "TextReader")
            .put("version", 1)
            .put("settings", settings)
            .put("bookmarks", bms)
            .put("history", hist)
            .toString(2)
    }

    private fun applyBackupJson(text: String) {
        val root = JSONObject(text)
        val s = root.optJSONObject("settings")
        if (s != null) {
            searchEngine = s.optString("engine", searchEngine)
            resultsPerPage = s.optInt("results_per_page", resultsPerPage)
            parasPerPage = s.optInt("paras_per_page", parasPerPage)
            maxChars = s.optInt("max_chars", maxChars)
            chronologyLength = s.optInt("chronology_length", chronologyLength)
            groqKey = s.optString("groq_key", groqKey)
            isNight = s.optBoolean("night", isNight)
            textSize = s.optDouble("text_size", textSize.toDouble()).toFloat()
            showTitle = s.optBoolean("show_title", showTitle)
            showPageNumber = s.optBoolean("show_page_number", showPageNumber)
            showCompactUrl = s.optBoolean("show_compact_url", showCompactUrl)
            ttsMode = s.optString("tts_mode", ttsMode)
            ttsSpeed = s.optDouble("tts_speed", ttsSpeed.toDouble()).toFloat()
            try { tts?.setSpeechRate(ttsSpeed) } catch (e: Exception) {}
            savePrefs()
            applyTheme()
            contentView.textSize = textSize
            renderBlock()
        }
        val bms = root.optJSONArray("bookmarks")
        if (bms != null) {
            val list = mutableListOf<Bookmark>()
            for (i in 0 until bms.length()) {
                val b = bms.getJSONObject(i)
                list.add(Bookmark(b.optString("title"), b.optString("url"), b.optInt("block")))
            }
            saveBookmarks(list)
        }
        val hist = root.optJSONArray("history")
        if (hist != null) {
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until hist.length()) {
                val h = hist.getJSONObject(i)
                list.add(Pair(h.optString("title"), h.optString("url")))
            }
            prefs().edit()
                .putString("history", list.joinToString("|||") { "${it.first}::${it.second}" })
                .apply()
        }
    }

    private fun pickEngine() {
        val names = arrayOf(
            "DuckDuckGo Lite", "DuckDuckGo HTML", "Brave Search",
            "Google (text mode)", "Bing (text mode)"
        )
        val keys = arrayOf("duck_lite", "duck_html", "brave", "google", "bing")
        val current = keys.indexOf(searchEngine).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, current) { _, which ->
                searchEngine = keys[which]
                savePrefs()
                toast("Engine: ${engineNames[searchEngine]}")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun askInt(label: String, current: Int, min: Int, max: Int, onSet: (Int) -> Unit) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText(current.toString())
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v != null && v in min..max) {
                    onSet(v)
                    savePrefs()
                } else {
                    toast("Enter a number from $min to $max")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askGroqKey() {
        val input = EditText(this)
        input.hint = "gsk_…"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        input.setText(groqKey)
        AlertDialog.Builder(this)
            .setTitle("Groq API key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                groqKey = input.text.toString().trim()
                savePrefs()
            }
            .setNeutralButton("Clear") { _, _ ->
                groqKey = ""
                savePrefs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ========= THEME =========

    private fun applyTheme() {
        val bg = if (isNight) getColor(R.color.nightBackground) else getColor(R.color.dayBackground)
        val fg = if (isNight) getColor(R.color.nightText) else getColor(R.color.dayText)
        rootLayout.setBackgroundColor(bg)
        contentView.setTextColor(fg)
        contentView.textSize = textSize
        urlInput.setTextColor(fg)
        urlInput.setHintTextColor(fg and 0x55FFFFFF.toInt())
    }

    private fun toggleTheme() {
        isNight = !isNight
        savePrefs()
        applyTheme()
    }

    // ========= BOOKMARKS =========

    private fun loadBookmarks(): MutableList<Bookmark> {
        val raw = getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
            .getString("list", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("||").mapNotNull {
            val p = it.split("::")
            if (p.size == 3) Bookmark(p[0], p[1], p[2].toIntOrNull() ?: 0) else null
        }.toMutableList()
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val encoded = list.joinToString("||") { "${it.title}::${it.url}::${it.blockIndex}" }
        getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
            .edit().putString("list", encoded).apply()
    }

    private fun addOrUpdateBookmark() {
        val url = currentUrl ?: return
        val list = loadBookmarks()
        val idx = list.indexOfFirst { it.url == url }
        val bm = Bookmark(if (currentTitle.isBlank()) url else currentTitle, url, currentBlockIndex)
        if (idx >= 0) list[idx] = bm else list.add(bm)
        saveBookmarks(list)
    }

    private fun removeCurrentBookmark() {
        val url = currentUrl ?: return
        val list = loadBookmarks()
        val before = list.size
        saveBookmarks(list.filterNot { it.url == url })
        toast(if (list.size != before) "Bookmark removed" else "No bookmark for this page")
    }

    private fun showBookmarksDialog() {
        val list = loadBookmarks()
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("No bookmarks yet.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val titles = list.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(titles) { _, which ->
                val bm = list[which]
                urlInput.setText(bm.url)
                loadUrl(bm.url)
                currentBlockIndex = bm.blockIndex
            }
            .setPositiveButton("Add current") { _, _ -> addOrUpdateBookmark() }
            .setNeutralButton("Remove…") { _, _ -> showRemoveBookmarksDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRemoveBookmarksDialog() {
        val list = loadBookmarks()
        if (list.isEmpty()) {
            toast("No bookmarks to remove")
            return
        }
        val titles = list.map { it.title }.toTypedArray()
        val checked = BooleanArray(list.size)
        AlertDialog.Builder(this)
            .setTitle("Remove bookmarks")
            .setMultiChoiceItems(titles, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Remove selected") { _, _ ->
                val toRemove = list.indices
                    .filter { checked[it] }
                    .map { list[it].url }
                    .toSet()
                if (toRemove.isEmpty()) {
                    toast("Nothing selected")
                    return@setPositiveButton
                }
                saveBookmarks(loadBookmarks().filterNot { it.url in toRemove })
                toast("Removed ${toRemove.size} bookmark(s)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun maybeOfferBookmarkRestore() {
        val url = currentUrl ?: return
        val bm = loadBookmarks().firstOrNull { it.url == url } ?: return

        AlertDialog.Builder(this)
            .setMessage("Resume from last position?")
            .setPositiveButton("Yes") { _, _ ->
                if (bm.blockIndex in blocks.indices) {
                    currentBlockIndex = bm.blockIndex
                    renderBlock()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (currentUrl != null && blocks.isNotEmpty()) addOrUpdateBookmark()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }

    // ========= MISC =========

    private fun setLoading(on: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun showMessage(msg: String) {
        contentView.text = msg
        blocks = listOf(msg)
        currentBlockIndex = 0
        setLoading(false)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun urlencode(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; TextReader) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private const val EXPORT_REQUEST = 1001
        private const val IMPORT_REQUEST = 1002

        private const val TTS_HIGHLIGHT_COLOR = 0x99FFC107.toInt()
    }
}
