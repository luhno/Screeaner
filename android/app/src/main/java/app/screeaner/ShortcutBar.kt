package app.screeaner

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Side bar of Mac shortcuts for stylus and touch users: sticky modifier keys
 * (tap ⌘ then click = ⌘-click) and one-tap combos like undo and copy.
 * Keys go out as Android key codes, which the Mac maps like a real keyboard.
 */
class ShortcutBar(
    private val container: LinearLayout,
    private val sendKey: (action: Int, keyCode: Int) -> Unit,
) {
    /** Spoken names, in the order of [ALL] (R.array.shortcut_labels). */
    private val names: Array<String> = container.resources.getStringArray(R.array.shortcut_labels)

    class Action(val id: String, val label: String, val keys: IntArray, val sticky: Boolean = false)

    private val held = LinkedHashSet<String>()
    private val views = HashMap<String, TextView>()
    private val density = container.resources.displayMetrics.density

    fun build(enabled: Set<String>) {
        releaseAll()
        container.removeAllViews()
        views.clear()
        for (action in ALL) {
            if (action.id !in enabled) continue
            val view = TextView(container.context).apply {
                text = action.label
                gravity = Gravity.CENTER
                textSize = if (action.label.length > 2) 13f else 18f
                typeface = container.resources.getFont(R.font.jetbrains_mono_medium)
                contentDescription = names.getOrNull(ALL.indexOf(action)) ?: action.label
                setOnClickListener { trigger(action) }
            }
            val size = (52 * density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply {
                val margin = (3 * density).toInt()
                setMargins(margin, margin, margin, margin)
            }
            container.addView(view, params)
            views[action.id] = view
            paint(action.id)
        }
    }

    private fun trigger(action: Action) {
        if (action.sticky) {
            val key = action.keys[0]
            if (held.remove(action.id)) sendKey(UP, key) else {
                held += action.id
                sendKey(DOWN, key)
            }
            paint(action.id)
            return
        }
        // Keys already held by a sticky modifier stay down.
        val heldKeys = held.mapNotNull { id -> ALL.firstOrNull { it.id == id }?.keys?.get(0) }.toSet()
        val toPress = action.keys.filter { it !in heldKeys }
        toPress.forEach { sendKey(DOWN, it) }
        toPress.asReversed().forEach { sendKey(UP, it) }
    }

    val hasHeldKeys: Boolean get() = held.isNotEmpty()

    /** Lets go of every sticky modifier (e.g. when the stream stops). */
    fun releaseAll() {
        for (id in held.toList()) {
            ALL.firstOrNull { it.id == id }?.let { sendKey(UP, it.keys[0]) }
            held.remove(id)
            paint(id)
        }
    }

    private fun paint(id: String) {
        val view = views[id] ?: return
        val latched = id in held
        // Screeaner Dot: ink outlines; the one signal red marks a key held down on the Mac.
        view.background = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(if (latched) Dot.SIGNAL else Dot.ink(0.06f))
            setStroke((1 * density).toInt().coerceAtLeast(1), if (latched) Dot.SIGNAL else Dot.ink(0.24f))
        }
        view.setTextColor(Dot.INK)
        view.isSelected = latched
    }

    companion object {
        private const val DOWN = 0
        private const val UP = 1

        val ALL = listOf(
            Action("cmd", "⌘", intArrayOf(KeyEvent.KEYCODE_META_LEFT), sticky = true),
            Action("opt", "⌥", intArrayOf(KeyEvent.KEYCODE_ALT_LEFT), sticky = true),
            Action("ctrl", "⌃", intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT), sticky = true),
            Action("shift", "⇧", intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT), sticky = true),
            Action("undo", "↶", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_Z)),
            Action("redo", "↷", intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_Z)),
            Action("copy", "⌘C", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_C)),
            Action("paste", "⌘V", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_V)),
            Action("save", "⌘S", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_S)),
            Action("esc", "esc", intArrayOf(KeyEvent.KEYCODE_ESCAPE)),
            Action("delete", "⌫", intArrayOf(KeyEvent.KEYCODE_DEL)),
            Action("switch", "⌘⇥", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_TAB)),
            Action("mission", "⌃↑", intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_DPAD_UP)),
            Action("spotlight", "⌘␣", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_SPACE)),
            Action("zoomIn", "⌘+", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_EQUALS)),
            Action("zoomOut", "⌘−", intArrayOf(KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_MINUS)),
        )
        val DEFAULT = setOf("cmd", "opt", "ctrl", "shift", "undo", "redo", "copy", "paste", "esc")
    }
}
