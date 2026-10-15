package com.duckduckgo.app.browser.desktop

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Desktop-style horizontal tab strip.
 *
 * Presentation only: TabRepository owns persistence, selection and closing.
 * No WebView, Fragment or page session is created or destroyed by this view.
 */
class DesktopTabStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class Item(val id: String, val title: String)

    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val container = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val plus = TextView(context).apply {
        text = "+"
        textSize = 25f
        gravity = Gravity.CENTER
        contentDescription = "New tab"
        isClickable = true
        isFocusable = true
        setOnClickListener { onNewTab?.invoke() }
    }

    private var onNewTab: (() -> Unit)? = null
    private var lastSelected: String? = null
    private var lastItems: List<Item> = emptyList()
    private var currentSelected: String? = null
    private var onSelect: ((String) -> Unit)? = null
    private var onClose: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        scroll.addView(container, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, dp(44), 1f))
        addView(plus, LayoutParams(dp(46), dp(44)))
        refreshColors()
    }

    fun submit(
        items: List<Item>,
        selectedId: String?,
        onSelectTab: (String) -> Unit,
        onCloseTab: (String) -> Unit,
        onNew: () -> Unit,
    ) {
        onSelect = onSelectTab
        onClose = onCloseTab
        onNewTab = onNew
        if (items == lastItems && selectedId == currentSelected) return

        val previousSelected = currentSelected
        lastItems = items.toList()
        currentSelected = selectedId
        container.removeAllViews()

        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val textColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        setBackgroundColor(surface)
        plus.setTextColor(textColor)

        items.forEach { tab ->
            val selected = tab.id == selectedId
            val tabBackground = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(ColorUtils.blendARGB(surface, primary, if (selected) 0.20f else 0.065f))
            }
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = tabBackground
                isClickable = true
                isFocusable = true
                contentDescription = tab.title
                setOnClickListener { onSelect?.invoke(tab.id) }
            }
            val label = TextView(context).apply {
                text = tab.title.ifBlank { "New tab" }
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(textColor)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            row.addView(label, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            val close = TextView(context).apply {
                text = "×"
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(textColor)
                contentDescription = "Close ${tab.title}"
                isClickable = true
                isFocusable = true
                setOnClickListener { onClose?.invoke(tab.id) }
            }
            row.addView(close, LayoutParams(dp(36), LayoutParams.MATCH_PARENT))
            val rowParams = LayoutParams(dp(164), dp(36)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
                gravity = Gravity.CENTER_VERTICAL
            }
            container.addView(row, rowParams)
        }
        if (previousSelected != selectedId) {
            val index = items.indexOfFirst { it.id == selectedId }
            if (index >= 0) post {
                val row = container.getChildAt(index) ?: return@post
                val x = (row.left - dp(20)).coerceAtLeast(0)
                scroll.smoothScrollTo(x, 0)
            }
        }
        lastSelected = selectedId
    }

    private fun refreshColors() {
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val textColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        setBackgroundColor(surface)
        plus.setTextColor(textColor)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
