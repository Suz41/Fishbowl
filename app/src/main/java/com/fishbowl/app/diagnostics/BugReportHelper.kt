package com.fishbowl.app.diagnostics

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Automates runtime device specification detection, issue prefilling,
 * and user-guided GitHub bug reporting for Fishbowl.
 */
object BugReportHelper {

    const val GITHUB_REPO_URL = "https://github.com/Suz41/Fishbowl"
    const val GITHUB_NEW_ISSUE_URL = "https://github.com/Suz41/Fishbowl/issues/new"
    const val MAX_SAFE_URL_LENGTH = 1800

    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val androidVersion: String,
        val apiLevel: Int,
        val versionName: String,
        val versionCode: Long,
        val architecture: String
    )

    @Suppress("DEPRECATION")
    @JvmStatic
    fun collectDeviceInfo(context: Context?): DeviceInfo {
        val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "unknown"
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown"
        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "unknown"
        val apiLevel = Build.VERSION.SDK_INT

        val architecture = when {
            Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.isNotEmpty() && !Build.SUPPORTED_ABIS[0].isNullOrBlank() -> {
                Build.SUPPORTED_ABIS[0]
            }
            !Build.CPU_ABI.isNullOrBlank() -> Build.CPU_ABI
            else -> "arm64-v8a"
        }

        var versionName = "unknown"
        var versionCode: Long = -1

        if (context != null) {
            try {
                val pi: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                versionName = pi.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
                versionCode = PackageInfoCompat.getLongVersionCode(pi)
            } catch (_: Throwable) {
                versionName = com.termux.BuildConfig.VERSION_NAME
                versionCode = com.termux.BuildConfig.VERSION_CODE.toLong()
            }
        } else {
            versionName = com.termux.BuildConfig.VERSION_NAME
            versionCode = com.termux.BuildConfig.VERSION_CODE.toLong()
        }

        return DeviceInfo(
            manufacturer = manufacturer,
            model = model,
            androidVersion = androidVersion,
            apiLevel = apiLevel,
            versionName = versionName,
            versionCode = versionCode,
            architecture = architecture
        )
    }

    @JvmStatic
    fun formatDeviceInfoMarkdown(info: DeviceInfo): String {
        return buildString {
            append("## Device Information\n")
            append("- Manufacturer: ").append(info.manufacturer).append("\n")
            append("- Model: ").append(info.model).append("\n")
            append("- Android Version: ").append(info.androidVersion).append("\n")
            append("- API Level: ").append(info.apiLevel).append("\n")
            append("- Fishbowl Version: ").append(info.versionName).append("\n")
            append("- Version Code: ").append(info.versionCode).append("\n")
            append("- Architecture: ").append(info.architecture)
        }
    }

    @JvmStatic
    fun buildBugReportMarkdown(
        context: Context?,
        problemDescription: String? = null,
        logExcerpt: String? = null
    ): String {
        val info = collectDeviceInfo(context)
        val devSection = formatDeviceInfoMarkdown(info)

        val problem = if (!problemDescription.isNullOrBlank()) {
            problemDescription.trim()
        } else {
            "Describe what went wrong."
        }

        val logs = if (!logExcerpt.isNullOrBlank()) {
            "```text\n${logExcerpt.trim()}\n```"
        } else {
            "Attach if available."
        }

        return buildString {
            append("## Problem\n")
            append(problem).append("\n\n")
            append("## Steps to Reproduce\n")
            append("1.\n2.\n3.\n\n")
            append("## Expected vs Actual Behavior\n")
            append("What did you expect, and what happened?\n\n")
            append(devSection).append("\n\n")
            append("## Logs / Screenshots\n")
            append(logs).append("\n\n")
            append("## Additional Information\n")
            append("Anything else?")
        }
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun buildUrlWithParams(baseUrl: String, params: Map<String, String>): String {
        val sb = StringBuilder(baseUrl)
        var first = true
        for ((k, v) in params) {
            if (first) {
                sb.append("?")
                first = false
            } else {
                sb.append("&")
            }
            sb.append(urlEncode(k)).append("=").append(urlEncode(v))
        }
        return sb.toString()
    }

    @JvmStatic
    fun buildGitHubIssueUrl(
        context: Context?,
        title: String? = null,
        logExcerpt: String? = null
    ): String {
        val issueTitle = if (!title.isNullOrBlank()) {
            if (title.startsWith("[BUG]")) title.trim() else "[BUG] ${title.trim()}"
        } else {
            "[BUG] "
        }

        val fullReport = buildBugReportMarkdown(context, null, logExcerpt)

        // URL length safeguard: if full report exceeds safe browser GET parameter size,
        // use a concise reference pointing to clipboard
        val bodyForUrl = if (fullReport.length > 800 && !logExcerpt.isNullOrBlank()) {
            buildBugReportMarkdown(
                context,
                null,
                "[Full diagnostic log attached to system clipboard - paste here]"
            )
        } else {
            fullReport
        }

        val params = linkedMapOf(
            "template" to "bug_report.md",
            "labels" to "bug",
            "title" to issueTitle,
            "body" to bodyForUrl
        )

        val fullUrl = buildUrlWithParams(GITHUB_NEW_ISSUE_URL, params)

        return if (fullUrl.length > MAX_SAFE_URL_LENGTH) {
            val minimalBody = buildBugReportMarkdown(context, null, null)
            val minParams = linkedMapOf(
                "template" to "bug_report.md",
                "labels" to "bug",
                "title" to issueTitle,
                "body" to minimalBody
            )
            buildUrlWithParams(GITHUB_NEW_ISSUE_URL, minParams)
        } else {
            fullUrl
        }
    }

    @JvmStatic
    fun copyReportToClipboard(context: Context, reportText: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("fishbowl_bug_report", reportText))
            Toast.makeText(context, "Bug report copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Initiates the bug reporting flow:
     * 1. Detects runtime device specs.
     * 2. Builds markdown report and copies to clipboard.
     * 3. Displays OLED confirmation dialog with detected technical specifications.
     * 4. Opens GitHub in browser upon user confirmation, with fallback error handling.
     */
    @JvmStatic
    fun openBugReportFlow(
        activity: Activity,
        customTitle: String? = null,
        logExcerpt: String? = null
    ) {
        val info = collectDeviceInfo(activity)
        val fullReport = buildBugReportMarkdown(activity, null, logExcerpt)
        val targetUrl = buildGitHubIssueUrl(activity, customTitle, logExcerpt)

        // Copy complete report to clipboard in advance for seamless user pasting
        copyReportToClipboard(activity, fullReport)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            val bg = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.parseColor("#27272A"))
                cornerRadius = dp(14).toFloat()
            }
            background = bg
        }

        val titleTv = TextView(activity).apply {
            text = "REPORT A BUG"
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        root.addView(titleTv)

        val subTv = TextView(activity).apply {
            text = "Automatic device diagnostic detection"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(0, dp(2), 0, 0)
        }
        root.addView(subTv)

        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#18181B"))
            val lp = LinearLayout.LayoutParams(-1, dp(1)).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }
        root.addView(divider)

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val contentBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val noticeTv = TextView(activity).apply {
            text = "Technical hardware and software specifications have been detected to assist debugging. No personal identifiers (IMEI, accounts, phone numbers, or credentials) are collected."
            textSize = 11.5f
            setTextColor(Color.parseColor("#D4D4D8"))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }
        contentBox.addView(noticeTv)

        val specsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val cardBg = GradientDrawable().apply {
                setColor(Color.parseColor("#090A0D"))
                setStroke(dp(1), Color.parseColor("#1F242C"))
                cornerRadius = dp(8).toFloat()
            }
            background = cardBg
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
            layoutParams = lp
        }

        val specsText = TextView(activity).apply {
            text = buildString {
                append("• Manufacturer: ").append(info.manufacturer).append("\n")
                append("• Model: ").append(info.model).append("\n")
                append("• Android: ").append(info.androidVersion).append(" (API ").append(info.apiLevel).append(")\n")
                append("• Version: ").append(info.versionName).append(" (Code ").append(info.versionCode).append(")\n")
                append("• Architecture: ").append(info.architecture)
            }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#38BDF8"))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }
        specsCard.addView(specsText)
        contentBox.addView(specsCard)

        val footerTv = TextView(activity).apply {
            text = "Your draft bug report has been copied to clipboard. You can review and edit all fields on GitHub before submitting."
            textSize = 10.5f
            setTextColor(Color.parseColor("#71717A"))
            setPadding(0, dp(2), 0, dp(4))
        }
        contentBox.addView(footerTv)

        scroll.addView(contentBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
            }
            layoutParams = lp
        }

        fun createPixelBtn(textStr: String, bgCol: Int, textCol: Int): Button {
            return Button(activity).apply {
                text = textStr
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(textCol)
                val btnBg = GradientDrawable().apply {
                    setColor(bgCol)
                    cornerRadius = dp(8).toFloat()
                }
                background = btnBg
                isAllCaps = true
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
        }

        val btnCancel = createPixelBtn("CANCEL", Color.parseColor("#18191E"), Color.parseColor("#A1A1AA")).apply {
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(btnCancel, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })

        val btnCopy = createPixelBtn("COPY REPORT", Color.parseColor("#27272A"), Color.WHITE).apply {
            setOnClickListener {
                copyReportToClipboard(activity, fullReport)
            }
        }
        btnRow.addView(btnCopy, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })

        val btnOpen = createPixelBtn("OPEN GITHUB", Color.parseColor("#0EA5E9"), Color.WHITE).apply {
            setOnClickListener {
                dialog.dismiss()
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                } catch (t: Throwable) {
                    showFallbackErrorDialog(activity, fullReport)
                }
            }
        }
        btnRow.addView(btnOpen, LinearLayout.LayoutParams(-2, dp(36)))

        root.addView(btnRow)
        dialog.setContentView(root)

        dialog.window?.let { win ->
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.85f)
            val lp = WindowManager.LayoutParams().apply {
                copyFrom(win.attributes)
                width = (activity.resources.displayMetrics.widthPixels * 0.92).toInt()
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            win.attributes = lp
        }

        dialog.show()
    }

    private fun showFallbackErrorDialog(activity: Activity, fullReport: String) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val errDialog = Dialog(activity)
        errDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            val bg = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(dp(1), Color.parseColor("#DC2626"))
                cornerRadius = dp(14).toFloat()
            }
            background = bg
        }

        val titleTv = TextView(activity).apply {
            text = "COULD NOT LAUNCH BROWSER"
            textSize = 13.5f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#EF4444"))
        }
        root.addView(titleTv)

        val msgTv = TextView(activity).apply {
            text = "No compatible web browser was detected on this device. The complete formatted bug report with device diagnostics has been copied to your clipboard.\n\nYou can manually paste it on GitHub:\nhttps://github.com/Suz41/Fishbowl/issues"
            textSize = 11f
            setTextColor(Color.parseColor("#D4D4D8"))
            setPadding(0, dp(8), 0, dp(12))
            setLineSpacing(dp(2).toFloat(), 1.15f)
        }
        root.addView(msgTv)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val btnCopy = Button(activity).apply {
            text = "COPY AGAIN"
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#27272A"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener {
                copyReportToClipboard(activity, fullReport)
            }
        }
        btnRow.addView(btnCopy, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(8) })

        val btnDismiss = Button(activity).apply {
            text = "DISMISS"
            textSize = 10f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18191E"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { errDialog.dismiss() }
        }
        btnRow.addView(btnDismiss, LinearLayout.LayoutParams(-2, dp(36)))

        root.addView(btnRow)
        errDialog.setContentView(root)

        errDialog.window?.let { win ->
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            win.setDimAmount(0.85f)
            val lp = WindowManager.LayoutParams().apply {
                copyFrom(win.attributes)
                width = (activity.resources.displayMetrics.widthPixels * 0.90).toInt()
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            win.attributes = lp
        }

        errDialog.show()
    }
}
