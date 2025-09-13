// MainActivity.kt
package com.dndpdf.sharehandler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import android.os.Environment

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    
    // New SAF Launcher
    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                // You can save this URI persistently using SharedPreferences if you want
                // to avoid asking the user every time.
                readObsidianFileFromUri(uri.toString())
            } else {
                Toast.makeText(this, "Obsidian vault not selected.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up WebView for PDF generation
        webView = WebView(this)
        setContentView(webView) // FIXED: Added missing setContentView
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Handle shared content
        handleIncomingContent()
    }

    private fun handleIncomingContent() {
        when {
            intent?.action == Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (sharedText != null) {
                        processMarkdownContent(sharedText)
                    } else {
                        Toast.makeText(this, "No content received", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            intent?.action == Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null && data.scheme == "obsidian") {
                    // Launch the SAF folder picker for the user to select their vault
                    openDocumentTreeLauncher.launch(null)
                } else {
                    Toast.makeText(this, "No valid content to process", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            else -> {
                Toast.makeText(this, "No valid content to process", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // FIXED: Added proper null checks and error handling
    private fun readObsidianFileFromUri(treeUri: String) {
        val sharedUri = intent?.dataString
        if (sharedUri == null) {
            Toast.makeText(this, "No URI data found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val regex = "file=([^&]+)".toRegex()
        val matchResult = regex.find(sharedUri)
        val fileName = matchResult?.groupValues?.getOrNull(1)?.replace("%20", " ")

        if (fileName.isNullOrEmpty()) {
            Toast.makeText(this, "Could not extract filename from URL: $sharedUri", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        
        val content = getFileContentFromDocumentTree(treeUri, fileName)
        if (content != null) {
            processMarkdownContent(content)
        } else {
            Toast.makeText(this, "File not found or unreadable: $fileName", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // New function to get file content via SAF
    private fun getFileContentFromDocumentTree(treeUri: String, fileName: String): String? {
        return try {
            val rootDocument = DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
            val fileDocument = rootDocument?.findFile("$fileName.md") ?: rootDocument?.findFile(fileName)

            if (fileDocument?.exists() == true && fileDocument.isFile) {
                val inputStream = contentResolver.openInputStream(fileDocument.uri)
                inputStream?.bufferedReader().use { it?.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            // FIXED: Added logging of the actual exception
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun processMarkdownContent(markdown: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Processing D&D content...", Toast.LENGTH_SHORT).show()

                val styledHtml = convertMarkdownToStyledHtml(markdown)
                
                // FIXED: Ensure WebView operations are on main thread
                runOnUiThread {
                    generatePdf(styledHtml)
                }

            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun convertMarkdownToStyledHtml(markdown: String): String {
        // Convert markdown to HTML with D&D styling
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

        // Convert HTML div blocks (spell blocks, stat blocks, etc.)
        // These are already in HTML format, so preserve them

        // Convert tables (basic implementation)
        html = convertTables(html)

        // Convert line breaks
        html = html.replace("\n\n", "</p><p>")
        html = html.replace("\n", "<br>")

        // Wrap in paragraphs
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

        /* D&D Content Blocks */
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

    private fun generatePdf(htmlContent: String) {
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)

        webView.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                createPdfFromWebView()
            }
        })
    }

    private fun createPdfFromWebView() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val printAdapter: PrintDocumentAdapter = webView.createPrintDocumentAdapter("DnD_Adventure")

        val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 600, 600))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        val fileName = "DnD_Adventure_${System.currentTimeMillis()}"

        printManager.print(fileName, printAdapter, printAttributes)

        Toast.makeText(this, "PDF generation started!", Toast.LENGTH_LONG).show()
        finish()
    }
}