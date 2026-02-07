package com.example.textreader

import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GestureDetectorCompat
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Bookmark(
    val title: String,
    val url: String,
    val blockIndex: Int
)

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var urlInput: EditText
    private lateinit var contentView: TextView
    private lateinit var goButton: Button
    private lateinit var bookmarksButton: Button
    private lateinit var themeButton: Button
    private lateinit var exitButton: Button

    private lateinit var gestureDetector: GestureDetectorCompat

    private var blocks: List<String> = emptyList()
    private var currentBlockIndex = 0
    private var currentUrl: String? = null
    private var currentTitle: String = ""
    private var isNight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        urlInput = findViewById(R.id.urlInput)
        contentView = findViewById(R.id.contentView)
        goButton = findViewById(R.id.goButton)
        bookmarksButton = findViewById(R.id.bookmarksButton)
        themeButton = findViewById(R.id.themeButton)
        exitButton = findViewById(R.id.exitButton)

        // ---------------------------
        // FIXED GESTURE LISTENER
        // ---------------------------
        gestureDetector = GestureDetectorCompat(
            this,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent?): Boolean {
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent?,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    if (e1 == null || e2 == null) return false

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
            if (input.startsWith("http://") || input.startsWith("https://")) {
                loadUrl(input)
            } else {
                searchDuckDuckGoLite(input)
            }
        }

        bookmarksButton.setOnClickListener { showBookmarksDialog() }
        themeButton.setOnClickListener { toggleTheme() }
        exitButton.setOnClickListener { finish() }

        applyTheme()
        showMessage("Enter URL or search query, then tap Go.")
    }

    private fun applyTheme() {
        val bg = if (isNight) getColor(R.color.nightBackground) else getColor(R.color.dayBackground)
        val fg = if (isNight) getColor(R.color.nightText) else getColor(R.color.dayText)
        rootLayout.setBackgroundColor(bg)
        contentView.setTextColor(fg)
        urlInput.setTextColor(fg)
        urlInput.setHintTextColor(fg and 0x55FFFFFF.toInt())
    }

    private fun toggleTheme() {
        isNight = !isNight
        applyTheme()
    }

    private fun showMessage(msg: String) {
        contentView.text = msg
        blocks = listOf(msg)
        currentBlockIndex = 0
    }

    private fun nextBlock() {
        if (currentBlockIndex < blocks.size - 1) {
            currentBlockIndex++
            contentView.text = blocks[currentBlockIndex]
        }
    }

    private fun previousBlock() {
        if (currentBlockIndex > 0) {
            currentBlockIndex--
            contentView.text = blocks[currentBlockIndex]
        }
    }

    private fun searchDuckDuckGoLite(query: String) {
        showMessage("Searching DuckDuckGo Lite…")
        thread {
            try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = "https://duckduckgo.com/lite/?q=$q"
                val html = httpGet(url)
                val results = parseDuckDuckGoLiteResults(html)
                runOnUiThread {
                    if (results.isEmpty()) showMessage("No results.")
                    else showResultsDialog(results)
                }
            } catch (e: Exception) {
                runOnUiThread { showMessage("Error: ${e.message}") }
            }
        }
    }

    private fun showResultsDialog(results: List<Pair<String, String>>) {
        val titles = results.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Results")
            .setItems(titles) { _, which ->
                val url = results[which].second
                urlInput.setText(url)
                loadUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadUrl(url: String) {
        currentUrl = url
        showMessage("Loading…")
        thread {
            try {
                val html = httpGet(url)
                val articleText = extractMainText(html)
                val newBlocks = splitIntoBlocks(articleText, 800)
                runOnUiThread {
                    currentTitle = url
                    blocks = if (newBlocks.isEmpty()) listOf("No readable text.") else newBlocks
                    currentBlockIndex = 0
                    contentView.text = blocks[currentBlockIndex]
                    maybeOfferBookmarkRestore()
                }
            } catch (e: Exception) {
                runOnUiThread { showMessage("Error: ${e.message}") }
            }
        }
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "TextReader/1.0")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun extractMainText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<script.*?>.*?</script>"), "")
            .replace(Regex("(?is)<style.*?>.*?</style>"), "")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</p>"), "\n\n")
            .replace(Regex("(?is)<.*?>"), "")

        return cleaned.lines()
            .map { it.trim() }
            .filter { it.length > 40 }
            .joinToString("\n\n")
    }

    private fun splitIntoBlocks(text: String, maxChars: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(Regex("\\s+"))
        val blocks = mutableListOf<String>()
        val sb = StringBuilder()

        for (w in words) {
            if (sb.length + w.length + 1 > maxChars) {
                blocks.add(sb.toString().trim())
                sb.clear()
            }
            sb.append(w).append(' ')
        }

        if (sb.isNotBlank()) blocks.add(sb.toString().trim())
        return blocks
    }

    private fun parseDuckDuckGoLiteResults(html: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val regex = Regex("<a href=\"(http[^\"]+)\">([^<]+)</a>", RegexOption.IGNORE_CASE)
        for (m in regex.findAll(html)) {
            val url = m.groupValues[1]
            val title = m.groupValues[2]
            results.add(title to url)
        }
        return results.take(20)
    }

    private fun prefs() = getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    private fun loadBookmarks(): MutableList<Bookmark> {
        val raw = prefs().getString("list", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("||").mapNotNull {
            val p = it.split("::")
            if (p.size == 3) Bookmark(p[0], p[1], p[2].toIntOrNull() ?: 0) else null
        }.toMutableList()
    }

    private fun saveBookmarks(list: List<Bookmark>) {
        val encoded = list.joinToString("||") { "${it.title}::${it.url}::${it.blockIndex}" }
        prefs().edit().putString("list", encoded).apply()
    }

    private fun addOrUpdateBookmark() {
        val url = currentUrl ?: return
        val list = loadBookmarks()
        val idx = list.indexOfFirst { it.url == url }
        val bm = Bookmark(currentTitle.ifBlank { url }, url, currentBlockIndex)
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
                    contentView.text = blocks[currentBlockIndex]
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (currentUrl != null && blocks.isNotEmpty()) addOrUpdateBookmark()
    }
}
