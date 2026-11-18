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
import com.lumina.reader.ui.view.ZoomablePdfView
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

        // PDF tap & swipe gestures
        binding.ivPdfPage.onSingleTapListener = {
            toggleChrome()
        }
        binding.ivPdfPage.onPageSwipeListener = { direction ->
            if (direction > 0) {
                navigateNext()
            } else {
                navigatePrevious()
            }
        }

        // Single tap gesture detector for Plain Text
        val singleTapDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                toggleChrome()
                return true
            }
        })

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

        // Kindle E-Ink Paper Matte Grain Overlay
        binding.viewPaperTexture.isPaperTextureEnabled = displaySettings.einkMatteOverlay

        // Theme colors (Strictly 4 presets)
        val (bgColor, textColor, barColor) = getThemeColors(displaySettings.theme)
        val barColorInt = Color.parseColor(barColor)
        val textColorInt = Color.parseColor(textColor)

        binding.layoutReaderRoot.setBackgroundColor(Color.parseColor(bgColor))
        binding.layoutTopBar.setBackgroundColor(barColorInt)
        binding.layoutBottomBar.setBackgroundColor(barColorInt)

        // Make ambient minimal navigation controls match the paper theme
        binding.tvReaderTitle.setTextColor(textColorInt)
        binding.tvReaderAuthor.setTextColor(textColorInt)
        binding.tvReaderAuthor.alpha = 0.7f
        binding.tvChapterTitle.setTextColor(textColorInt)
        binding.tvChapterTitle.alpha = 0.7f
        binding.tvScrubberPercent.setTextColor(getColor(R.color.brand_primary))

        binding.btnBack.setColorFilter(textColorInt)
        binding.btnToc.setColorFilter(textColorInt)
        binding.btnBookmark.setColorFilter(textColorInt)
        binding.btnDisplaySettings.setColorFilter(textColorInt)
        binding.btnBottomPrev.setColorFilter(textColorInt)
        binding.btnBottomNext.setColorFilter(textColorInt)

        // Re-apply to currently loaded book
        when (currentBook?.format) {
            "epub" -> {
                updateWebViewDynamicStyles()
            }
            "pdf" -> renderPdfPage(currentPdfPage)
            "txt", "md" -> styleTextView()
        }
    }

    private fun updateWebViewDynamicStyles() {
        val (bg, text, _) = getThemeColors(displaySettings.theme)
        val fontCss = when (displaySettings.fontFamily) {
            "sans" -> "sans-serif"
            "mono" -> "monospace"
            else -> "serif"
        }
        val js = """
            (function() {
                var style = document.getElementById('inkwell-dynamic-style');
                if (style) {
                    style.innerHTML = `
                        * { box-sizing: border-box !important; }
                        html, body {
                            background-color: $bg !important;
                            color: $text !important;
                            margin: 0 !important;
                            padding: 0 !important;
                        }
                        body {
                            padding-left: ${displaySettings.margin}px !important;
                            padding-right: ${displaySettings.margin}px !important;
                            padding-top: 56px !important;
                            padding-bottom: 100px !important;
                        }
                        body, p, div, span, h1, h2, h3, h4, h5, h6, li, blockquote, a, td, th {
                            font-family: $fontCss !important;
                            font-size: ${displaySettings.fontSize}px !important;
                            line-height: ${displaySettings.lineSpacing} !important;
                            color: $text !important;
                        }
                    `;
                }
            })();
        """.trimIndent()
        binding.webViewEpub.evaluateJavascript(js, null)
    }

    private fun triggerEinkRefreshFlash() {
        if (!displaySettings.einkPageRefreshFlash) return
        binding.viewEinkFlash.visibility = View.VISIBLE
        binding.viewEinkFlash.alpha = 0.25f
        binding.viewEinkFlash.animate()
            .alpha(0f)
            .setDuration(60)
            .withEndAction {
                binding.viewEinkFlash.visibility = View.GONE
            }
            .start()
    }

    private fun getThemeColors(theme: String): Triple<String, String, String> {
        return when (theme) {
            "paperwhite" -> Triple("#F7F4EB", "#121316", "#EEF7F4EB")
            "sand" -> Triple("#EBE5D8", "#252422", "#EEEBE5D8")
            "charcoal" -> Triple("#1A1B1E", "#E2E5EB", "#EE1A1B1E")
            "oled" -> Triple("#000000", "#D4D4D8", "#EE000000")
            else -> Triple("#F7F4EB", "#121316", "#EEF7F4EB")
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
            "sans" -> "sans-serif"
            "mono" -> "monospace"
            else -> "serif"
        }

        val styleTag = """
            <style id="inkwell-dynamic-style">
                * {
                    box-sizing: border-box !important;
                }
                html, body {
                    background-color: $bg !important;
                    color: $text !important;
                    margin: 0 !important;
                    padding: 0 !important;
                    word-break: break-word !important;
                    text-rendering: optimizeLegibility !important;
                    -webkit-font-smoothing: antialiased !important;
                }
                body {
                    padding-left: ${displaySettings.margin}px !important;
                    padding-right: ${displaySettings.margin}px !important;
                    padding-top: 56px !important;
                    padding-bottom: 100px !important;
                }
                body, p, div, span, h1, h2, h3, h4, h5, h6, li, blockquote, a, td, th {
                    font-family: $fontCss !important;
                    font-size: ${displaySettings.fontSize}px !important;
                    line-height: ${displaySettings.lineSpacing} !important;
                    color: $text !important;
                    font-weight: ${if (displaySettings.theme == "paperwhite") "600" else "normal"} !important;
                    -webkit-font-smoothing: antialiased !important;
                    text-rendering: optimizeLegibility !important;
                }
                h1, h2, h3, h4, h5, h6 {
                    margin-top: 1.2em !important;
                    margin-bottom: 0.6em !important;
                    font-weight: bold !important;
                }
                p {
                    margin-top: 0 !important;
                    margin-bottom: 1em !important;
                }
                a { color: #1A73E8 !important; }
                img, image, svg { max-width: 100% !important; height: auto !important; display: block; margin: 12px auto !important; }
                .inkwell-footer-nav {
                    margin-top: 40px;
                    padding: 20px 0 40px 0;
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
                    background: #1A73E8 !important;
                    color: #FFFFFF !important;
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

        binding.ivPdfPage.fitMode = when (displaySettings.pdfFitMode) {
            "page" -> ZoomablePdfView.PdfFitMode.FIT_PAGE
            "screen" -> ZoomablePdfView.PdfFitMode.FIT_SCREEN
            else -> ZoomablePdfView.PdfFitMode.FIT_WIDTH
        }

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
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            // Scale up to crisp high-DPI resolution for phone display
            val targetWidth = (screenWidth * 1.5f).toInt().coerceAtLeast(1080)
            val aspectRatio = page.height.toFloat() / page.width.toFloat()
            val targetHeight = (targetWidth * aspectRatio).toInt()
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            binding.ivPdfPage.setPdfBitmap(bitmap)

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
                "paperwhite" -> {
                    // Kindle Paperwhite high-contrast ink: deepen black ink and increase boldness
                    val contrast = 1.35f
                    val translate = (-0.5f * contrast + 0.5f) * 255f - 18f
                    val colorMatrix = ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
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
        val (bg, text, _) = getThemeColors(displaySettings.theme)
        binding.layoutReaderRoot.setBackgroundColor(Color.parseColor(bg))
        binding.svTextContent.setBackgroundColor(Color.parseColor(bg))
        binding.tvTextBookContent.setTextColor(Color.parseColor(text))
        binding.tvTextBookContent.textSize = displaySettings.fontSize.toFloat()
        binding.tvTextBookContent.setLineSpacing(0f, displaySettings.lineSpacing)
        binding.tvTextBookContent.setPadding(
            displaySettings.margin,
            56,
            displaySettings.margin,
            120
        )

        val baseTypeface = when (displaySettings.fontFamily) {
            "sans" -> Typeface.SANS_SERIF
            "mono" -> Typeface.MONOSPACE
            else -> Typeface.SERIF
        }
        val weight = if (displaySettings.theme == "paperwhite") {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                Typeface.create(baseTypeface, 600, false)
            } else {
                Typeface.create(baseTypeface, Typeface.BOLD)
            }
        } else {
            baseTypeface
        }
        binding.tvTextBookContent.typeface = weight
    }

    // ----------------------------------------------------
    // Navigation
    // ----------------------------------------------------
    private fun navigatePrevious() {
        triggerEinkRefreshFlash()
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
        triggerEinkRefreshFlash()
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
        triggerEinkRefreshFlash()
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

        // Setup theme buttons (Strictly 4 presets)
        val themeButtons = listOf(
            sBinding.btnThemePaperwhite to "paperwhite",
            sBinding.btnThemeSand to "sand",
            sBinding.btnThemeCharcoal to "charcoal",
            sBinding.btnThemeOled to "oled"
        )

        fun updateControlSelectionUi() {
            val activeBg = getColor(R.color.brand_primary_container)
            val activeText = getColor(R.color.brand_on_primary_container)
            val inactiveBg = getColor(R.color.app_surface_variant)
            val inactiveText = getColor(R.color.text_secondary)

            // Typeface
            sBinding.btnFontSerif.setBackgroundColor(if (displaySettings.fontFamily == "serif") activeBg else inactiveBg)
            sBinding.btnFontSerif.setTextColor(if (displaySettings.fontFamily == "serif") activeText else inactiveText)
            sBinding.btnFontSans.setBackgroundColor(if (displaySettings.fontFamily == "sans") activeBg else inactiveBg)
            sBinding.btnFontSans.setTextColor(if (displaySettings.fontFamily == "sans") activeText else inactiveText)
            sBinding.btnFontMono.setBackgroundColor(if (displaySettings.fontFamily == "mono") activeBg else inactiveBg)
            sBinding.btnFontMono.setTextColor(if (displaySettings.fontFamily == "mono") activeText else inactiveText)

            // Line Spacing
            sBinding.btnLineSpacingCompact.setBackgroundColor(if (displaySettings.lineSpacing == 1.3f) activeBg else inactiveBg)
            sBinding.btnLineSpacingCompact.setTextColor(if (displaySettings.lineSpacing == 1.3f) activeText else inactiveText)
            sBinding.btnLineSpacingNormal.setBackgroundColor(if (displaySettings.lineSpacing == 1.6f) activeBg else inactiveBg)
            sBinding.btnLineSpacingNormal.setTextColor(if (displaySettings.lineSpacing == 1.6f) activeText else inactiveText)
            sBinding.btnLineSpacingRelaxed.setBackgroundColor(if (displaySettings.lineSpacing == 2.0f) activeBg else inactiveBg)
            sBinding.btnLineSpacingRelaxed.setTextColor(if (displaySettings.lineSpacing == 2.0f) activeText else inactiveText)

            // Margins
            sBinding.btnMarginNarrow.setBackgroundColor(if (displaySettings.margin <= 14) activeBg else inactiveBg)
            sBinding.btnMarginNarrow.setTextColor(if (displaySettings.margin <= 14) activeText else inactiveText)
            sBinding.btnMarginNormal.setBackgroundColor(if (displaySettings.margin in 15..28) activeBg else inactiveBg)
            sBinding.btnMarginNormal.setTextColor(if (displaySettings.margin in 15..28) activeText else inactiveText)
            sBinding.btnMarginWide.setBackgroundColor(if (displaySettings.margin > 28) activeBg else inactiveBg)
            sBinding.btnMarginWide.setTextColor(if (displaySettings.margin > 28) activeText else inactiveText)

            // Theme selection strokes
            val activeStroke = (resources.displayMetrics.density * 2.5f).toInt()
            val inactiveStroke = (resources.displayMetrics.density * 1f).toInt()
            sBinding.btnThemePaperwhite.strokeWidth = if (displaySettings.theme == "paperwhite") activeStroke else inactiveStroke
            sBinding.btnThemeSand.strokeWidth = if (displaySettings.theme == "sand") activeStroke else inactiveStroke
            sBinding.btnThemeCharcoal.strokeWidth = if (displaySettings.theme == "charcoal") activeStroke else inactiveStroke
            sBinding.btnThemeOled.strokeWidth = if (displaySettings.theme == "oled") activeStroke else inactiveStroke
        }

        themeButtons.forEach { (btn, theme) ->
            btn.setOnClickListener {
                displaySettings.theme = theme
                repository.saveDisplaySettings(displaySettings)
                updateControlSelectionUi()
                applyDisplaySettings()
            }
        }

        // Kindle E-Ink & Paper Simulation Controls
        sBinding.switchPaperMatte.isChecked = displaySettings.einkMatteOverlay
        sBinding.switchPaperMatte.setOnCheckedChangeListener { _, isChecked ->
            displaySettings.einkMatteOverlay = isChecked
            repository.saveDisplaySettings(displaySettings)
            applyDisplaySettings()
        }

        sBinding.switchEinkFlash.isChecked = displaySettings.einkPageRefreshFlash
        sBinding.switchEinkFlash.setOnCheckedChangeListener { _, isChecked ->
            displaySettings.einkPageRefreshFlash = isChecked
            repository.saveDisplaySettings(displaySettings)
        }

        // Mode Switching: PDF controls vs reflowable text controls
        val isPdf = currentBook?.format == "pdf"
        sBinding.layoutPdfControls.visibility = if (isPdf) View.VISIBLE else View.GONE
        sBinding.layoutTextControls.visibility = if (isPdf) View.GONE else View.VISIBLE

        if (isPdf) {
            fun updatePdfModeButtons() {
                val activeBg = getColor(R.color.brand_primary)
                val activeText = getColor(R.color.brand_on_primary)
                val inactiveBg = getColor(R.color.app_surface_variant)
                val inactiveText = getColor(R.color.text_secondary)

                val isWidth = binding.ivPdfPage.fitMode == ZoomablePdfView.PdfFitMode.FIT_WIDTH
                val isScreen = binding.ivPdfPage.fitMode == ZoomablePdfView.PdfFitMode.FIT_SCREEN
                val isPage = binding.ivPdfPage.fitMode == ZoomablePdfView.PdfFitMode.FIT_PAGE

                sBinding.btnPdfFitWidth.setBackgroundColor(if (isWidth) activeBg else inactiveBg)
                sBinding.btnPdfFitWidth.setTextColor(if (isWidth) activeText else inactiveText)

                sBinding.btnPdfFitScreen.setBackgroundColor(if (isScreen) activeBg else inactiveBg)
                sBinding.btnPdfFitScreen.setTextColor(if (isScreen) activeText else inactiveText)

                sBinding.btnPdfFitPage.setBackgroundColor(if (isPage) activeBg else inactiveBg)
                sBinding.btnPdfFitPage.setTextColor(if (isPage) activeText else inactiveText)
            }

            sBinding.btnPdfFitWidth.setOnClickListener {
                binding.ivPdfPage.applyFitMode(ZoomablePdfView.PdfFitMode.FIT_WIDTH)
                displaySettings.pdfFitMode = "width"
                repository.saveDisplaySettings(displaySettings)
                updatePdfModeButtons()
                Toast.makeText(this, "Fit Width: 100% width (0 side gaps)", Toast.LENGTH_SHORT).show()
            }

            sBinding.btnPdfFitScreen.setOnClickListener {
                binding.ivPdfPage.applyFitMode(ZoomablePdfView.PdfFitMode.FIT_SCREEN)
                displaySettings.pdfFitMode = "screen"
                repository.saveDisplaySettings(displaySettings)
                updatePdfModeButtons()
                Toast.makeText(this, "Fit Whole Screen: edge-to-edge fill", Toast.LENGTH_SHORT).show()
            }

            sBinding.btnPdfFitPage.setOnClickListener {
                binding.ivPdfPage.applyFitMode(ZoomablePdfView.PdfFitMode.FIT_PAGE)
                displaySettings.pdfFitMode = "page"
                repository.saveDisplaySettings(displaySettings)
                updatePdfModeButtons()
                Toast.makeText(this, "Fit Page: entire page visible", Toast.LENGTH_SHORT).show()
            }

            updatePdfModeButtons()

            sBinding.btnPdfZoomIn.setOnClickListener {
                binding.ivPdfPage.zoomIn()
            }

            sBinding.btnPdfZoomOut.setOnClickListener {
                binding.ivPdfPage.zoomOut()
            }

            sBinding.btnPdfZoomReset.setOnClickListener {
                binding.ivPdfPage.resetZoom()
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
            updateControlSelectionUi()
            applyDisplaySettings()
        }
        sBinding.btnFontSans.setOnClickListener {
            displaySettings.fontFamily = "sans"
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }
        sBinding.btnFontMono.setOnClickListener {
            displaySettings.fontFamily = "mono"
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }

        // Line Spacing
        sBinding.btnLineSpacingCompact.setOnClickListener {
            displaySettings.lineSpacing = 1.3f
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }
        sBinding.btnLineSpacingNormal.setOnClickListener {
            displaySettings.lineSpacing = 1.6f
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }
        sBinding.btnLineSpacingRelaxed.setOnClickListener {
            displaySettings.lineSpacing = 2.0f
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }

        // Margins
        sBinding.btnMarginNarrow.setOnClickListener {
            displaySettings.margin = 12
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }
        sBinding.btnMarginNormal.setOnClickListener {
            displaySettings.margin = 20
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }
        sBinding.btnMarginWide.setOnClickListener {
            displaySettings.margin = 36
            repository.saveDisplaySettings(displaySettings)
            updateControlSelectionUi()
            applyDisplaySettings()
        }

        // Initialize button selection UI state
        updateControlSelectionUi()

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
