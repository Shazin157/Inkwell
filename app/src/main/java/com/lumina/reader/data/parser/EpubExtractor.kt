package com.lumina.reader.data.parser

import android.content.Context
import com.lumina.reader.data.model.TocItem
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class EpubMetadata(
    val title: String,
    val author: String,
    val coverImagePath: String?,
    val spineFiles: List<String>,
    val opfDir: String,
    val unpackedDir: String,
    val toc: List<TocItem>
)

object EpubExtractor {

    fun unpackEpubIfNeeded(context: Context, epubFile: File, bookId: String): File {
        val destDir = File(context.filesDir, "unpacked/$bookId")
        if (destDir.exists() && (destDir.list()?.isNotEmpty() == true)) {
            return destDir
        }
        destDir.mkdirs()

        try {
            val zip = ZipFile(epubFile)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            zip.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return destDir
    }

    fun extractMetadata(context: Context, epubFile: File, bookId: String): EpubMetadata {
        val unpackedDirFile = unpackEpubIfNeeded(context, epubFile, bookId)
        val unpackedDir = unpackedDirFile.absolutePath

        var title = epubFile.nameWithoutExtension.replace("[_-]".toRegex(), " ")
        var author = "Unknown Author"
        var coverImagePath: String? = null
        val spineFiles = mutableListOf<String>()
        val tocItems = mutableListOf<TocItem>()
        var opfDir = ""

        try {
            // 1. Find OPF path from META-INF/container.xml
            var opfPath = "OEBPS/content.opf"
            val containerFile = File(unpackedDirFile, "META-INF/container.xml")
            if (containerFile.exists()) {
                val xml = containerFile.readText()
                val match = Regex("""full-path=["']([^"']+)["']""").find(xml)
                if (match != null) {
                    opfPath = match.groupValues[1]
                }
            }

            opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            // 2. Read OPF file
            val opfFile = File(unpackedDirFile, opfPath)
            if (opfFile.exists()) {
                val opfContent = opfFile.readText()

                // Parse title
                val titleMatch = Regex("""<dc:title[^>]*>([^<]+)</dc:title>""", RegexOption.IGNORE_CASE).find(opfContent)
                if (titleMatch != null && titleMatch.groupValues[1].isNotBlank()) {
                    title = titleMatch.groupValues[1].trim()
                }

                // Parse author
                val authorMatch = Regex("""<dc:creator[^>]*>([^<]+)</dc:creator>""", RegexOption.IGNORE_CASE).find(opfContent)
                if (authorMatch != null && authorMatch.groupValues[1].isNotBlank()) {
                    author = authorMatch.groupValues[1].trim()
                }

                // Parse Manifest items
                val manifestMap = mutableMapOf<String, String>() // id -> href
                var coverHref: String? = null

                val itemRegex = Regex("""<item\s+[^>]*>""", RegexOption.IGNORE_CASE)
                for (itemMatch in itemRegex.findAll(opfContent)) {
                    val tag = itemMatch.value
                    val id = Regex("""id=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                    val href = Regex("""href=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)
                    val properties = Regex("""properties=["']([^"']+)["']""").find(tag)?.groupValues?.get(1)

                    if (id != null && href != null) {
                        manifestMap[id] = href
                        if (properties?.contains("cover-image") == true || id.contains("cover", ignoreCase = true)) {
                            if (href.endsWith(".jpg", ignoreCase = true) || href.endsWith(".jpeg", ignoreCase = true) || href.endsWith(".png", ignoreCase = true)) {
                                coverHref = href
                            }
                        }
                    }
                }

                // Parse spine items in reading order
                val itemrefRegex = Regex("""<itemref\s+[^>]*idref=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
                for (refMatch in itemrefRegex.findAll(opfContent)) {
                    val idref = refMatch.groupValues[1]
                    val href = manifestMap[idref]
                    if (href != null) {
                        spineFiles.add(opfDir + href)
                    }
                }

                // Extract cover image if found
                if (coverHref != null) {
                    val fullCoverPath = opfDir + coverHref
                    val coverFileOnDisk = File(unpackedDirFile, fullCoverPath)
                    if (coverFileOnDisk.exists()) {
                        coverImagePath = coverFileOnDisk.absolutePath
                    } else {
                        val directCover = File(unpackedDirFile, coverHref)
                        if (directCover.exists()) {
                            coverImagePath = directCover.absolutePath
                        }
                    }
                }
            }

            // Fallback: if no spine, look for html/xhtml files
            if (spineFiles.isEmpty()) {
                unpackedDirFile.walkTopDown().forEach { file ->
                    val name = file.name
                    if ((name.endsWith(".html", ignoreCase = true) || name.endsWith(".xhtml", ignoreCase = true)) && !name.contains("toc", ignoreCase = true)) {
                        val relPath = file.relativeTo(unpackedDirFile).path.replace("\\", "/")
                        spineFiles.add(relPath)
                    }
                }
                spineFiles.sort()
            }

            // Build simple TOC from spine
            spineFiles.forEachIndexed { index, path ->
                val simpleName = path.substringAfterLast("/").substringBeforeLast(".")
                    .replace("[_-]".toRegex(), " ")
                    .replace("(?i)chapter".toRegex(), "Chapter ")
                tocItems.add(TocItem("chap_$index", simpleName.ifBlank { "Section ${index + 1}" }, index.toString()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return EpubMetadata(title, author, coverImagePath, spineFiles, opfDir, unpackedDir, tocItems)
    }

    fun readChapterHtml(unpackedDir: String, entryPath: String): String {
        return try {
            val file = File(unpackedDir, entryPath)
            if (file.exists()) {
                file.readText()
            } else {
                "<html><body><p>Chapter not found: $entryPath</p></body></html>"
            }
        } catch (e: Exception) {
            "<html><body><p>Error reading chapter: ${e.message}</p></body></html>"
        }
    }
}
