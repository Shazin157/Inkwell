package com.lumina.reader.ui.adapter

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lumina.reader.R
import com.lumina.reader.data.model.Book
import com.lumina.reader.databinding.ItemBookBinding
import java.io.File

class BookAdapter(
    private var books: List<Book>,
    private val onBookClick: (Book) -> Unit,
    private val onBookMenuClick: (Book, View) -> Unit
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

    fun updateBooks(newBooks: List<Book>) {
        this.books = newBooks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val binding = ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BookViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount(): Int = books.size

    inner class BookViewHolder(private val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(book: Book) {
            binding.tvBookTitle.text = book.title
            binding.tvBookAuthor.text = book.author
            binding.tvFormatBadge.text = book.format.uppercase()

            // Format badge colors
            when (book.format) {
                "epub" -> {
                    binding.tvFormatBadge.setTextColor(Color.parseColor("#FBBF24"))
                }
                "pdf" -> {
                    binding.tvFormatBadge.setTextColor(Color.parseColor("#FB7185"))
                }
                else -> {
                    binding.tvFormatBadge.setTextColor(Color.parseColor("#38BDF8"))
                }
            }

            // Progress
            val progressInt = book.progress.toInt()
            binding.pbReadingCover.progress = progressInt
            binding.tvProgressPercent.text = if (progressInt > 0) "$progressInt% read" else "Unread"

            // Cover Image vs Typographic Cover
            val coverFile = book.coverImagePath?.let { File(it) }
            if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                try {
                    val bitmap = BitmapFactory.decodeFile(coverFile.absolutePath)
                    if (bitmap != null) {
                        binding.ivCoverImage.setImageBitmap(bitmap)
                        binding.ivCoverImage.visibility = View.VISIBLE
                        binding.layoutTypoCover.visibility = View.GONE
                    } else {
                        showTypoCover(book)
                    }
                } catch (e: Exception) {
                    showTypoCover(book)
                }
            } else {
                showTypoCover(book)
            }

            binding.root.setOnClickListener {
                onBookClick(book)
            }

            binding.ivBookMenu.setOnClickListener { view ->
                onBookMenuClick(book, view)
            }
        }

        private fun showTypoCover(book: Book) {
            binding.ivCoverImage.visibility = View.GONE
            binding.layoutTypoCover.visibility = View.VISIBLE

            binding.tvTypoTitle.text = book.title
            binding.tvTypoAuthor.text = book.author

            // Dynamic gradient based on book color
            try {
                val baseColor = Color.parseColor(book.coverColor)
                val darkerColor = Color.argb(
                    255,
                    (Color.red(baseColor) * 0.5f).toInt(),
                    (Color.green(baseColor) * 0.5f).toInt(),
                    (Color.blue(baseColor) * 0.5f).toInt()
                )
                val gradient = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(baseColor, darkerColor)
                )
                binding.layoutCover.background = gradient
            } catch (e: Exception) {
                binding.layoutCover.setBackgroundColor(Color.parseColor("#171A21"))
            }
        }
    }
}
