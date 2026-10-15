package com.duckduckgo.app.browser.desktop

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Duck's tab repository is the only owner of tabs. This view implements the
 * interaction policy of main/DesktopTabStrip without creating WebViews.
 */
class DesktopTabStripView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {
    data class Item(
        val id: String,
        val title: String,
        val pinned: Boolean = false,
        val group: String? = null,
    )

    enum class Mode { NORMAL, COMPACT, GROUPED }
    enum class Position { TOP, BOTTOM }

    private val preferences = context.getSharedPreferences("ybrowser_desktop_tab_strip", Context.MODE_PRIVATE)
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val plus = textButton("+", "新建标签页", 23f)
    private val folder = textButton("▤", "展开分组", 19f).apply {
        visibility = View.GONE
        setOnClickListener {
            groupsExpanded = !groupsExpanded
            rebuild()
        }
    }
    private val groupRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val groupScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(groupRow)
        visibility = View.GONE
    }
    private val strip = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    var mode: Mode = runCatching {
        Mode.valueOf(preferences.getString("mode", Mode.NORMAL.name) ?: Mode.NORMAL.name)
    }.getOrDefault(Mode.NORMAL)
        private set

    var position: Position = runCatching {
        Position.valueOf(preferences.getString("position", Position.TOP.name) ?: Position.TOP.name)
    }.getOrDefault(Position.TOP)
        private set

    private var fullWidth = preferences.getInt("fullWidth", 180).coerceIn(36, 320)
    private var compactWidth = preferences.getInt("compactWidth", 56).coerceIn(28, 160)
    private var configuredHeight = preferences.getInt("height", 44).coerceIn(28, 88)
    private var groupsExpanded = false
    private var allTabs: List<Item> = emptyList()
    private var selectedTabId: String? = null

    var onSelectTab: ((String) -> Unit)? = null
    var onCloseTab: ((String) -> Unit)? = null
    var onNewTab: (() -> Unit)? = null
    var onTogglePin: ((String) -> Unit)? = null
    var onDuplicate: ((String) -> Unit)? = null
    var onMove: ((String, String) -> Unit)? = null
    var onSetGroup: ((String, String?) -> Unit)? = null
    var onCloseOthers: ((String) -> Unit)? = null
    var onLayoutPreferencesChanged: ((Position, Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        scroll.addView(row)
        strip.addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        strip.addView(folder, LayoutParams(dp(38), LayoutParams.MATCH_PARENT))
        strip.addView(plus, LayoutParams(dp(44), LayoutParams.MATCH_PARENT))
        addView(groupScroller, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, dp(configuredHeight)))
        plus.setOnClickListener { onNewTab?.invoke() }
        plus.setOnLongClickListener { showSettings(plus); true }
        visibility = View.VISIBLE
    }

    /** Keep the previous scroll position unless the selected tab changes. */
    fun submit(items: List<Item>, selectedId: String?) {
        if (items == allTabs && selectedId == selectedTabId) return
        val changed = selectedId != selectedTabId
        allTabs = items.toList()
        selectedTabId = selectedId
        rebuild(scrollToActive = changed)
    }

    fun totalHeightDp(): Int =
        configuredHeight + if (mode == Mode.GROUPED && groupsExpanded && allTabs.map { it.group }.distinct().size > 1) 34 else 0

    private fun selectedGroup(): String? = allTabs.firstOrNull { it.id == selectedTabId }?.group

    private fun visibleTabs(): List<Item> =
        if (mode == Mode.GROUPED) allTabs.filter { it.group == selectedGroup() } else allTabs

    private fun rebuild(scrollToActive: Boolean = false) {
        val oldScroll = scroll.scrollX
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val foreground = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        setBackgroundColor(surface)
        plus.setTextColor(foreground)
        folder.setTextColor(foreground)
        row.removeAllViews()
        groupRow.removeAllViews()

        val groups = allTabs.map { it.group }.distinct()
        val showGroups = mode == Mode.GROUPED && groups.size > 1
        folder.visibility = if (showGroups) View.VISIBLE else View.GONE
        folder.contentDescription = if (groupsExpanded) "收起分组" else "展开分组"
        groupScroller.visibility = if (showGroups && groupsExpanded) View.VISIBLE else View.GONE
        val activeGroup = selectedGroup()
        if (showGroups && groupsExpanded) {
            groups.forEach { group ->
                val groupButton = textButton(group ?: "未分组", "选择分组", 12f)
                groupButton.setTextColor(foreground)
                groupButton.background = pill(surface, primary, group == activeGroup)
                groupButton.setPadding(dp(12), 0, dp(12), 0)
                groupButton.setOnClickListener {
                    allTabs.firstOrNull { it.group == group }?.let { tab -> onSelectTab?.invoke(tab.id) }
                }
                groupRow.addView(groupButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    marginStart = dp(4)
                })
            }
        }
        val tabs = visibleTabs()
        val widthFor: (Item) -> Int = { tab ->
            if (mode == Mode.COMPACT) compactWidth
            else if (tab.id == selectedTabId) fullWidth
            else (fullWidth * 0.78f).toInt().coerceIn(28, 320)
        }
        tabs.forEach { tab ->
            val selected = tab.id == selectedTabId
            val width = widthFor(tab)
            val button = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pill(surface, primary, selected)
                isClickable = true
                isFocusable = true
                contentDescription = tab.title
                setOnClickListener { onSelectTab?.invoke(tab.id) }
            }
            val icon = textButton(if (tab.pinned) "◆" else "◉", "", 13f).apply {
                isClickable = false
                isFocusable = false
                setTextColor(foreground)
            }
            button.addView(icon, LayoutParams(dp(24), LayoutParams.MATCH_PARENT))
            val showTitle = mode != Mode.COMPACT && width >= (if (selected) 96 else 72)
            if (showTitle) {
                val title = TextView(context).apply {
                    text = tab.title.ifBlank { "新标签页" }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 12f
                    setTextColor(foreground)
                    gravity = Gravity.CENTER_VERTICAL
                }
                button.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            } else {
                button.addView(View(context), LayoutParams(0, 1, 1f))
            }
            if (selected && mode != Mode.COMPACT && !tab.pinned && width >= 136) {
                val close = textButton("×", "关闭标签页", 21f).apply {
                    setTextColor(foreground)
                    setOnClickListener { onCloseTab?.invoke(tab.id) }
                }
                button.addView(close, LayoutParams(dp(29), LayoutParams.MATCH_PARENT))
            }
            if ((selected || mode == Mode.COMPACT) && width >= 56) {
                val more = textButton("⋮", "更多标签操作", 20f).apply {
                    setTextColor(foreground)
                    setOnClickListener { showTabMenu(this, tab) }
                }
                button.addView(more, LayoutParams(dp(29), LayoutParams.MATCH_PARENT))
            }

            // Matches main's long-press drag and drop semantics; the repository
            // applies the final ordering, including persistence across restarts.
            button.setOnLongClickListener {
                startTabDrag(button, tab.id)
                true
            }
            button.setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                    DragEvent.ACTION_DROP -> {
                        val dragged = event.localState as? String
                        if (dragged != null && dragged != tab.id) onMove?.invoke(dragged, tab.id)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_EXITED,
                    DragEvent.ACTION_DRAG_ENDED, DragEvent.ACTION_DRAG_LOCATION -> true
                    else -> false
                }
            }
            row.addView(button, LayoutParams(dp(width), dp(configuredHeight - 6)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        // Like main, retain selected-tab visibility after creation or switching.
        if (scrollToActive) {
            val index = tabs.indexOfFirst { it.id == selectedTabId }
            if (index >= 0) scroll.post {
                val item = row.getChildAt(index) ?: return@post
                scroll.smoothScrollTo((item.left - dp(8)).coerceAtLeast(0), 0)
            }
        } else {
            scroll.post { scroll.scrollTo(oldScroll, 0) }
        }
        if (showGroups) {
            if (groupsExpanded) {
                groupScroller.visibility = View.VISIBLE
                groupScroller.setOnClickListener(null)
            }
            plus.contentDescription = "新建标签页，长按设置标签栏"
        }
        onLayoutPreferencesChanged?.invoke(position, totalHeightDp())
    }

    private fun startTabDrag(view: View, tabId: String) {
        val clip = ClipData.newPlainText("ybrowser-tab", tabId)
        view.startDragAndDrop(clip, View.DragShadowBuilder(view), tabId, 0)
    }

    private fun showTabMenu(anchor: View, tab: Item) {
        PopupMenu(context, anchor).apply {
            menu.add(if (tab.pinned) "取消固定标签" else "固定标签").setOnMenuItemClickListener {
                onTogglePin?.invoke(tab.id); true
            }
            menu.add("复制标签").setOnMenuItemClickListener {
                onDuplicate?.invoke(tab.id); true
            }
            menu.add("向前移动").setOnMenuItemClickListener {
                moveByOne(tab.id, -1); true
            }
            menu.add("向后移动").setOnMenuItemClickListener {
                moveByOne(tab.id, 1); true
            }
            menu.add("设置分组").setOnMenuItemClickListener {
                showGroupDialog(tab); true
            }
            if (tab.group != null) menu.add("取消分组").setOnMenuItemClickListener {
                onSetGroup?.invoke(tab.id, null); true
            }
            menu.add("关闭其他未固定标签").setOnMenuItemClickListener {
                onCloseOthers?.invoke(tab.id); true
            }
            menu.add("关闭标签").setOnMenuItemClickListener {
                onCloseTab?.invoke(tab.id); true
            }
            show()
        }
    }

    private fun moveByOne(id: String, step: Int) {
        val index = allTabs.indexOfFirst { it.id == id }
        val target = allTabs.getOrNull(index + step) ?: return
        if (index >= 0) onMove?.invoke(id, target.id)
    }

    private fun showGroupDialog(tab: Item) {
        val input = EditText(context).apply {
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(tab.group.orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("标签分组")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                onSetGroup?.invoke(tab.id, input.text.toString().trim().takeIf { it.isNotBlank() })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettings(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add("普通标签栏").setOnMenuItemClickListener { setMode(Mode.NORMAL); true }
            menu.add("紧凑标签栏").setOnMenuItemClickListener { setMode(Mode.COMPACT); true }
            menu.add("按分组显示").setOnMenuItemClickListener { setMode(Mode.GROUPED); true }
            if (mode == Mode.GROUPED) {
                menu.add(if (groupsExpanded) "收起分组列表" else "展开分组列表").setOnMenuItemClickListener {
                    groupsExpanded = !groupsExpanded
                    rebuild()
                    true
                }
            }
            menu.add("增加标签宽度").setOnMenuItemClickListener {
                setWidth(if (mode == Mode.COMPACT) compactWidth + 12 else fullWidth + 16)
                true
            }
            menu.add("减小标签宽度").setOnMenuItemClickListener {
                setWidth(if (mode == Mode.COMPACT) compactWidth - 12 else fullWidth - 16)
                true
            }
            menu.add("增加标签栏高度").setOnMenuItemClickListener {
                configuredHeight = (configuredHeight + 4).coerceIn(28, 88)
                preferences.edit().putInt("height", configuredHeight).apply()
                strip.layoutParams = strip.layoutParams.apply { height = dp(configuredHeight) }
                rebuild()
                true
            }
            menu.add("减小标签栏高度").setOnMenuItemClickListener {
                configuredHeight = (configuredHeight - 4).coerceIn(28, 88)
                preferences.edit().putInt("height", configuredHeight).apply()
                strip.layoutParams = strip.layoutParams.apply { height = dp(configuredHeight) }
                rebuild()
                true
            }
            menu.add(if (position == Position.TOP) "移到底部" else "移到顶部").setOnMenuItemClickListener {
                position = if (position == Position.TOP) Position.BOTTOM else Position.TOP
                preferences.edit().putString("position", position.name).apply()
                onLayoutPreferencesChanged?.invoke(position, totalHeightDp())
                true
            }
            show()
        }
    }

    private fun setMode(value: Mode) {
        mode = value
        preferences.edit().putString("mode", value.name).apply()
        rebuild(scrollToActive = true)
    }

    private fun setWidth(value: Int) {
        if (mode == Mode.COMPACT) {
            compactWidth = value.coerceIn(28, 160)
            preferences.edit().putInt("compactWidth", compactWidth).apply()
        } else {
            fullWidth = value.coerceIn(36, 320)
            preferences.edit().putInt("fullWidth", fullWidth).apply()
        }
        rebuild()
    }

    private fun textButton(label: String, description: String, sp: Float): TextView =
        TextView(context).apply {
            text = label
            textSize = sp
            gravity = Gravity.CENTER
            contentDescription = description.ifBlank { label }
            isClickable = true
            isFocusable = true
        }

    private fun pill(surface: Int, primary: Int, selected: Boolean) =
        GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(ColorUtils.blendARGB(surface, primary, if (selected) 0.21f else 0.065f))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
