package com.lumina.reader.data.model

data class Bookmark(
    val id: String,
    val bookId: String,
    val location: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)
