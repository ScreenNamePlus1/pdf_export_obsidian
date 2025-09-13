package com.dndpdf.sharehandler

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.itextpdf.html2pdf.HtmlConverter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val debugLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log("=== D&D PDF HANDLER DEBUG ===")
        log("App started at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        log("Build version: ${Build.VERSION.SDK_INT} (API ${Build.VERSION.CODENAME})")
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        analyzeIntent()
        showFileLocations()
        handleIntent()
        saveFile("debug_log.txt", debugLog.toString())
        finish()
    }

    private fun log(message: String) {
        debugLog.appendLine(message)
        android.util.Log.d("DnDDebug", message)
    }

    private fun analyzeIntent() {
        log("\n=== INTENT ANALYSIS ===")
        log("Action: ${intent?.action ?: "null"}")
        log("Type: ${intent?.type ?: "null"}")
        log("Data: ${intent?.data ?: "null"}")
        log("DataString: ${intent?.dataString ?: "null"}")
        log("Categories: ${intent?.categories?.joinToString() ?: "null"}")
        log("Scheme: ${intent?.data?.scheme ?: "null"}")
        log("Host: ${intent?.data?.host ?: "null"}")
        log("Path: ${intent?.data?.path ?: "null"}")
        log("Query: ${intent?.data?.query ?: "null"}")
        log("Fragment: ${intent?.data?.fragment ?: "null"}")

        log("\nExtras:")
        intent?.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                log("  $key: ${bundle.get(key)}")
            }
        } ?: log("  No extras")
    }

    private fun showFileLocations() {
        log("\n=== FILE LOCATIONS ===")
        log("Internal files dir: ${filesDir.absolutePath}")
        log("Internal exists: ${filesDir.exists()}")
        log("Internal writable: ${filesDir.canWrite()}")

        val externalFiles = getExternalFilesDir(null)
        if (externalFiles != null) {
            log("External files dir: ${externalFiles.absolutePath}")
            log("External exists: ${externalFiles.exists()}")
            log("External writable: ${externalFiles.canWrite()}")
        } else {
            log("External storage not available")
        }

        val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir != null) {
            log("Downloads dir: ${downloadsDir.absolutePath}")
            log("Downloads exists: ${downloadsDir.exists()}")
            log("Downloads writable: ${downloadsDir.canWrite()}")
        } else {
            log("Downloads directory not available")
        }
    }

    private fun handleIntent() {
        log("\n=== HANDLING INTENT ===")
        log("Intent received: ${intent?.toString() ?: "null"}")

        when (intent?.action) {
            Intent.ACTION_SEND -> handleTextShare()
            Intent.ACTION_VIEW -> handleObsidianUrl()
            Intent.ACTION_MAIN -> {
                log("App launched directly (not shared)")
                createTestFiles()
            }
            else -> {
                log("Unexpected action: ${intent?.action ?: "null"}")
                saveFile("fallback_debug.txt", "Unexpected intent action: ${intent?.action ?: "null"}\nDebug log:\n$debugLog")
            }
        }
    }

    private fun handleTextShare() {
        log("Entering handleTextShare")
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        val sharedStreamUri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        if (sharedText != null) {
            log("Got shared text, length: ${sharedText.length}")
            log("Shared text preview: ${sharedText.take(200)}")
            val html = convertMarkdownToHtml(sharedText)
            saveFile("shared_content.html", html)
            saveFile("shared_content.txt", sharedText)
            saveFileAsPdf("shared_content.pdf", html)
            Toast.makeText(this, "Processed shared text to HTML and PDF", Toast.LENGTH_LONG).show()
        } else if (sharedStreamUri != null) {
            log("Got shared file URI: $sharedStreamUri")
            val fileContent = readFileContent(sharedStreamUri)
            if (fileContent != null) {
                log("Read file content, length: ${fileContent.length}")
                log("File content preview: ${fileContent.take(200)}")
                val fileName = sharedStreamUri.lastPathSegment?.replace("%20", " ") ?: "shared_file"
                val html = convertMarkdownToHtml(fileContent)
                saveFile("${fileName}.html", html)
                saveFile("${fileName}.txt", fileContent)
                saveFileAsPdf("${fileName}.pdf", html)
                Toast.makeText(this, "Processed shared file to HTML and PDF", Toast.LENGTH_LONG).show()
            } else {
                log("Failed to read file content from URI: $sharedStreamUri")
                saveFile("failed_action_send.txt", "Failed to read file content from URI: $sharedStreamUri\nDebug log:\n$debugLog")
                Toast.makeText(this, "Failed to read shared file", Toast.LENGTH_LONG).show()
            }
        } else {
            log("No text content or file stream in ACTION_SEND")
            saveFile("failed_action_send.txt", "No text content or file stream received in ACTION_SEND\nDebug log:\n$debugLog")
            Toast.makeText(this, "No shareable content received", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleObsidianUrl() {
        log("Entering handleObsidianUrl")
        val data = intent?.data
        if (data != null) {
            log("Obsidian URL received: $data")
            log("Scheme: ${data.scheme}")
            log("Host: ${data.host}")
            log("Path: ${data.path}")
            log("Query: ${data.query}")
            log("Fragment: ${data.fragment}")
            val fileName = extractFileName(data.toString()) ?: "unknown_note"
            val noteContent = """
# Obsidian Note: $fileName
This is a placeholder for the Obsidian note content.
URL: $data
Extracted filename: $fileName
*Note*: Direct access to Obsidian note content is not supported.
Please share the note text via ACTION_SEND if needed.
*Processed at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}*
            """.trimIndent()
            val html = convertMarkdownToHtml(noteContent)
            saveFile("obsidian_${fileName}.html", html)
            saveFile("obsidian_${fileName}.txt", noteContent)
            saveFileAsPdf("obsidian_${fileName}.pdf", html)
            val urlInfo = """
Obsidian URL Debug Info
======================
Full URL: $data
Scheme: ${data.scheme}
Host: ${data.host}
Path: ${data.path}
Query: ${data.query}
Fragment: ${data.fragment}
Extracted filename: $fileName
Debug log:
$debugLog
            """.trimIndent()
            saveFile("obsidian_url_info.txt", urlInfo)
            Toast.makeText(this, "Processed Obsidian URL to HTML and PDF", Toast.LENGTH_LONG).show()
        } else {
            log("No URL data in ACTION_VIEW")
            saveFile("failed_obsidian_url.txt", "No URL data received in ACTION_VIEW\nDebug log:\n$debugLog")
            Toast.makeText(this, "No Obsidian URL data received", Toast.LENGTH_LONG).show()
        }
    }

    private fun createTestFiles() {
        log("Creating test files...")
        val testMarkdown = """
# Test D&D Adventure
This is a **test file** created by the D&D PDF Handler app.
## Scene 1: The Tavern
The party enters *The Prancing Pony*, a cozy tavern with:
- Warm firelight
- The smell of roasted meat
- Suspicious looking patrons
> A hooded figure in the corner beckons you over...
### Combat Encounter
**Goblin Scout**
- AC: 15
- HP: 7
- Speed: 30 ft
**Actions:**
- Scimitar: +4 to hit, 1d6+2 slashing damage
*File created at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}*
        """.trimIndent()
        val html = convertMarkdownToHtml(testMarkdown)
        saveFile("test_adventure.md", testMarkdown)
        saveFile("test_adventure.html", html)
        saveFileAsPdf("test_adventure.pdf", html)
        saveFile("debug_log.txt", debugLog.toString())
        log("Test files created!")
        Toast.makeText(this, "Test files created - check Downloads", Toast.LENGTH_LONG).show()
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        var html = markdown
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        html = html.replace(Regex("^> (.+)$", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
        html = html.replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        html = html.replace("\n\n", "</p><p>")
        html = html.replace("\n", "<br>")
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>D&D Content</title>
    <style>
        body { font-family: serif; line-height: 1.6; margin: 40px; }
        h1, h2, h3 { color: #8B0000; }
        blockquote { border-left: 4px solid #8B0000; padding-left: 20px; margin: 20px 0; font-style: italic; }
        li { margin: 5px 0; }
    </style>
</head>
<body>
    <p>$html</p>
</body>
</html>
        """.trimIndent()
    }

    private fun extractFileName(obsidianUri: String): String? {
        log("Extracting filename from URI: $obsidianUri")
        val patterns = listOf(
            "file=([^&]+)",
            "vault=([^&]+).*?file=([^&]+)",
            "/([^/]+)$"
        )
        for (pattern in patterns) {
            val regex = pattern.toRegex()
            val matchResult = regex.find(obsidianUri)
            if (matchResult != null) {
                val fileName = if (matchResult.groupValues.size > 2) matchResult.groupValues[2] else matchResult.groupValues[1]
                log("Extracted filename: $fileName")
                return fileName.replace("%20", " ").replace("%2F", "/")
            }
        }
        log("No filename extracted from URI")
        return null
    }

    private fun saveFile(fileName: String, content: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fullFileName = "DnD_${fileName.substringBeforeLast(".")}_${timestamp}.${fileName.substringAfterLast(".")}"
        try {
            log("Attempting to save file: $fullFileName")
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fullFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fullFileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                    outputStream.flush()
                }
                log("✓ Saved via MediaStore to Downloads: $fullFileName (URI: $uri)")
                return true
            } else {
                log("✗ MediaStore URI is null for file: $fullFileName")
                return false
            }
        } catch (e: Exception) {
            log("✗ Failed to save file: $fullFileName, Error: ${e.message}")
            Toast.makeText(this, "Failed to save: $fullFileName (${e.message})", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun saveFileAsPdf(fileName: String, htmlContent: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fullFileName = "DnD_${fileName.substringBeforeLast(".")}_${timestamp}.pdf"
        try {
            log("Attempting to save PDF: $fullFileName")
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fullFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    HtmlConverter.convertToPdf(htmlContent, outputStream)
                }
                log("✓ Saved PDF via MediaStore to Downloads: $fullFileName (URI: $uri)")
                return true
            } else {
                log("✗ MediaStore URI is null for PDF: $fullFileName")
                return false
            }
        } catch (e: Exception) {
            log("✗ Failed to save PDF: $fullFileName, Error: ${e.message}")
            Toast.makeText(this, "Failed to save PDF: $fullFileName (${e.message})", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast(".").lowercase()) {
            "html" -> "text/html"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "pdf" -> "application/pdf"
            else -> "text/plain"
        }
    }

    private fun readFileContent(uri: Uri): String? {
        log("Attempting to read content from URI: $uri")
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            log("✗ Failed to read file content: ${e.message}")
            null
        }
    }
}