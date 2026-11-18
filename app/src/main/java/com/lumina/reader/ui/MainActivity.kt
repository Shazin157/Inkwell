package com.lumina.reader.ui

import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.GridLayoutManager
import com.lumina.reader.R
import com.lumina.reader.data.model.Book
import com.lumina.reader.data.storage.BookRepository
import com.lumina.reader.databinding.ActivityMainBinding
import com.lumina.reader.ui.adapter.BookAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: BookRepository
    private lateinit var bookAdapter: BookAdapter

    private var allBooks: List<Book> = emptyList()
    private var currentFilter: String = "all" // "all", "reading", "epub", "pdf", "txt"
    private var currentQuery: String = ""

    // Storage Access Framework File Picker
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importBookUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_LuminaReader)
        super.onCreate(savedInstanceState)

        repository = BookRepository(this)
        val initialSettings = repository.loadDisplaySettings()
        when (initialSettings.appThemeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()

        // Handle intent if opened from file manager
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateGreeting()
        loadBooks()
    }

    private fun updateGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greetingTime = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..22 -> "Good evening"
            else -> "Good night"
        }
        val settings = repository.loadDisplaySettings()
        binding.tvGreeting.text = "$greetingTime, ${settings.readerName}"

        val sdf = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        binding.tvGreetingSubtitle.text = "${sdf.format(Date())} • Kindle Sanctuary"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                importBookUri(uri)
            }
        }
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            books = emptyList(),
            onBookClick = { book ->
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                }
                startActivity(intent)
            },
            onBookMenuClick = { book, view ->
                showBookMenu(book, view)
            }
        )

        binding.rvBooks.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = bookAdapter
        }
    }

    private fun setupListeners() {
        // Settings Button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Upload FAB
        binding.fabUpload.setOnClickListener {
            openDocumentLauncher.launch(
                arrayOf(
                    "application/epub+zip",
                    "application/pdf",
                    "text/plain",
                    "*/*"
                )
            )
        }

        // Load Starter Classics Button
        binding.btnLoadStarter.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                repository.seedStarterClassics()
                withContext(Dispatchers.Main) {
                    loadBooks()
                    Toast.makeText(this@MainActivity, "Starter classics loaded!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Search EditText
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString()?.trim() ?: ""
                applyFilterAndSearch()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Filter Chips
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipReading) -> "reading"
                checkedIds.contains(R.id.chipEpub) -> "epub"
                checkedIds.contains(R.id.chipPdf) -> "pdf"
                checkedIds.contains(R.id.chipTxt) -> "txt"
                else -> "all"
            }
            applyFilterAndSearch()
        }
    }

    private fun loadBooks() {
        CoroutineScope(Dispatchers.IO).launch {
            var books = repository.getAllBooks()
            if (books.isEmpty()) {
                repository.seedStarterClassics()
                books = repository.getAllBooks()
            }
            withContext(Dispatchers.Main) {
                allBooks = books
                applyFilterAndSearch()
            }
        }
    }

    private fun applyFilterAndSearch() {
        var filtered = allBooks

        // Apply format / progress filter
        filtered = when (currentFilter) {
            "reading" -> filtered.filter { it.progress > 0f && it.progress < 100f }
            "epub" -> filtered.filter { it.format == "epub" }
            "pdf" -> filtered.filter { it.format == "pdf" }
            "txt" -> filtered.filter { it.format == "txt" || it.format == "md" }
            else -> filtered
        }

        // Apply search query
        if (currentQuery.isNotBlank()) {
            filtered = filtered.filter {
                it.title.contains(currentQuery, ignoreCase = true) ||
                it.author.contains(currentQuery, ignoreCase = true)
            }
        }

        bookAdapter.updateBooks(filtered)

        // Update Kindle "Jump Back In" Hero Card
        updateHeroCard()

        // Empty state visibility
        if (allBooks.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvBooks.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvBooks.visibility = View.VISIBLE
        }
    }

    private fun updateHeroCard() {
        val continueBook = allBooks.filter { it.progress > 0 }.maxByOrNull { it.lastReadAt }
            ?: allBooks.firstOrNull { it.progress > 0 }

        if (continueBook != null && currentFilter == "all" && currentQuery.isBlank()) {
            binding.cardContinueReading.visibility = View.VISIBLE
            binding.tvHeroTitle.text = continueBook.title
            binding.tvHeroAuthor.text = continueBook.author
            binding.tvHeroFormatBadge.text = continueBook.format.uppercase()
            binding.pbHeroProgress.progress = continueBook.progress.toInt()
            binding.tvHeroProgressPercent.text = "${continueBook.progress.toInt()}%"

            if (!continueBook.coverImagePath.isNullOrEmpty() && File(continueBook.coverImagePath).exists()) {
                binding.ivHeroCover.visibility = View.VISIBLE
                binding.tvHeroCoverInitial.visibility = View.GONE
                binding.ivHeroCover.setImageURI(Uri.fromFile(File(continueBook.coverImagePath)))
            } else {
                binding.ivHeroCover.visibility = View.GONE
                binding.tvHeroCoverInitial.visibility = View.VISIBLE
                binding.tvHeroCoverInitial.text = continueBook.title.take(1).uppercase()
                try {
                    binding.layoutHeroCover.setBackgroundColor(Color.parseColor(continueBook.coverColor))
                } catch (e: Exception) {}
            }

            val openBookAction = {
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, continueBook.id)
                }
                startActivity(intent)
            }
            binding.btnResumeReading.setOnClickListener { openBookAction() }
            binding.cardContinueReading.setOnClickListener { openBookAction() }
        } else {
            binding.cardContinueReading.visibility = View.GONE
        }
    }

    private fun importBookUri(uri: Uri) {
        Toast.makeText(this, "Importing book…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val book = repository.importBookFromUri(uri)
            withContext(Dispatchers.Main) {
                if (book != null) {
                    Toast.makeText(this@MainActivity, "Imported: ${book.title}", Toast.LENGTH_SHORT).show()
                    loadBooks()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to import book. Please ensure it is a valid EPUB, PDF, or TXT file.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showBookMenu(book: Book, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Read")
        popup.menu.add(0, 2, 1, "Book Details")
        popup.menu.add(0, 3, 2, "Delete from Library")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val intent = Intent(this, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                    }
                    startActivity(intent)
                    true
                }
                2 -> {
                    showBookDetails(book)
                    true
                }
                3 -> {
                    confirmDeleteBook(book)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showBookDetails(book: Book) {
        val sizeKb = book.fileSize / 1024
        val sizeStr = if (sizeKb > 1024) "${String.format("%.1f", sizeKb / 1024f)} MB" else "$sizeKb KB"

        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(
                "Author: ${book.author}\n" +
                "Format: ${book.format.uppercase()}\n" +
                "File Size: $sizeStr\n" +
                "Progress: ${book.progress.toInt()}% completed\n" +
                (book.chapterTitle?.let { "Current: $it" } ?: "")
            )
            .setPositiveButton("Read") { _, _ ->
                val intent = Intent(this, ReaderActivity::class.java).apply {
                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                }
                startActivity(intent)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDeleteBook(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("Remove Book")
            .setMessage("Are you sure you want to remove '${book.title}' from your library?")
            .setPositiveButton("Remove") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    repository.deleteBook(book.id)
                    withContext(Dispatchers.Main) {
                        loadBooks()
                        Toast.makeText(this@MainActivity, "Book removed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
