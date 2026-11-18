package com.lumina.reader.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.lumina.reader.R
import com.lumina.reader.data.model.Book
import com.lumina.reader.data.model.Bookmark
import com.lumina.reader.data.model.DisplaySettings
import com.lumina.reader.data.model.TocItem
import com.lumina.reader.data.parser.EpubExtractor
import com.lumina.reader.data.parser.EpubMetadata
import com.lumina.reader.data.storage.BookRepository
import com.lumina.reader.databinding.ActivityReaderBinding
import com.lumina.reader.databinding.BottomSheetDisplayBinding
import com.lumina.reader.databinding.DialogTocBinding
import com.lumina.reader.ui.adapter.TocAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var repository: BookRepository

    private var currentBook: Book? = null
    private var displaySettings: DisplaySettings = DisplaySettings()
    private var isChromeVisible: Boolean = true

    // EPUB state
    private var epubMeta: EpubMetadata? = null
    private var currentSpineIndex: Int = 0

    // PDF state
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var currentPdfPage: Int = 0
    private var totalPdfPages: Int = 1

    // Text state
    private var textLines: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = BookRepository(this)
        displaySettings = repository.loadDisplaySettings()

        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (bookId == null) {
            finish()
            return
        }

        currentBook = repository.getBook(bookId)
        if (currentBook == null) {
            Toast.makeText(this, "Book not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        applyDisplaySettings()
        openBook()
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
    }

    private fun setupUI() {
        val book = currentBook ?: return
        binding.tvReaderTitle.text = book.title
        binding.tvReaderAuthor.text = book.author

        // Back button
        binding.btnBack.setOnClickListener {
            saveCurrentProgress()
            finish()
        }

        // TOC button
        binding.btnToc.setOnClickListener {
            showTocDialog()
        }

        // Bookmark button
        binding.btnBookmark.setOnClickListener {
            toggleBookmark()
        }

        // Display settings button (Aa)
        binding.btnDisplaySettings.setOnClickListener {
            showDisplaySettingsBottomSheet()
        }

        // Bottom scrubber buttons
        binding.btnBottomPrev.setOnClickListener {
            navigatePrevious()
        }

        binding.btnBottomNext.setOnClickListener {
            navigateNext()
        }

        // Scrubber SeekBar
        binding.sbReadingProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvScrubberPercent.text = "$progress%"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                jumpToPercentage(progress)
            }
        })

        // Single tap gesture detector for PDF & Plain Text
        val singleTapDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                toggleChrome()
                return true
            }
        })

        binding.ivPdfPage.setOnTouchListener { _, event ->
            singleTapDetector.onTouchEvent(event)
            true
        }

        binding.svTextContent.setOnTouchListener { _, event ->
            singleTapDetector.onTouchEvent(event)
            false
        }
    }

    private fun applyDisplaySettings() {
        // Keep Screen Awake
        if (displaySettings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Night dimmer
        val dimmerAlpha = (displaySettings.dimmerPercent / 100f) * 0.85f
        binding.viewDimmerOverlay.alpha = dimmerAlpha

        // Theme colors
        val (bgColor, textColor, barColor) = getThemeColors(displaySettings.theme)
        binding.layoutReaderRoot.setBackgroundColor(Color.parseColor(bgColor))
        binding.layoutTopBar.setBackgroundColor(Color.parseColor(barColor))
        binding.layoutBottomBar.setBackgroundColor(Color.parseColor(barColor))

        // Re-apply to currently loaded book
        when (currentBook?.format) {
            "epub" -> reloadEpubChapter()
            "pdf" -> renderPdfPage(currentPdfPage)
            "txt", "md" -> styleTextView()
        }
    }

    private fun getThemeColors(theme: String): Triple<String, String, String> {
        return when (theme) {
            "light" -> Triple("#FDFDFD", "#1B1C1E", "#FAF8F9FA")
            "sepia" -> Triple("#F5EEDC", "#2F2519", "#FAEDE3CD")
            "sand" -> Triple("#EBE5D8", "#252422", "#FAE1D9C9")
            "charcoal" -> Triple("#1E2025", "#E2E5EB", "#FA17191D")
            "oled" -> Triple("#000000", "#D4D4D8", "#FA0A0A0C")
            "emerald" -> Triple("#0D1A16", "#D1FAE5", "#FA091410")
            else -> Triple("#F5EEDC", "#2F2519", "#FAEDE3CD")
        }
    }

    fun toggleChrome() {
        isChromeVisible = !isChromeVisible
        val topBar = binding.layoutTopBar
        val bottomBar = binding.layoutBottomBar

        if (isChromeVisible) {
            topBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            topBar.animate().translationY(0f).setDuration(220).start()
            bottomBar.animate().translationY(0f).setDuration(220).start()
        } else {
            topBar.animate().translationY(-topBar.height.toFloat()).setDuration(220).withEndAction {
                topBar.visibility = View.GONE
            }.start()
            bottomBar.animate().translationY(bottomBar.height.toFloat()).setDuration(220).withEndAction {
                bottomBar.visibility = View.GONE
            }.start()
        }
    }

    private fun openBook() {
        val book = currentBook ?: return
        when (book.format) {
            "epub" -> openEpub(book)
            "pdf" -> openPdf(book)
            "txt", "md" -> openText(book)
            else -> openText(book)
        }
        updateBookmarkIcon()
    }

    // ----------------------------------------------------
    // EPUB Reader Engine
    // ----------------------------------------------------
    private class EpubJsBridge(private val activity: ReaderActivity) {
        @android.webkit.JavascriptInterface
        fun toggleChrome() {
            activity.runOnUiThread {
                activity.toggleChrome()
            }
        }

        @android.webkit.JavascriptInterface
        fun nextChapter() {
            activity.runOnUiThread {
                activity.navigateNext()
            }
        }

        @android.webkit.JavascriptInterface
        fun prevChapter() {
            activity.runOnUiThread {
                activity.navigatePrevious()
            }
        }
    }

    private fun openEpub(book: Book) {
        binding.webViewEpub.visibility = View.VISIBLE
        binding.ivPdfPage.visibility = View.GONE
        binding.svTextContent.visibility = View.GONE

        binding.webViewEpub.settings.apply {
            javaScriptEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled = true
        }

        binding.webViewEpub.addJavascriptInterface(EpubJsBridge(this), "Android")

        binding.webViewEpub.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.scheme == "file") {
                    val file = File(url.path ?: "")
                    if (file.exists() && file.isFile) {
                        val mimeType = when (file.extension.lowercase()) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "svg" -> "image/svg+xml"
                            "webp" -> "image/webp"
                            "css" -> "text/css"
                            "js" -> "application/javascript"
                            else -> null
                        }
                        if (mimeType != null) {
                            try {
                                return WebResourceResponse(mimeType, "UTF-8", file.inputStream())
                            } catch (e: Exception) {}
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val file = File(book.filePath)
            val meta = EpubExtractor.extractMetadata(this@ReaderActivity, file, book.id)
            withContext(Dispatchers.Main) {
                epubMeta = meta
                currentSpineIndex = book.currentLocation?.toIntOrNull() ?: 0
                if (currentSpineIndex >= meta.spineFiles.size) currentSpineIndex = 0
                reloadEpubChapter()
            }
        }
    }

    private fun reloadEpubChapter() {
        val meta = epubMeta ?: return
        val book = currentBook ?: return
        if (meta.spineFiles.isEmpty()) return

        val spinePath = meta.spineFiles[currentSpineIndex]
        val chapterFile = File(meta.unpackedDir, spinePath)
        val baseURL = "file://${chapterFile.parentFile?.absolutePath ?: meta.unpackedDir}/"

        CoroutineScope(Dispatchers.IO).launch {
            val rawHtml = EpubExtractor.readChapterHtml(meta.unpackedDir, spinePath)
            val styledHtml = injectEpubStyles(rawHtml)
            withContext(Dispatchers.Main) {
                binding.webViewEpub.scrollTo(0, 0)
                binding.webViewEpub.loadDataWithBaseURL(baseURL, styledHtml, "text/html", "UTF-8", null)

                val chapterTitle = meta.toc.getOrNull(currentSpineIndex)?.label ?: "Chapter ${currentSpineIndex + 1}"
                val progress = ((currentSpineIndex + 1).toFloat() / meta.spineFiles.size) * 100f

                binding.tvChapterTitle.text = chapterTitle
                binding.tvScrubberPercent.text = "${progress.toInt()}%"
                binding.sbReadingProgress.progress = progress.toInt()

                currentBook?.currentLocation = currentSpineIndex.toString()
                currentBook?.chapterTitle = chapterTitle
                currentBook?.progress = progress
                saveCurrentProgress()
                updateBookmarkIcon()
            }
        }
    }

    private fun injectEpubStyles(html: String): String {
        val (bg, text, _) = getThemeColors(displaySettings.theme)
        val fontCss = when (displaySettings.fontFamily) {
            "sans" -> "font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;"
            "mono" -> "font-family: monospace;"
            else -> "font-family: Georgia, serif;"
        }

        val styleTag = """
            <style>
                body {
                    background-color: $bg !important;
                    color: $text !important;
                    $fontCss
                    font-size: ${displaySettings.fontSize}px !important;
                    line-height: ${displaySettings.lineSpacing} !important;
                    padding: 72px ${displaySettings.margin}px 120px ${displaySettings.margin}px !important;
                    margin: 0 !important;
                    word-break: break-word !important;
                    box-sizing: border-box !important;
                }
                p {
                    margin-bottom: 1em !important;
                    color: $text !important;
                    font-size: ${displaySettings.fontSize}px !important;
                    line-height: ${displaySettings.lineSpacing} !important;
                }
                h1, h2, h3, h4, h5, h6 {
                    color: $text !important;
                }
                a { color: #F59E0B !important; }
                img { max-width: 100% !important; height: auto !important; display: block; margin: 12px auto !important; }
                image { max-width: 100% !important; height: auto !important; }
                svg { max-width: 100% !important; height: auto !important; }
                .inkwell-footer-nav {
                    margin-top: 50px;
                    padding: 24px 0 40px 0;
                    display: flex;
                    justify-content: space-between;
                    gap: 12px;
                    border-top: 1px solid rgba(128,128,128,0.25);
                }
                .inkwell-nav-btn {
                    flex: 1;
                    padding: 14px 8px;
                    background: rgba(128,128,128,0.15);
                    color: inherit;
                    border: 1px solid rgba(128,128,128,0.3);
                    border-radius: 12px;
                    font-weight: bold;
                    font-size: 13px;
                    cursor: pointer;
                }
                .inkwell-nav-next {
                    background: #F59E0B !important;
                    color: #0F1115 !important;
                    border: none !important;
                }
            </style>
        """.trimIndent()

        val footerHtml = """
            <div class="inkwell-footer-nav">
                <button class="inkwell-nav-btn" onclick="window.Android && window.Android.prevChapter()">← Previous Chapter</button>
                <button class="inkwell-nav-btn inkwell-nav-next" onclick="window.Android && window.Android.nextChapter()">Next Chapter →</button>
            </div>
            <script>
                (function() {
                    let startX = 0, startY = 0, startTime = 0;
                    document.addEventListener('touchstart', function(e) {
                        if (e.touches && e.touches.length > 0) {
                            startX = e.touches[0].clientX;
                            startY = e.touches[0].clientY;
                            startTime = Date.now();
                        }
                    }, { passive: true });
                    document.addEventListener('touchend', function(e) {
                        if (e.changedTouches && e.changedTouches.length > 0) {
                            const dx = Math.abs(e.changedTouches[0].clientX - startX);
                            const dy = Math.abs(e.changedTouches[0].clientY - startY);
                            const dt = Date.now() - startTime;
                            if (dx < 20 && dy < 20 && dt < 350) {
                                if (window.Android && window.Android.toggleChrome) {
                                    window.Android.toggleChrome();
                                }
                            }
                        }
                    }, { passive: true });
                })();
            </script>
        """.trimIndent()

        var result = if (html.contains("</head>", ignoreCase = true)) {
            html.replace("</head>", "$styleTag</head>")
        } else {
            "<html><head>$styleTag</head><body>$html</body></html>"
        }

        result = if (result.contains("</body>", ignoreCase = true)) {
            result.replace("</body>", "$footerHtml</body>")
        } else {
            "$result$footerHtml"
        }

        return result
    }

    // ----------------------------------------------------
    // PDF Reader Engine
    // ----------------------------------------------------
    private fun openPdf(book: Book) {
        binding.webViewEpub.visibility = View.GONE
        binding.ivPdfPage.visibility = View.VISIBLE
        binding.svTextContent.visibility = View.GONE

        try {
            val file = File(book.filePath)
            pdfDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfDescriptor!!)
            totalPdfPages = pdfRenderer?.pageCount ?: 1

            currentPdfPage = book.currentLocation?.toIntOrNull() ?: 0
            if (currentPdfPage >= totalPdfPages) currentPdfPage = 0

            renderPdfPage(currentPdfPage)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not open PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPdfPage(pageIndex: Int) {
        val renderer = pdfRenderer ?: return
        if (pageIndex < 0 || pageIndex >= totalPdfPages) return

        try {
            val page = renderer.openPage(pageIndex)
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            binding.ivPdfPage.setImageBitmap(bitmap)

            // Dark/Sepia theme filter for PDF
            when (displaySettings.theme) {
                "charcoal", "oled" -> {
                    val colorMatrix = ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                    binding.ivPdfPage.colorFilter = ColorMatrixColorFilter(colorMatrix)
                }
                "sepia", "sand" -> {
                    val colorMatrix = ColorMatrix().apply {
                        setScale(0.95f, 0.90f, 0.80f, 1.0f)
                    }
                    binding.ivPdfPage.colorFilter = ColorMatrixColorFilter(colorMatrix)
                }
                else -> {
                    binding.ivPdfPage.clearColorFilter()
                }
            }

            val progress = ((pageIndex + 1).toFloat() / totalPdfPages) * 100f
            binding.tvChapterTitle.text = "Page ${pageIndex + 1} of $totalPdfPages"
            binding.tvScrubberPercent.text = "${progress.toInt()}%"
            binding.sbReadingProgress.progress = progress.toInt()

            currentBook?.currentLocation = pageIndex.toString()
            currentBook?.chapterTitle = "Page ${pageIndex + 1}"
            currentBook?.progress = progress
            saveCurrentProgress()
            updateBookmarkIcon()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun closePdfRenderer() {
        try {
            pdfRenderer?.close()
            pdfDescriptor?.close()
        } catch (e: Exception) {}
    }

    // ----------------------------------------------------
    // Plain Text Reader Engine
    // ----------------------------------------------------
    private fun openText(book: Book) {
        binding.webViewEpub.visibility = View.GONE
        binding.ivPdfPage.visibility = View.GONE
        binding.svTextContent.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val file = File(book.filePath)
            val text = if (file.exists()) file.readText() else "Empty book."
            withContext(Dispatchers.Main) {
                binding.tvTextBookContent.text = text
                styleTextView()

                binding.tvChapterTitle.text = book.chapterTitle ?: "Reading"
                binding.tvScrubberPercent.text = "${book.progress.toInt()}%"
                binding.sbReadingProgress.progress = book.progress.toInt()
            }
        }
    }

    private fun styleTextView() {
        val (_, text, _) = getThemeColors(displaySettings.theme)
        binding.tvTextBookContent.setTextColor(Color.parseColor(text))
        binding.tvTextBookContent.textSize = displaySettings.fontSize.toFloat()
        binding.tvTextBookContent.setLineSpacing(0f, displaySettings.lineSpacing)
        binding.tvTextBookContent.setPadding(
            displaySettings.margin * 2,
            32,
            displaySettings.margin * 2,
            120
        )

        binding.tvTextBookContent.typeface = when (displaySettings.fontFamily) {
            "sans" -> Typeface.SANS_SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SERIF
        }
    }

    // ----------------------------------------------------
    // Navigation
    // ----------------------------------------------------
    private fun navigatePrevious() {
        when (currentBook?.format) {
            "epub" -> {
                if (currentSpineIndex > 0) {
                    currentSpineIndex--
                    reloadEpubChapter()
                }
            }
            "pdf" -> {
                if (currentPdfPage > 0) {
                    currentPdfPage--
                    renderPdfPage(currentPdfPage)
                }
            }
            "txt", "md" -> {
                binding.svTextContent.pageScroll(View.FOCUS_UP)
            }
        }
    }

    private fun navigateNext() {
        when (currentBook?.format) {
            "epub" -> {
                val meta = epubMeta ?: return
                if (currentSpineIndex < meta.spineFiles.size - 1) {
                    currentSpineIndex++
                    reloadEpubChapter()
                }
            }
            "pdf" -> {
                if (currentPdfPage < totalPdfPages - 1) {
                    currentPdfPage++
                    renderPdfPage(currentPdfPage)
                }
            }
            "txt", "md" -> {
                binding.svTextContent.pageScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun jumpToPercentage(percent: Int) {
        when (currentBook?.format) {
            "epub" -> {
                val meta = epubMeta ?: return
                val targetIndex = ((percent / 100f) * (meta.spineFiles.size - 1)).toInt().coerceIn(0, meta.spineFiles.size - 1)
                currentSpineIndex = targetIndex
                reloadEpubChapter()
            }
            "pdf" -> {
                val targetPage = ((percent / 100f) * (totalPdfPages - 1)).toInt().coerceIn(0, totalPdfPages - 1)
                currentPdfPage = targetPage
                renderPdfPage(currentPdfPage)
            }
        }
    }

    private fun saveCurrentProgress() {
        val book = currentBook ?: return
        book.lastReadAt = System.currentTimeMillis()
        repository.updateBook(book)
    }

    // ----------------------------------------------------
    // Bookmarks
    // ----------------------------------------------------
    private fun toggleBookmark() {
        val book = currentBook ?: return
        val currentLocation = book.currentLocation ?: "0"
        val existing = repository.getBookmarksForBook(book.id).find { it.location == currentLocation }

        if (existing != null) {
            repository.deleteBookmark(existing.id)
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
        } else {
            val bm = Bookmark(
                id = "bm_${System.currentTimeMillis()}",
                bookId = book.id,
                location = currentLocation,
                title = book.chapterTitle ?: "Page $currentLocation"
            )
            repository.addBookmark(bm)
            Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
        }
        updateBookmarkIcon()
    }

    private fun updateBookmarkIcon() {
        val book = currentBook ?: return
        val currentLocation = book.currentLocation ?: "0"
        val isBookmarked = repository.getBookmarksForBook(book.id).any { it.location == currentLocation }

        if (isBookmarked) {
            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark_filled)
        } else {
            binding.btnBookmark.setImageResource(R.drawable.ic_bookmark)
        }
    }

    // ----------------------------------------------------
    // Table of Contents & Bookmarks Dialog
    // ----------------------------------------------------
    private fun showTocDialog() {
        val book = currentBook ?: return
        val dialog = AlertDialog.Builder(this).create()
        val dBinding = DialogTocBinding.inflate(layoutInflater)
        dialog.setView(dBinding.root)

        val tocAdapter = TocAdapter(
            onChapterClick = { item ->
                when (book.format) {
                    "epub" -> {
                        currentSpineIndex = item.location.toIntOrNull() ?: 0
                        reloadEpubChapter()
                    }
                    "pdf" -> {
                        currentPdfPage = item.location.toIntOrNull() ?: 0
                        renderPdfPage(currentPdfPage)
                    }
                }
                dialog.dismiss()
            },
            onBookmarkClick = { bm ->
                when (book.format) {
                    "epub" -> {
                        currentSpineIndex = bm.location.toIntOrNull() ?: 0
                        reloadEpubChapter()
                    }
                    "pdf" -> {
                        currentPdfPage = bm.location.toIntOrNull() ?: 0
                        renderPdfPage(currentPdfPage)
                    }
                }
                dialog.dismiss()
            },
            onDeleteBookmarkClick = { bm ->
                repository.deleteBookmark(bm.id)
                val updatedBookmarks = repository.getBookmarksForBook(book.id)
                // refresh bookmarks tab
                dBinding.tabLayoutToc.getTabAt(1)?.select()
                updateBookmarkIcon()
            }
        )

        dBinding.rvTocItems.apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            adapter = tocAdapter
        }

        val chapters = when (book.format) {
            "epub" -> epubMeta?.toc ?: emptyList()
            "pdf" -> (0 until totalPdfPages).map { TocItem("page_$it", "Page ${it + 1}", it.toString()) }
            else -> emptyList()
        }

        tocAdapter.setChapters(chapters)

        dBinding.tabLayoutToc.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 1) {
                    val bookmarks = repository.getBookmarksForBook(book.id)
                    tocAdapter.setBookmarks(bookmarks)
                    dBinding.tvTocEmpty.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
                    dBinding.rvTocItems.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    tocAdapter.setChapters(chapters)
                    dBinding.tvTocEmpty.visibility = if (chapters.isEmpty()) View.VISIBLE else View.GONE
                    dBinding.rvTocItems.visibility = if (chapters.isEmpty()) View.GONE else View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        dBinding.btnCloseToc.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ----------------------------------------------------
    // Display & Style (Aa) Bottom Sheet
    // ----------------------------------------------------
    private fun showDisplaySettingsBottomSheet() {
        val sheet = BottomSheetDialog(this)
        val sBinding = BottomSheetDisplayBinding.inflate(layoutInflater)
        sheet.setContentView(sBinding.root)

        // Setup theme buttons
        val themeButtons = listOf(
            sBinding.btnThemeLight to "light",
            sBinding.btnThemeSepia to "sepia",
            sBinding.btnThemeSand to "sand",
            sBinding.btnThemeCharcoal to "charcoal",
            sBinding.btnThemeOled to "oled",
            sBinding.btnThemeEmerald to "emerald"
        )

        themeButtons.forEach { (btn, theme) ->
            btn.setOnClickListener {
                displaySettings.theme = theme
                repository.saveDisplaySettings(displaySettings)
                applyDisplaySettings()
            }
        }

        // Font Size
        sBinding.tvFontSizeLabel.text = "${displaySettings.fontSize}sp"
        sBinding.sbFontSize.progress = (displaySettings.fontSize - 12).coerceIn(0, 20)

        sBinding.btnFontDecrease.setOnClickListener {
            if (displaySettings.fontSize > 12) {
                displaySettings.fontSize--
                sBinding.tvFontSizeLabel.text = "${displaySettings.fontSize}sp"
                sBinding.sbFontSize.progress = displaySettings.fontSize - 12
                repository.saveDisplaySettings(displaySettings)
                applyDisplaySettings()
            }
        }

        sBinding.btnFontIncrease.setOnClickListener {
            if (displaySettings.fontSize < 32) {
                displaySettings.fontSize++
                sBinding.tvFontSizeLabel.text = "${displaySettings.fontSize}sp"
                sBinding.sbFontSize.progress = displaySettings.fontSize - 12
                repository.saveDisplaySettings(displaySettings)
                applyDisplaySettings()
            }
        }

        sBinding.sbFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    displaySettings.fontSize = 12 + progress
                    sBinding.tvFontSizeLabel.text = "${displaySettings.fontSize}sp"
                    repository.saveDisplaySettings(displaySettings)
                    applyDisplaySettings()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Typeface
        sBinding.btnFontSerif.setOnClickListener {
            displaySettings.fontFamily = "serif"
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }
        sBinding.btnFontSans.setOnClickListener {
            displaySettings.fontFamily = "sans"
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }
        sBinding.btnFontMono.setOnClickListener {
            displaySettings.fontFamily = "mono"
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }

        // Line Spacing
        sBinding.btnLineSpacingCompact.setOnClickListener {
            displaySettings.lineSpacing = 1.3f
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }
        sBinding.btnLineSpacingNormal.setOnClickListener {
            displaySettings.lineSpacing = 1.6f
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }
        sBinding.btnLineSpacingRelaxed.setOnClickListener {
            displaySettings.lineSpacing = 2.0f
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }

        // Margins
        sBinding.btnMarginNarrow.setOnClickListener {
            displaySettings.margin = 12
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }
        sBinding.btnMarginNormal.setOnClickListener {
            displaySettings.margin = 20
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }
        sBinding.btnMarginWide.setOnClickListener {
            displaySettings.margin = 32
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }

        // Night dimmer
        sBinding.tvDimmerPercent.text = "${displaySettings.dimmerPercent}%"
        sBinding.sbNightDimmer.progress = displaySettings.dimmerPercent
        sBinding.sbNightDimmer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                displaySettings.dimmerPercent = progress
                sBinding.tvDimmerPercent.text = "$progress%"
                repository.saveDisplaySettings(displaySettings)
                applyDisplaySettings()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Keep Screen Awake
        sBinding.switchKeepAwake.isChecked = displaySettings.keepScreenAwake
        sBinding.switchKeepAwake.setOnCheckedChangeListener { _, isChecked ->
            displaySettings.keepScreenAwake = isChecked
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }

        sBinding.btnCloseDisplay.setOnClickListener {
            sheet.dismiss()
        }

        sheet.show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }
}
