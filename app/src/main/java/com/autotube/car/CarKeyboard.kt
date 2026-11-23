package com.autotube.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Our own big-key keyboard for the car screen. Android's phone keyboard can't appear on the car
 * display, so the search box (and any text box on the page) is typed here. In search mode it
 * also shows YouTube search suggestions, so a couple of letters are usually enough.
 */
@SuppressLint("ViewConstructor")
class CarKeyboard(
    ctx: Context,
    private val onDone: (text: String, search: Boolean) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(ctx) {

    private val text = StringBuilder()
    private var searchMode = true
    private var searchLabel = "YouTube"
    private var suggest = true
    private var secret = false
    private val field = TextView(ctx)
    private val suggestRow = LinearLayout(ctx)
    private lateinit var doneKey: TextView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var suggestSeq = 0
    private val suggestTask = Runnable { fetchSuggestions() }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF5101214.toInt())
        isClickable = true // swallow taps so they don't reach the page underneath
        setPadding(dp(8), dp(6), dp(8), dp(6))

        field.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = context.pressable(0xFF23272A.toInt(), 0xFF23272A.toInt(), 10)
        }
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.15f).apply { setMargins(dp(3), dp(2), dp(3), dp(4)) })

        suggestRow.orientation = HORIZONTAL
        addView(suggestRow, LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.95f))

        row("1234567890")
        row("qwertyuiop")
        row("asdfghjkl", pad = 0.5f)
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(View(ctx), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f))
            "zxcvbnm".forEach { c -> addView(key(c.toString()) { type(c.toString()) }, keyLp(1f)) }
            addView(iconKey(R.drawable.ic_backspace, onLong = { text.setLength(0); changed() }) {
                if (text.isNotEmpty()) text.setLength(text.length - 1)
                changed()
            }, keyLp(2.5f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(iconKey(R.drawable.ic_close) { onClose() }, keyLp(1.5f))
            addView(key("-") { type("-") }, keyLp(1f))
            addView(key("space") { type(" ") }, keyLp(4.5f))
            addView(key(".") { type(".") }, keyLp(1f))
            doneKey = key("Search", accent = true) { submit(text.toString(), searchMode) }
            addView(doneKey, keyLp(2f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /**
     * @param label what a search looks in ("YouTube", "Netflix"...); [suggest] shows YouTube's
     * suggestions; [secret] hides the typed text (password boxes: the car screen is visible to all).
     */
    fun open(initial: String, search: Boolean, label: String = "YouTube", suggest: Boolean = true, secret: Boolean = false) {
        text.setLength(0)
        if (!secret) text.append(initial)
        searchMode = search
        searchLabel = label
        this.suggest = suggest && search
        this.secret = secret
        doneKey.text = if (search) "Search" else "Enter"
        visibility = VISIBLE
        changed()
    }

    fun shutdown() = io.shutdownNow()

    private fun submit(t: String, search: Boolean) {
        main.removeCallbacks(suggestTask)
        suggestSeq++
        onDone(t, search)
    }

    private fun type(s: String) {
        if (text.length < 200) text.append(s)
        changed()
    }

    private fun changed() {
        if (text.isEmpty()) {
            field.text = if (searchMode) "Search $searchLabel" else if (secret) "Password" else "Type here"
            field.setTextColor(0xFF8A9095.toInt())
        } else {
            field.text = (if (secret) "•".repeat(text.length) else text.toString()) + "▏"
            field.setTextColor(Color.WHITE)
        }
        main.removeCallbacks(suggestTask)
        if (suggest && text.isNotBlank()) main.postDelayed(suggestTask, 250) else showSuggestions(emptyList())
    }

    private fun fetchSuggestions() {
        val q = text.toString()
        val seq = ++suggestSeq
        io.execute {
            val list = try {
                val c = URL(
                    "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&ie=utf-8&oe=utf-8&q=" +
                        URLEncoder.encode(q, "UTF-8")
                ).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                c.disconnect()
                val arr = JSONArray(body).getJSONArray(1)
                (0 until minOf(arr.length(), 4)).map { arr.getString(it) }
            } catch (e: Exception) {
                Log.d("CarKeyboard", "suggestions: $e")
                emptyList()
            }
            main.post { if (seq == suggestSeq && visibility == VISIBLE) showSuggestions(list) }
        }
    }

    private fun showSuggestions(list: List<String>) {
        suggestRow.removeAllViews()
        list.forEach { s ->
            suggestRow.addView(key(s, small = true) { submit(s, true) }, keyLp(1f))
        }
        if (list.isEmpty()) suggestRow.addView(View(context), keyLp(1f))
    }

    private fun row(keys: String, pad: Float = 0f) {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            if (pad > 0) addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, pad))
            keys.forEach { c -> addView(key(c.toString()) { type(c.toString()) }, keyLp(1f)) }
            if (pad > 0) addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, pad))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun keyLp(w: Float) = LayoutParams(0, LayoutParams.MATCH_PARENT, w).apply {
        setMargins(dp(3), dp(3), dp(3), dp(3))
    }

    private fun key(label: String, accent: Boolean = false, small: Boolean = false, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 16f else 21f)
            if (accent || small) typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
            background = if (accent) context.pressable(0xFF1F8F83.toInt(), 0xFF2BB3A3.toInt(), 8)
            else if (small) context.pressable(0xFF1C3533.toInt(), 0xFF2A4A47.toInt(), 18)
            else context.pressable(0xFF2A2D2F.toInt(), 0xFF4A4F53.toInt(), 8)
            setOnClickListener { onClick() }
        }

    private fun iconKey(icon: Int, onLong: (() -> Unit)? = null, onClick: () -> Unit) =
        ImageView(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = context.pressable(0xFF3A3E41.toInt(), 0xFF55595D.toInt(), 8)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
}
