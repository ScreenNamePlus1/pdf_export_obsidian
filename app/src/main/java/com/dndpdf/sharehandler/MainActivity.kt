package com.dndpdf.sharehandler

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var debugText: TextView
    private val debugLog = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a simple text view to show debug info
        debugText = TextView(this).apply {
            textSize = 12f
            setPadding(20, 20, 20, 20)
        }

        val scrollView = ScrollView(this).apply {
            addView(debugText)
        }
        setContentView(scrollView)

        log("=== D&D PDF HANDLER DEBUG ===")
        log("App started at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")

        Toast.makeText(this, "Debug version started!", Toast.LENGTH_LONG).show()

        // Check permissions for older APIs
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }

        // Analyze the intent
        analyzeIntent()

        // Show file locations
        showFileLocations()

        // Handle the intent
        handleIntent()

        // Auto-close after 30 seconds so you can read the debug info
        debugText.postDelayed({
            finish()
        }, 30000)
    }

    private fun log(message: String) {
        debugLog.appendLine(message)
        debugText.text = debugLog.toString()
        debugText.invalidate() // Force UI refresh
        android.util.Log.d("DnDDebug", message)
    }

    private fun analyzeIntent() {
        log("\n=== INTENT ANALYSIS ===")
        log("Action: ${intent?.action ?: "null"}")
        log("Type: ${intent?.type ?: "null"}")
        log("Data: ${intent?.data ?: "null"}")
        log("DataString: ${intent?.dataString ?: "null"}")
        log("Categories: ${intent?.categories ?: "null"}")

        log("\nExtras:")
        intent?.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                log("  $key: ${bundle.get(key)}")
            }
        } ?: log("  No extras")

        // Special check for text content
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText != null) {
            log("\nShared text preview:")
            log("Length: ${sharedText.length}")
            log("First 200 chars: ${sharedText.take(200)}")
        }
    }

    private fun showFileLocations() {
        log("\n=== FILE LOCATIONS ===")

        // Internal storage (always available)
        log("Internal files dir: ${filesDir.absolutePath}")
        log("Internal exists: ${filesDir.exists()}")
        log("Internal writable: ${filesDir.canWrite()}")

        // External storage
        val externalFiles = getExternalFilesDir(null)
        if (externalFiles != null) {
            log("External files dir: ${externalFiles.absolutePath}")
            log("External exists: ${externalFiles.exists()}")
            log("External writable: ${externalFiles.canWrite()}")
        } else {
            log("External storage not available")
        }

        // Downloads folder
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

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                log("Processing ACTION_SEND")
                handleTextShare()
            }
            Intent.ACTION_VIEW -> {
                log("Processing ACTION_VIEW")
                handleObsidianUrl()
            }
            Intent.ACTION_MAIN -> {
                log("App launched directly (not shared)")
                createTestFiles()
            }
            else -> {
                log("Unexpected action: ${intent?.action ?: "null"}")
                createTestFiles()
            }
        }
    }

    private fun handleTextShare() {
        val sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT)

        if (sharedText != null) {
            log("Got shared text, length: ${sharedText.length}")

            // Process as markdown and save both HTML and test file
            val html = convertMarkdownToHtml(sharedText)
            saveFile("shared_content.html", html)
            saveFile("shared_content.txt", sharedText)

            Toast.makeText(this, "Processed shared text", Toast.LENGTH_LONG).show()
        } else {
            log("No text content in ACTION_SEND")
        }
    }

    private fun handleObsidianUrl() {
        val data = intent?.data

        if (data != null) {
            log("Obsidian URL received: $data")

            // Save URL info
            val urlInfo = """
Obsidian URL Debug Info
======================
Full URL: $data
Scheme: ${data.scheme}
Host: ${data.host}
Path: ${data.path}
Query: ${data.query}
Fragment: ${data.fragment}
Extracted filename attempt: ${extractFileName(data.toString())}
            """.trimIndent()

            saveFile("obsidian_url_info.txt", urlInfo)

            Toast.makeText(this, "Received Obsidian URL - check files", Toast.LENGTH_LONG).show()
        } else {
            log("No URL data in ACTION_VIEW")
        }
    }

    private fun createTestFiles() {
        log("Creating test files...")

        // Test markdown content
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

---

*File created at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}*
        """.trimIndent()

        // Save as both markdown and HTML
        val html = convertMarkdownToHtml(testMarkdown)

        saveFile("test_adventure.md", testMarkdown)
        saveFile("test_adventure.html", html)
        saveFile("debug_log.txt", debugLog.toString())

        log("Test files created!")
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        var html = markdown

        // Basic markdown conversion
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        html = html.replace(Regex("^> (.+)$", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
        html = html.replace(Regex("^- (.+)$", RegexOption.MULTILINE), "<li>$1</li>")
        html = html.replace("\n\n", "</p><p>")
        html = html.replace("\n", "<br>")

        // Wrap in basic HTML structure
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>D&D Content</title>
    <style>
        body { font-family: serif; line-height: 1.6; margin: 40px; }
        h1, h2, h3 { color: #8B0000; }
        blockquote { 
            border-left: 4px solid #8B0000; 
            padding-left: 20px; 
            margin: 20px 0;
            font-style: italic;
        }
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
        val patterns = listOf(
            "file=([^&]+)",
            "vault=([^&]+).*?file=([^&]+)",
            "/([^/]+)$"
        )

        for (pattern in patterns) {
            val regex = pattern.toRegex()
            val matchResult = regex.find(obsidianUri)
            if (matchResult != null) {
                val fileName = if (matchResult.groupValues.size > 2) {
                    matchResult.groupValues[2]
                } else {
                    matchResult.groupValues[1]
                }
                return fileName.replace("%20", " ").replace("%2F", "/")
            }
        }
        return null
    }

    private fun saveFile(fileName: String, content: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fullFileName = "DnD_${fileName.substringBeforeLast(".")}_${timestamp}.${fileName.substringAfterLast(".")}"

        try {
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
                log("✓ Saved via MediaStore to Downloads: $fullFileName")
                Toast.makeText(this, "File saved: $fullFileName", Toast.LENGTH_LONG).show()
                return true
            } else {
                log("✗ MediaStore URI is null")
                return false
            }
        } catch (e: Exception) {
            log("✗ Failed to save file: $fullFileName, Error: ${e.message}")
            Toast.makeText(this, "Failed to save: $fullFileName", Toast.LENGTH_LONG).show()
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
}