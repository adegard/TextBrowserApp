package com.example.textreader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

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
    val image: String?
)

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var urlInput: EditText
    private lateinit var contentView: TextView
    private lateinit var goButton: Button
    private lateinit var prevButton: Button
    private lateinit var nextButton: Button
    private lateinit var bookmarksButton: Button
    private lateinit var themeButton: Button
    private lateinit var exitButton: Button
    private lateinit var menuButton: ImageButton
    private lateinit var cancelButton: ImageButton

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

    @Volatile
    private var loadGeneration = 0
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private var searchResults: List<Pair<String, String>> = emptyList()
    private var searchOffset = 0

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
        themeButton = findViewById(R.id.themeButton)
        exitButton = findViewById(R.id.exitButton)
        menuButton = findViewById(R.id.menuButton)
        cancelButton = findViewById(R.id.cancelButton)

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
            true
        }

        goButton.setOnClickListener {
            val input = urlInput.text.toString().trim()
            if (input.isNotEmpty()) handleInput(input)
        }

        bookmarksButton.setOnClickListener { showBookmarksDialog() }
        prevButton.setOnClickListener { previousBlock() }
        nextButton.setOnClickListener { nextBlock() }
        cancelButton.setOnClickListener { cancelLoad() }
        themeButton.setOnClickListener { toggleTheme() }
        exitButton.setOnClickListener { finish() }

        setupMenu()

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
            popup.menu.add(0, 9, 8, "A+ bigger text")
            popup.menu.add(0, 10, 9, "A− smaller text")
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
                    9 -> changeTextSize(2f)
                    10 -> changeTextSize(-2f)
                }
                true
            }
            popup.show()
        }
    }

    // ========= HOME =========

    private fun showHome() {
        currentUrl = null
        rawParagraphs = emptyList()
        currentLinks = emptyList()
        currentImageUrl = null
        val msg = """
            ═══ TEXT READER (Android) ═══
            A port of text_browser.py 1.52

            Type a URL or search query, then tap Go.
            Swipe LEFT/RIGHT to change blocks.

            ⋮ Menu: Home · Links · History · Settings
                     Share · I'm feeling lucky · AI

            Engine: ${engineNames[searchEngine]}
            Bookmarks: tap the Bookmarks button.
            Tip: "ifl query" opens the first result.
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

    private fun renderBlock() {
        if (blocks.isEmpty()) return
        val body = blocks[currentBlockIndex]
        val lines = mutableListOf<String>()
        if (showTitle && currentTitle.isNotEmpty()) lines.add("▌$currentTitle")
        if (showCompactUrl && !currentUrl.isNullOrBlank()) {
            lines.add("🔗 ${shortenMiddle(currentUrl!!, 44)}")
        }
        if (showPageNumber) {
            val remaining = blocks.size - currentBlockIndex - 1
            lines.add("— Block ${currentBlockIndex + 1}/${blocks.size} · $remaining left —")
        }
        val header = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n\n"
        contentView.text = header + body
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

    private fun cancelLoad() {
        loadGeneration++
        activeConnection?.disconnect()
        activeConnection = null
        showMessage("Loading cancelled.")
    }

    private fun nextBlock() {
        if (currentBlockIndex < blocks.size - 1) {
            currentBlockIndex++
            renderBlock()
        } else {
            tryLoadNextPart()
        }
    }

    private fun previousBlock() {
        if (currentBlockIndex > 0) {
            currentBlockIndex--
            renderBlock()
        }
    }

    // ========= NEXT PART (pagination) =========

    private fun tryLoadNextPart() {
        val base = currentUrl ?: return
        val current = rawParagraphs
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
                    runOnUiThread { toast("You've reached the end.") }
                    return@thread
                }
                runOnUiThread {
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
                runOnUiThread { toast("You've reached the end.") }
            }
        }
    }

    // ========= SEARCH =========

    private fun searchAndSelect(query: String) {
        val gen = ++loadGeneration
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
        val results = when (searchEngine) {
            "duck_html" -> searchDuckHtml(q)
            "brave" -> searchBrave(q)
            "google" -> searchGoogleText(q)
            "bing" -> searchBing(q)
            else -> searchDuckLite(q)
        }
        if (results.isEmpty() && searchEngine != "duck_lite") {
            val fallback = searchDuckLite(q)
            if (fallback.isNotEmpty()) return fallback
        }
        return results
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
            .setTitle("Results · ${engineNames[searchEngine]}")
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
            return Extracted(paragraphs, collectLinks(doc, base), extractTitle(doc), fetchMainImage(doc, base))
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

        return Extracted(paragraphs, collectLinks(main, base), extractTitle(doc), fetchMainImage(doc, base))
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
        val items = arrayOf(
            "Search engine: ${engineNames[searchEngine]}",
            "Results per page: $resultsPerPage",
            "Paragraphs per page: $parasPerPage",
            "Max chars per block: $maxChars",
            "Groq API key: ${if (groqKey.isBlank()) "NOT SET" else "SET"}",
            "Chronology length: $chronologyLength",
            "Text size: ${textSize.toInt()}sp",
            "Show page title: ${if (showTitle) "on" else "off"}",
            "Show page number: ${if (showPageNumber) "on" else "off"}",
            "Show compact URL: ${if (showCompactUrl) "on" else "off"}"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(items) { _, which ->
                when (which) {
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
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
            .setNegativeButton("Close", null)
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

    // ========= MISC =========

    private fun showMessage(msg: String) {
        contentView.text = msg
        blocks = listOf(msg)
        currentBlockIndex = 0
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
    }
}
