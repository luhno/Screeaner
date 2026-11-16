package app.screeaner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Sends text copied on the tablet to the Mac and puts the Mac's text on the
 * tablet's clipboard, without bouncing the same text back and forth.
 * Android only reports clipboard changes to the app in front, which is
 * exactly when Screeaner is in use.
 */
class ClipboardBridge(private val context: Context, private val onCopy: (String) -> Unit) {
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var lastText: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = manager.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
        if (text.isNullOrEmpty() || text == lastText) return@OnPrimaryClipChangedListener
        lastText = text
        onCopy(text)
    }

    fun start() = manager.addPrimaryClipChangedListener(listener)

    fun stop() = manager.removePrimaryClipChangedListener(listener)

    fun apply(text: String) {
        if (text == lastText) return
        lastText = text
        manager.setPrimaryClip(ClipData.newPlainText("Mac", text))
    }
}
