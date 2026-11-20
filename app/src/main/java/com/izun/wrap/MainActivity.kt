package com.izun.wrap

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var pageReady = false
    private var pendingJs: String? = null
    private var askedForPermissions = false

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    )

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            lifecycleScope.launch { readAndPush() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingJs?.let { js ->
                    pendingJs = null
                    view.evaluateJavascript(js, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return if (url.startsWith("https://izun.surge.sh")) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                    true
                }
            }
        }
        webView.addJavascriptInterface(Bridge(), "IzunHealth")
        setContentView(webView)
        webView.loadUrl("https://izun.surge.sh/")
        sync()
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class Bridge {
        @JavascriptInterface
        fun requestSync() {
            sync()
        }
    }

    private fun sync() {
        lifecycleScope.launch { ensurePermissionsAndRead() }
    }

    private suspend fun ensurePermissionsAndRead() {
        val status = HealthConnectClient.getSdkStatus(this)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            pushStatus("Health Connect לא מותקן במכשיר הזה - אפשר להמשיך עם ייבוא CSV")
            return
        }
        val client = HealthConnectClient.getOrCreate(this)
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            if (!askedForPermissions) {
                askedForPermissions = true
                requestPermissions.launch(permissions)
            } else {
                pushStatus("אין עדיין הרשאת קריאה לנתוני הבריאות - אפשר לאשר בהגדרות Health Connect")
            }
            return
        }
        readAndPush()
    }

    private suspend fun readAndPush() {
        try {
            val client = HealthConnectClient.getOrCreate(this)
            val granted = client.permissionController.getGrantedPermissions()
            if (!granted.containsAll(permissions)) return

            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val end = Instant.now()
            val range = TimeRangeFilter.between(start, end)

            val steps = client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range)
            )[StepsRecord.COUNT_TOTAL] ?: 0L

            val active = client.aggregate(
                AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range)
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories

            val total = client.aggregate(
                AggregateRequest(setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL), range)
            )[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories

            // Samsung Health writes workout calories to TotalCaloriesBurned; if no active
            // calories exist, fall back to that so the user still sees a burn number.
            val activeOut = active?.takeIf { it > 0.0 } ?: total

            val payload = JSONObject()
                .put("date", LocalDate.now(zone).toString())
                .put("steps", steps)
                .put("active", if (activeOut != null) JSONObject.wrap(Math.round(activeOut)) else JSONObject.NULL)
                .put("total", if (total != null) JSONObject.wrap(Math.round(total)) else JSONObject.NULL)
                .toString()

            push("window.onIzunHealth && window.onIzunHealth($payload)")
        } catch (e: Exception) {
            pushStatus("לא הצלחתי לקרוא נתונים מהשעון כרגע")
        }
    }

    private fun pushStatus(msg: String) {
        val safe = JSONObject.quote(msg)
        push("window.onIzunHealthStatus && window.onIzunHealthStatus($safe)")
    }

    private fun push(js: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (pageReady) {
                webView.evaluateJavascript(js, null)
            } else {
                pendingJs = js
            }
        }
    }
}
