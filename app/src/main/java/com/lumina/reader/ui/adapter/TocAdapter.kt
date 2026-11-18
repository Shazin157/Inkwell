package com.lumina.reader.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lumina.reader.data.model.Bookmark
import com.lumina.reader.data.model.TocItem
import com.lumina.reader.databinding.ItemTocBinding

class TocAdapter(
    private var chapters: List<TocItem> = emptyList(),
    private var bookmarks: List<Bookmark> = emptyList(),
    private var isBookmarksMode: Boolean = false,
    private val onChapterClick: (TocItem) -> Unit,
    private val onBookmarkClick: (Bookmark) -> Unit,
    private val onDeleteBookmarkClick: (Bookmark) -> Unit
) : RecyclerView.Adapter<TocAdapter.TocViewHolder>() {

    fun setChapters(items: List<TocItem>) {
        this.chapters = items
        this.isBookmarksMode = false
        notifyDataSetChanged()
    }

    fun setBookmarks(items: List<Bookmark>) {
        this.bookmarks = items
        this.isBookmarksMode = true
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TocViewHolder {
        val binding = ItemTocBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TocViewHolder, position: Int) {
        if (isBookmarksMode) {
            holder.bindBookmark(bookmarks[position])
        } else {
            holder.bindChapter(chapters[position])
        }
    }

    override fun getItemCount(): Int = if (isBookmarksMode) bookmarks.size else chapters.size

    inner class TocViewHolder(private val binding: ItemTocBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bindChapter(item: TocItem) {
            binding.tvTocItemLabel.text = item.label
            binding.ivTocArrow.visibility = View.VISIBLE
            binding.btnDeleteBookmark.visibility = View.GONE

            binding.root.setOnClickListener {
                onChapterClick(item)
            }
        }

        fun bindBookmark(item: Bookmark) {
            binding.tvTocItemLabel.text = item.title
            binding.ivTocArrow.visibility = View.GONE
            binding.btnDeleteBookmark.visibility = View.VISIBLE

            binding.root.setOnClickListener {
                onBookmarkClick(item)
            }

            binding.btnDeleteBookmark.setOnClickListener {
                onDeleteBookmarkClick(item)
            }
        }
    }
}
