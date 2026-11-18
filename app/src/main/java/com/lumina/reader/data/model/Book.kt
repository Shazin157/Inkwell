package com.lumina.reader.data.model

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String, // "epub", "pdf", "txt", "md"
    val filePath: String,
    val fileSize: Long,
    val coverColor: String, // e.g. "#1E3A8A", "#701A75", "#14532D"
    val coverImagePath: String? = null,
    var progress: Float = 0f, // 0..100
    var currentLocation: String? = null,
    var chapterTitle: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    var lastReadAt: Long = System.currentTimeMillis()
)
