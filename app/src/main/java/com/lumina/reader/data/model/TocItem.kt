package com.lumina.reader.data.model

data class TocItem(
    val id: String,
    val label: String,
    val location: String // chapter index or page number
)
