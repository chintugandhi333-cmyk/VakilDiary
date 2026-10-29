package com.vakildiary.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vakildiary.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class EcourtsWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var tvCnrInfo: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var btnAutoDetect: Button
    private lateinit var progressBar: ProgressBar

    private var caseId: Int = -1
    private var cnrNumber: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pollerRunnable: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecourts_web_view)

        caseId = intent.getIntExtra("CASE_ID", -1)
        cnrNumber = intent.getStringExtra("CNR_NUMBER") ?: ""

        tvCnrInfo = findViewById(R.id.tvCnrInfo)
        tvInstruction = findViewById(R.id.tvInstruction)
        btnAutoDetect = findViewById(R.id.btnAutoDetect)
        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)

        tvCnrInfo.text = "CNR: $cnrNumber (Copied)"
        tvInstruction.text = "Step 1: Click CNR Search > Paste CNR > Enter Captcha > Search"

        // Auto-copy CNR to Clipboard
        copyToClipboard(cnrNumber)
        Toast.makeText(this, "CNR Copied - Paste in Search", Toast.LENGTH_SHORT).show()

        btnAutoDetect.isEnabled = false
        btnAutoDetect.alpha = 0.5f
        btnAutoDetect.text = "WAIT FOR CAPTCHA & SEARCH RESULT"

        setupWebView()

        btnAutoDetect.setOnClickListener {
            extractCaseDetailsViaJs()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        // --- CACHE BYPASS AUR REAL PAGE SETTINGS ---
        webView.clearCache(true)
        webView.clearHistory()
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) Chrome/120.0.0.0 Mobile"

        webView.addJavascriptInterface(AndroidJsBridge(), "Android")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                startPollingForCaseData()
            }
        }

        // Real eCourts CNR portal URL load
        webView.loadUrl("https://services.ecourts.gov.in/ecourtindia_v6/?c=2")
    }

    private fun startPollingForCaseData() {
        stopPolling()
        pollerRunnable = object : Runnable {
            override fun run() {
                val checkJs = """
                    (function() {
                        var body = document.body ? document.body.innerText : '';
                        var hasHearing = body.indexOf('Next Hearing Date') !== -1;
                        var hasFiling = body.indexOf('Filing Date') !== -1 || body.indexOf('Registration Number') !== -1 || body.indexOf('Case Status') !== -1;
                        return (hasHearing || hasFiling);
                    })();
                """.trimIndent()

                webView.evaluateJavascript(checkJs) { result ->
                    if (result != null && result.contains("true")) {
                        onCaseDetailsDetected()
                    } else {
                        handler.postDelayed(this, 2000)
                    }
                }
            }
        }
        handler.post(pollerRunnable!!)
    }

    private fun stopPolling() {
        pollerRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun onCaseDetailsDetected() {
        stopPolling()
        tvInstruction.text = "✓ Case Opened! Now Click FETCH CORRECT NEXT DATE"
        tvInstruction.setBackgroundColor(Color.parseColor("#D4EDDA"))
        tvInstruction.setTextColor(Color.parseColor("#155724"))

        btnAutoDetect.isEnabled = true
        btnAutoDetect.alpha = 1.0f
        btnAutoDetect.text = "FETCH CORRECT NEXT DATE NOW"
        btnAutoDetect.setBackgroundColor(Color.parseColor("#0A7F2E"))
    }

    private fun extractCaseDetailsViaJs() {
        val extractJs = """
            (function() {
                function getVal(label) {
                    var tables = document.querySelectorAll('table');
                    for (var t = 0; t < tables.length; t++) {
                        var rows = tables[t].rows;
                        for (var r = 0; r < rows.length; r++) {
                            var cells = rows[r].cells;
                            for (var c = 0; c < cells.length; c++) {
                                if (cells[c].innerText && cells[c].innerText.toLowerCase().indexOf(label.toLowerCase()) !== -1) {
                                    if (cells[c + 1]) {
                                        return cells[c + 1].innerText.trim();
                                    }
                                }
                            }
                        }
                    }
                    return '';
                }

                var nextDate = getVal('Next Hearing Date');
                var purpose = getVal('Purpose of hearing');
                if (!purpose) purpose = getVal('Purpose');

                // Fallback scan inside plain text
                if (!nextDate) {
                    var body = document.body ? document.body.innerText : '';
                    var m = body.match(/Next\s+Hearing\s+Date\s*[:\-]?\s*([0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{4})/i);
                    if (m && m[1]) nextDate = m[1];
                }

                Android.onNextDateFound(nextDate || '', purpose || '');
            })();
        """.trimIndent()

        webView.evaluateJavascript(extractJs, null)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("CNR Number", text)
        clipboard.setPrimaryClip(clip)
    }

    inner class AndroidJsBridge {
        @JavascriptInterface
        fun onNextDateFound(rawDate: String, rawPurpose: String) {
            val cleanDate = cleanDateWithRegex(rawDate)
            val purpose = if (rawPurpose.isNotBlank()) rawPurpose.trim() else "Hearing"

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val existingCase = db.caseDao().getById(caseId)
                if (existingCase != null) {
                    val updatedCase = existingCase.copy(
                        nextDate = if (cleanDate.isNotBlank()) cleanDate else null,
                        nextPurpose = purpose,
                        lastChecked = System.currentTimeMillis()
                    )
                    db.caseDao().update(updatedCase)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@EcourtsWebViewActivity,
                            "Next Date Saved: ${if (cleanDate.isNotBlank()) cleanDate else "Not Updated"}",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun cleanDateWithRegex(input: String): String {
        val matcher = Pattern.compile("(\\d{2}[-/]\\d{2}[-/]\\d{4})").matcher(input)
        return if (matcher.find()) {
            matcher.group(1) ?: ""
        } else {
            input.trim()
        }
    }

    override fun onDestroy() {
        stopPolling()
        super.onDestroy()
    }
}