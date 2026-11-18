package com.lumina.reader.data.storage

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lumina.reader.data.model.Book
import com.lumina.reader.data.model.Bookmark
import com.lumina.reader.data.model.DisplaySettings
import com.lumina.reader.data.parser.EpubExtractor
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BookRepository(private val context: Context) {

    private val gson = Gson()
    private val booksMetaFile = File(context.filesDir, "books_meta.json")
    private val bookmarksFile = File(context.filesDir, "bookmarks.json")
    private val prefs = context.getSharedPreferences("lumina_settings", Context.MODE_PRIVATE)

    private val coverPalettes = listOf(
        "#1E3A8A", // Deep Navy
        "#701A75", // Royal Plum
        "#14532D", // Forest Emerald
        "#7C2D12", // Terracotta
        "#1E2025", // Charcoal Slate
        "#854D0E"  // Vintage Amber
    )

    fun getAllBooks(): MutableList<Book> {
        if (!booksMetaFile.exists()) {
            return mutableListOf()
        }
        return try {
            val json = booksMetaFile.readText()
            val type = object : TypeToken<MutableList<Book>>() {}.type
            gson.fromJson<MutableList<Book>>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    private fun saveBooks(books: List<Book>) {
        try {
            val json = gson.toJson(books)
            booksMetaFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getBook(id: String): Book? {
        return getAllBooks().find { it.id == id }
    }

    fun updateBook(book: Book) {
        val books = getAllBooks()
        val index = books.indexOfFirst { it.id == book.id }
        if (index != -1) {
            books[index] = book
        } else {
            books.add(0, book)
        }
        saveBooks(books)
    }

    fun deleteBook(id: String) {
        val books = getAllBooks()
        val book = books.find { it.id == id }
        if (book != null) {
            try {
                File(book.filePath).delete()
                book.coverImagePath?.let { File(it).delete() }
                File(context.filesDir, "unpacked/$id").deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            books.removeAll { it.id == id }
            saveBooks(books)

            // Remove bookmarks
            val bookmarks = getAllBookmarks().filter { it.bookId != id }
            saveBookmarks(bookmarks)
        }
    }

    fun importBookFromUri(uri: Uri): Book? {
        return try {
            val contentResolver = context.contentResolver
            val originalName = queryFileName(uri) ?: "Imported_Book"
            val ext = originalName.substringAfterLast(".", "txt").lowercase()

            val format = when (ext) {
                "epub" -> "epub"
                "pdf" -> "pdf"
                "md" -> "md"
                else -> "txt"
            }

            val id = "book_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6)
            val booksDir = File(context.filesDir, "books").apply { mkdirs() }
            val destFile = File(booksDir, "$id.$format")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            var title = originalName.substringBeforeLast(".").replace("[_-]".toRegex(), " ")
            var author = "Unknown Author"
            var coverImage: String? = null

            if (format == "epub") {
                val meta = EpubExtractor.extractMetadata(context, destFile, id)
                if (meta.title.isNotBlank()) title = meta.title
                if (meta.author.isNotBlank()) author = meta.author
                coverImage = meta.coverImagePath
            } else if (format == "txt" || format == "md") {
                try {
                    val firstLine = destFile.bufferedReader().readLine()
                    if (!firstLine.isNullOrBlank() && firstLine.length < 60) {
                        title = firstLine.replace("^[#*\\s]+".toRegex(), "").trim()
                    }
                } catch (e: Exception) {}
            }

            val coverColor = coverPalettes[Math.abs(title.hashCode()) % coverPalettes.size]

            val book = Book(
                id = id,
                title = title,
                author = author,
                format = format,
                filePath = destFile.absolutePath,
                fileSize = destFile.length(),
                coverColor = coverColor,
                coverImagePath = coverImage,
                progress = 0f,
                chapterTitle = if (format == "pdf") "Page 1" else "Beginning"
            )

            updateBook(book)
            book
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }

    fun seedStarterClassics() {
        val starters = listOf(
            Triple("Meditations", "Marcus Aurelius", MEDITATIONS_SAMPLE),
            Triple("The Art of War", "Sun Tzu", ART_OF_WAR_SAMPLE),
            Triple("A Scandal in Bohemia", "Arthur Conan Doyle", SCANDAL_SAMPLE)
        )

        val booksDir = File(context.filesDir, "books").apply { mkdirs() }

        starters.forEachIndexed { index, (title, author, content) ->
            val id = "sample_${index + 1}"
            val file = File(booksDir, "$id.txt")
            if (!file.exists()) {
                file.writeText(content)
            }
            val color = coverPalettes[index % coverPalettes.size]
            val book = Book(
                id = id,
                title = title,
                author = author,
                format = "txt",
                filePath = file.absolutePath,
                fileSize = file.length(),
                coverColor = color,
                progress = if (index == 0) 14f else 0f,
                chapterTitle = if (index == 0) "Book II: On the Granua" else "Chapter I"
            )
            updateBook(book)
        }
    }

    // Display Settings
    fun loadDisplaySettings(): DisplaySettings {
        val json = prefs.getString("settings", null)
        return if (json != null) {
            try {
                gson.fromJson(json, DisplaySettings::class.java) ?: DisplaySettings()
            } catch (e: Exception) {
                DisplaySettings()
            }
        } else {
            DisplaySettings()
        }
    }

    fun saveDisplaySettings(settings: DisplaySettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
    }

    // Bookmarks
    fun getAllBookmarks(): MutableList<Bookmark> {
        if (!bookmarksFile.exists()) return mutableListOf()
        return try {
            val json = bookmarksFile.readText()
            val type = object : TypeToken<MutableList<Bookmark>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getBookmarksForBook(bookId: String): List<Bookmark> {
        return getAllBookmarks().filter { it.bookId == bookId }
    }

    fun addBookmark(bookmark: Bookmark) {
        val list = getAllBookmarks()
        list.add(0, bookmark)
        saveBookmarks(list)
    }

    fun deleteBookmark(id: String) {
        val list = getAllBookmarks()
        list.removeAll { it.id == id }
        saveBookmarks(list)
    }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        try {
            bookmarksFile.writeText(gson.toJson(bookmarks))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val MEDITATIONS_SAMPLE = """MEDITATIONS
by Marcus Aurelius

BOOK ONE
1. From my grandfather Verus I learned good morals and the government of my temper.
2. From the reputation and remembrance of my father, modesty and a manly character.
3. From my mother, piety and beneficence, and abstinence, not only from evil deeds, but even from evil thoughts.

BOOK TWO
1. When you wake up in the morning, tell yourself: The people I deal with today will be meddling, ungrateful, arrogant, dishonest, jealous, and surly. They are like this because they cannot tell good from evil. But I have seen the beauty of good, and the ugliness of evil, and have recognized that the wrongdoer has a nature related to my own.
2. Whatever this is that I am, it is a little flesh and breath, and the ruling part. Despise the flesh: blood and bones and a network, a jumble of nerves, veins, and arteries.
"""

        const val ART_OF_WAR_SAMPLE = """THE ART OF WAR
by Sun Tzu

CHAPTER I: LAYING PLANS
1. Sun Tzu said: The art of war is of vital importance to the State.
2. It is a matter of life and death, a road either to safety or to ruin. Hence it is a subject of inquiry which can on no account be neglected.
3. The art of war, then, is governed by five constant factors: (1) The Moral Law; (2) Heaven; (3) Earth; (4) The Commander; (5) Method and discipline.

CHAPTER II: WAGING WAR
1. Sun Tzu said: In the operations of war, where there are in the field a thousand swift chariots, as many heavy chariots, and a hundred thousand mail-clad soldiers, the expenditure will reach the total of a thousand ounces of silver per day.
"""

        const val SCANDAL_SAMPLE = """A SCANDAL IN BOHEMIA
by Sir Arthur Conan Doyle

I.
To Sherlock Holmes she is always THE woman. I have seldom heard him mention her under any other name. In his eyes she eclipses and predominates the whole of her sex. It was not that he felt any emotion akin to love for Irene Adler. All emotions, and that one particularly, were abhorrent to his cold, precise but admirably balanced mind. He was, I take it, the most perfect reasoning and observing machine that the world has seen.
"""
    }
}
