// MainActivity.kt
package com.dndpdf.sharehandler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

// iText PDF imports
import com.itextpdf.html2pdf.HtmlConverter
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DnDPDFHandler"
    }

    private lateinit var webView: WebView
    private var sourceDocumentFile: DocumentFile? = null
    private var rootDocument: DocumentFile? = null
    
    // SAF Launcher with proper URI persistence
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                Log.d(TAG, "SAF URI received: $uri")
                // Take persistable permission
                try {
                    contentResolver.takePersistableUriPermission(uri, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    Log.d(TAG, "Persistable permission taken successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to take persistable permission", e)
                }
                
                readObsidianFileFromUri(uri.toString())
            } else {
                Log.w(TAG, "No URI received from SAF picker")
                Toast.makeText(this, "Obsidian vault not selected.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        // Set up WebView for HTML rendering
        webView = WebView(this)
        setContentView(webView)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Handle shared content
        handleIncomingContent()
    }

    private fun handleIncomingContent() {
        Log.d(TAG, "handleIncomingContent called")
        Log.d(TAG, "Intent action: ${intent?.action}")
        Log.d(TAG, "Intent type: ${intent?.type}")
        Log.d(TAG, "Intent data: ${intent?.data}")
        Log.d(TAG, "Intent dataString: ${intent?.dataString}")
        
        // Show debug info to user
        Toast.makeText(this, "Intent action: ${intent?.action}, type: ${intent?.type}", Toast.LENGTH_LONG).show()
        
        when {
            intent?.action == Intent.ACTION_SEND -> {
                Log.d(TAG, "Handling ACTION_SEND")
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    Log.d(TAG, "Shared text length: ${sharedText?.length}")
                    if (sharedText != null) {
                        Toast.makeText(this, "Received text content (${sharedText.length} chars)", Toast.LENGTH_SHORT).show()
                        processMarkdownContent(sharedText, null)
                    } else {
                        Log.w(TAG, "No text content in ACTION_SEND")
                        Toast.makeText(this, "No content received", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    Log.w(TAG, "Unsupported content type: ${intent.type}")
                    Toast.makeText(this, "Unsupported content type: ${intent.type}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            intent?.action == Intent.ACTION_VIEW -> {
                Log.d(TAG, "Handling ACTION_VIEW")
                val data = intent.data
                if (data != null && data.scheme == "obsidian") {
                    Log.d(TAG, "Valid Obsidian URL received: $data")
                    Toast.makeText(this, "Received Obsidian URL: $data", Toast.LENGTH_LONG).show()
                    // Launch the SAF folder picker
                    openDocumentTreeLauncher.launch(null)
                } else {
                    Log.w(TAG, "Invalid or no URI data: $data")
                    Toast.makeText(this, "No valid content to process", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            else -> {
                Log.w(TAG, "Unhandled intent action: ${intent?.action}")
                Toast.makeText(this, "No valid content to process", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun readObsidianFileFromUri(treeUri: String) {
        Log.d(TAG, "readObsidianFileFromUri called with: $treeUri")
        
        val sharedUri = intent?.dataString
        Log.d(TAG, "SharedURI from intent: $sharedUri")
        
        if (sharedUri == null) {
            Log.e(TAG, "No URI data found in intent")
            Toast.makeText(this, "No URI data found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        // More robust filename extraction
        val fileName = extractFileName(sharedUri)
        Log.d(TAG, "Extracted filename: $fileName")

        if (fileName.isNullOrEmpty()) {
            Log.e(TAG, "Could not extract filename from URL: $sharedUri")
            Toast.makeText(this, "Could not extract filename from URL", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        val (content, fileDocument) = getFileContentAndDocument(treeUri, fileName)
        if (content != null && fileDocument != null) {
            Log.d(TAG, "File found successfully, content length: ${content.length}")
            Toast.makeText(this, "File found, processing content...", Toast.LENGTH_LONG).show()
            sourceDocumentFile = fileDocument
            rootDocument = DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
            processMarkdownContent(content, fileDocument)
        } else {
            Log.e(TAG, "File not found or unreadable: $fileName")
            Toast.makeText(this, "File not found: $fileName", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun extractFileName(obsidianUri: String): String? {
        Log.d(TAG, "Extracting filename from: $obsidianUri")
        
        // Try multiple patterns for filename extraction
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
                    matchResult.groupValues[2] // For vault + file pattern
                } else {
                    matchResult.groupValues[1]
                }
                val decoded = fileName.replace("%20", " ").replace("%2F", "/")
                Log.d(TAG, "Pattern '$pattern' matched: $decoded")
                return decoded
            }
        }
        
        Log.w(TAG, "No filename pattern matched")
        return null
    }

    private fun getFileContentAndDocument(treeUri: String, fileName: String): Pair<String?, DocumentFile?> {
        return try {
            Log.d(TAG, "Searching for file: $fileName in tree: $treeUri")
            val rootDoc = DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
            
            if (rootDoc == null) {
                Log.e(TAG, "Failed to create DocumentFile from tree URI")
                return Pair(null, null)
            }
            
            Log.d(TAG, "Root document name: ${rootDoc.name}")
            Log.d(TAG, "Root document can read: ${rootDoc.canRead()}")
            
            // Try different filename variations
            val fileVariations = listOf(
                "$fileName.md",
                fileName,
                fileName.substringAfterLast("/"), // Remove path if present
                "${fileName.substringAfterLast("/")}.md"
            )
            
            var fileDocument: DocumentFile? = null
            for (variation in fileVariations) {
                Log.d(TAG, "Trying filename variation: $variation")
                fileDocument = findFileRecursively(rootDoc, variation)
                if (fileDocument != null) {
                    Log.d(TAG, "Found file with variation: $variation")
                    break
                }
            }

            if (fileDocument?.exists() == true && fileDocument.isFile) {
                Log.d(TAG, "File exists and is readable")
                val inputStream = contentResolver.openInputStream(fileDocument.uri)
                val content = inputStream?.bufferedReader().use { it?.readText() }
                Log.d(TAG, "File content read, length: ${content?.length}")
                Pair(content, fileDocument)
            } else {
                Log.w(TAG, "File not found or not readable")
                Pair(null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: ${e.message}", e)
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
            Pair(null, null)
        }
    }

    private fun findFileRecursively(directory: DocumentFile, fileName: String): DocumentFile? {
        // First check direct children
        val directMatch = directory.findFile(fileName)
        if (directMatch != null) return directMatch
        
        // Then search subdirectories
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                val found = findFileRecursively(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun processMarkdownContent(markdown: String, sourceFile: DocumentFile?) {
        Log.d(TAG, "Processing markdown content, length: ${markdown.length}")
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Processing D&D content...", Toast.LENGTH_SHORT).show()

                val styledHtml = convertMarkdownToStyledHtml(markdown)
                Log.d(TAG, "HTML generated, length: ${styledHtml.length}")
                
                // Generate both HTML and PDF files
                runOnUiThread {
                    val htmlSuccess = saveHtmlFile(styledHtml, sourceFile)
                    val pdfSuccess = savePdfFile(styledHtml, sourceFile)
                    
                    if (htmlSuccess || pdfSuccess) {
                        // Load HTML in WebView for preview only if at least one save succeeded
                        webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
                        
                        // Auto-close after delay
                        webView.postDelayed({
                            finish()
                        }, 5000)
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to save files", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing content", e)
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun saveHtmlFile(htmlContent: String, sourceFile: DocumentFile?): Boolean {
        return try {
            Log.d(TAG, "Saving HTML file")
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = generateFileName(sourceFile, "html", timestamp)
            Log.d(TAG, "HTML filename: $fileName")

            val targetDirectory = getTargetDirectory(sourceFile)
            if (targetDirectory == null) {
                Log.e(TAG, "No target directory available for HTML")
                Toast.makeText(this, "No target directory available for HTML", Toast.LENGTH_LONG).show()
                return false
            }

            // Try to create the file
            val htmlFile = targetDirectory.createFile("text/html", fileName)
            
            if (htmlFile != null) {
                Log.d(TAG, "HTML file created: ${htmlFile.uri}")
                contentResolver.openOutputStream(htmlFile.uri)?.use { outputStream ->
                    outputStream.write(htmlContent.toByteArray())
                }
                Log.d(TAG, "HTML file written successfully")
                Toast.makeText(this, "HTML saved: $fileName", Toast.LENGTH_SHORT).show()
                true
            } else {
                Log.e(TAG, "Failed to create HTML file")
                Toast.makeText(this, "Failed to create HTML file", Toast.LENGTH_LONG).show()
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving HTML file", e)
            Toast.makeText(this, "Error saving HTML: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun savePdfFile(htmlContent: String, sourceFile: DocumentFile?): Boolean {
        return try {
            Log.d(TAG, "Generating PDF...")
            Toast.makeText(this, "Generating PDF...", Toast.LENGTH_SHORT).show()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = generateFileName(sourceFile, "pdf", timestamp)
            Log.d(TAG, "PDF filename: $fileName")

            // Convert HTML to PDF using iText
            val outputStream = ByteArrayOutputStream()
            HtmlConverter.convertToPdf(htmlContent, outputStream)
            val pdfBytes = outputStream.toByteArray()
            Log.d(TAG, "PDF generated, size: ${pdfBytes.size} bytes")

            val targetDirectory = getTargetDirectory(sourceFile)
            if (targetDirectory == null) {
                Log.e(TAG, "No target directory available for PDF")
                Toast.makeText(this, "No target directory available for PDF", Toast.LENGTH_LONG).show()
                return false
            }

            // Create the PDF file
            val pdfFile = targetDirectory.createFile("application/pdf", fileName)
            
            if (pdfFile != null) {
                Log.d(TAG, "PDF file created: ${pdfFile.uri}")
                contentResolver.openOutputStream(pdfFile.uri)?.use { fileOutputStream ->
                    fileOutputStream.write(pdfBytes)
                }
                Log.d(TAG, "PDF file written successfully")
                Toast.makeText(this, "PDF saved: $fileName", Toast.LENGTH_LONG).show()
                true
            } else {
                Log.e(TAG, "Failed to create PDF file")
                Toast.makeText(this, "Failed to create PDF file", Toast.LENGTH_LONG).show()
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating PDF", e)
            Toast.makeText(this, "Error creating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun generateFileName(sourceFile: DocumentFile?, extension: String, timestamp: String): String {
        return if (sourceFile != null) {
            val baseName = sourceFile.name?.substringBeforeLast(".") ?: "DnD_Adventure"
            "${baseName}_${timestamp}.${extension}"
        } else {
            "DnD_Adventure_${timestamp}.${extension}"
        }
    }

    private fun getTargetDirectory(sourceFile: DocumentFile?): DocumentFile? {
        return if (sourceFile != null && rootDocument != null) {
            // Try to find parent directory, fall back to root
            findParentDirectory(sourceFile) ?: rootDocument
        } else {
            rootDocument
        }
    }

    private fun findParentDirectory(file: DocumentFile): DocumentFile? {
        // For now, return root directory
        // Advanced implementation would parse URI structure to find actual parent
        return rootDocument
    }

    // Keep your existing convertMarkdownToStyledHtml, convertTables, and buildCompleteHtml methods unchanged
    private fun convertMarkdownToStyledHtml(markdown: String): String {
        var html = markdown

        // Convert headers
        html = html.replace(Regex("^# (.+)$", RegexOption.MULTILINE), "<h1>$1</h1>")
        html = html.replace(Regex("^## (.+)$", RegexOption.MULTILINE), "<h2>$1</h2>")
        html = html.replace(Regex("^### (.+)$", RegexOption.MULTILINE), "<h3>$1</h3>")
        html = html.replace(Regex("^#### (.+)$", RegexOption.MULTILINE), "<h4>$1</h4>")

        // Convert bold and italic
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        html = html.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")

        // Convert blockquotes
        html = html.replace(Regex("^> (.+)$", RegexOption.MULTILINE), "<blockquote><p>$1</p></blockquote>")

        // Convert tables
        html = convertTables(html)

        // Convert line breaks
        html = html.replace("\n\n", "</p><p>")
        html = html.replace("\n", "<br>")

        // Wrap in paragraphs if needed
        if (!html.contains("<h1>") && !html.contains("<div")) {
            html = "<p>$html</p>"
        }

        return buildCompleteHtml(html)
    }

    private fun convertTables(html: String): String {
        val tableRegex = Regex("\\|(.+)\\|\n\\|[-\\s|:]+\\|\n((?:\\|.+\\|\n?)+)", RegexOption.MULTILINE)

        return tableRegex.replace(html) { matchResult ->
            val headerRow = matchResult.groupValues[1]
            val dataRows = matchResult.groupValues[2]

            val headers = headerRow.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val rows = dataRows.split("\n").filter { it.isNotEmpty() }

            val tableHtml = StringBuilder("<table>")

            // Add header
            tableHtml.append("<tr>")
            headers.forEach { header ->
                tableHtml.append("<th>$header</th>")
            }
            tableHtml.append("</tr>")

            // Add data rows
            rows.forEach { row ->
                val cells = row.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                tableHtml.append("<tr>")
                cells.forEach { cell ->
                    tableHtml.append("<td>$cell</td>")
                }
                tableHtml.append("</tr>")
            }

            tableHtml.append("</table>")
            tableHtml.toString()
        }
    }

    private fun buildCompleteHtml(content: String): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>D&D Adventure</title>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Libre+Baskerville:ital,wght@0,400;0,700;1,400&display=swap');
        @import url('https://fonts.googleapis.com/css2?family=Cinzel:wght@400;500;600;700&display=swap');
        @import url('https://fonts.googleapis.com/css2?family=Cinzel+Decorative:wght@400;700;900&display=swap');
        @import url('https://fonts.googleapis.com/css2?family=Cormorant+Garamond:ital,wght@0,300;0,400;0,500;0,600;0,700;1,400;1,500;1,600&display=swap');
        
        :root {
            --phb-burgundy: #58180D;
            --phb-gold: #c9ad6a;
            --phb-cream: #fdf1dc;
            --phb-dark-cream: #f4e5d0;
            --phb-brown: #8B7562;
            --phb-dark-brown: #722F37;
            --phb-light-gold: #e8d5a3;
            --phb-shadow: rgba(0, 0, 0, 0.3);
            --phb-text-shadow: rgba(255, 255, 255, 0.8);
        }
        
        body {
            font-family: "Cormorant Garamond", "Libre Baskerville", serif;
            font-size: 15px;
            line-height: 1.65;
            color: #000;
            background-color: var(--phb-cream);
            background-image: 
                radial-gradient(circle at 15% 20%, rgba(139, 117, 94, 0.12) 0%, transparent 35%),
                radial-gradient(circle at 85% 10%, rgba(139, 117, 94, 0.08) 0%, transparent 40%),
                radial-gradient(circle at 25% 80%, rgba(160, 140, 100, 0.1) 0%, transparent 30%),
                radial-gradient(circle at 75% 75%, rgba(139, 117, 94, 0.06) 0%, transparent 45%),
                repeating-linear-gradient(0deg, transparent, transparent 1px, rgba(139, 117, 94, 0.015) 1px, rgba(139, 117, 94, 0.015) 2px),
                repeating-linear-gradient(90deg, transparent, transparent 1px, rgba(139, 117, 94, 0.015) 1px, rgba(139, 117, 94, 0.015) 2px);
            padding: 40px;
            margin: 0;
            -webkit-print-color-adjust: exact;
            print-color-adjust: exact;
        }
        
        h1, h2, h3, h4, h5, h6 {
            font-family: "Cinzel", serif;
            font-weight: bold;
            color: var(--phb-burgundy);
            text-shadow: 1px 1px 0px var(--phb-text-shadow);
            margin-top: 1.8em;
            margin-bottom: 0.6em;
        }
        
        h1 {
            font-family: "Cinzel Decorative", serif;
            font-size: 2.2em;
            text-align: center;
            background: linear-gradient(135deg, rgba(255, 255, 255, 0.6) 0%, rgba(255, 248, 230, 0.8) 50%, rgba(255, 255, 255, 0.4) 100%);
            padding: 1em 2em;
            border-radius: 15px;
            margin: 2em 0 1.5em 0;
            border: 3px solid var(--phb-burgundy);
            box-shadow: 0 6px 12px rgba(0, 0, 0, 0.15);
        }
        
        h2 {
            font-size: 1.6em;
            border-bottom: 3px solid var(--phb-gold);
            padding-bottom: 0.4em;
            margin: 2em 0 1em 0;
        }
        
        h3 {
            font-size: 1.35em;
            position: relative;
        }
        
        h3::after {
            content: "";
            position: absolute;
            bottom: -3px;
            left: 0;
            width: 60%;
            height: 1px;
            background: linear-gradient(90deg, var(--phb-gold) 0%, transparent 100%);
        }
        
        p {
            color: #000;
            text-align: justify;
            text-indent: 1.2em;
            margin: 0.9em 0;
            hyphens: auto;
        }
        
        h1 + p, h2 + p, h3 + p, h4 + p {
            text-indent: 0;
        }
        
        strong {
            color: var(--phb-burgundy);
            font-weight: 700;
        }
        
        em {
            font-style: italic;
        }
        
        blockquote {
            background: linear-gradient(135deg, rgba(255, 248, 230, 0.95) 0%, rgba(250, 240, 210, 0.95) 100%);
            border-left: 6px solid var(--phb-burgundy);
            border-right: 2px solid var(--phb-gold);
            color: #000;
            padding: 1.5em 2em;
            margin: 2em 0;
            font-style: italic;
            border-radius: 0 12px 12px 0;
            position: relative;
        }
        
        blockquote::before {
            content: "";
            font-family: "Cinzel Decorative", serif;
            font-size: 4em;
            color: rgba(88, 24, 13, 0.2);
            position: absolute;
            top: -0.2em;
            left: 0.1em;
        }

        table {
            width: 100%;
            margin: 2em 0;
            border-collapse: separate;
            border-spacing: 0;
            background: rgba(255, 252, 245, 0.98);
            border: 3px solid var(--phb-burgundy);
            border-radius: 12px;
            overflow: hidden;
        }

        th {
            background: linear-gradient(180deg, #f4b942 0%, #e69a28 50%, #d4841f 100%);
            color: white;
            font-family: "Cinzel", serif;
            font-weight: bold;
            text-align: center;
            padding: 1em 0.8em;
            text-shadow: 0px 1px 0px rgba(0, 0, 0, 0.5);
        }

        td {
            background-color: transparent;
            color: #000;
            padding: 0.8em;
            border: 1px solid rgba(201, 173, 106, 0.3);
            vertical-align: top;
        }

        tr:nth-child(even) td {
            background-color: rgba(255, 248, 235, 0.6);
        }

        .spell, .stat-block, .feature, .magic-item {
            background: linear-gradient(135deg, rgba(255, 252, 245, 0.95) 0%, rgba(250, 245, 235, 0.95) 100%);
            border: 3px solid var(--phb-burgundy);
            border-radius: 12px;
            padding: 1.5em;
            margin: 2em 0;
            position: relative;
        }

        .spell-title, .feature-title, .magic-item-title {
            font-family: "Cinzel", serif;
            font-weight: bold;
            color: var(--phb-burgundy);
            font-size: 1.3em;
            text-align: center;
            margin: 0 0 0.8em 0;
            text-shadow: 1px 1px 2px rgba(255, 255, 255, 0.8);
        }

        .stat-block h4 {
            font-family: "Cinzel Decorative", serif;
            text-align: center;
            font-size: 1.5em;
            margin: 0 0 1em 0;
            padding: 0.5em;
            background: linear-gradient(90deg, rgba(255, 255, 255, 0.3) 0%, rgba(255, 248, 230, 0.6) 50%, rgba(255, 255, 255, 0.3) 100%);
            border-radius: 8px;
        }

        @media print {
            body {
                background-color: var(--phb-cream);
                -webkit-print-color-adjust: exact;
                print-color-adjust: exact;
            }

            * {
                -webkit-print-color-adjust: exact;
                print-color-adjust: exact;
            }
        }
    </style>
</head>
<body>
    $content
</body>
</html>
        """
    }
}