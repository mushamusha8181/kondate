package jp.mushamusha.kyounokondate

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var secure: SecureStore
    private lateinit var store: LocalStore
    private lateinit var repository: MenuRepository
    private var requestSerial = 0
    private var settingsOpened = false
    private var filters = FilterState()
    private var current: RecipeCard? = null
    private var activeRequest: Future<*>? = null
    private var imageRequest: Future<*>? = null
    private var playerWebView: WebView? = null
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var nextButton: Button
    private lateinit var favoriteButton: Button
    private val chipButtons = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        secure = SecureStore(this)
        removeLegacyYouTubeKeyOnce()
        store = LocalStore(this)
        repository = MenuRepository(secure, store)
        restoreFilters(savedInstanceState)
        setContentView(buildUi())
        refreshChipSelection()

        val introSeen = getSharedPreferences("app_state", MODE_PRIVATE).getBoolean("intro_seen", false)
        val restoredId = savedInstanceState?.getString("current_video_id")
        val restoredCard = restoredId?.let { id -> store.history().firstOrNull { it.videoId == id } }
        when {
            !introSeen -> showIntro()
            restoredCard != null -> {
                current = restoredCard
                status.text = "表示を復元しました"
                render(restoredCard)
            }
            secure.has("deepseek_api_key") -> loadNext()
            else -> status.text = "設定からDeepSeek APIキーを登録してください。"
        }
    }

    private fun restoreFilters(state: Bundle?) {
        if (state == null) return
        filters = FilterState(
            minutes = state.getInt("filter_minutes", 3),
            category = state.getString("filter_category") ?: "主菜",
            method = state.getString("filter_method") ?: "なんでも",
            displayMode = state.getString("filter_display") ?: "動画",
            includeIngredients = state.getString("filter_include").orEmpty(),
            excludeIngredients = state.getString("filter_exclude").orEmpty()
        )
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        applySystemBarInsets(root, dp(10), dp(8), dp(10), dp(8))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "今日の献立"
                textSize = 20f
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        header.addView(smallTapButton("♡", "お気に入り") { showFavorites() })
        header.addView(smallTapButton("履歴", "閲覧履歴") { showHistory() })
        header.addView(smallTapButton("⚙", "設定") { openSettings() })
        root.addView(header)
        root.addView(filterStrip())

        status = TextView(this).apply {
            textSize = 13f
            setPadding(dp(2), dp(6), dp(2), dp(6))
            text = "条件に合う献立を探します"
        }
        root.addView(status)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        favoriteButton = Button(this).apply {
            text = "♡"
            contentDescription = "お気に入りに追加"
            minHeight = dp(48)
            isEnabled = false
            setOnClickListener {
                current?.let { card ->
                    card.favorite = store.toggleFavorite(card)
                    text = if (card.favorite) "♥" else "♡"
                    contentDescription = if (card.favorite) "お気に入りから削除" else "お気に入りに追加"
                }
            }
        }
        bottom.addView(favoriteButton, LinearLayout.LayoutParams(dp(60), dp(48)))

        nextButton = Button(this).apply {
            text = "次へ"
            minHeight = dp(48)
            setOnClickListener { loadNext() }
        }
        bottom.addView(
            nextButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
        )

        bottom.addView(
            Button(this).apply {
                text = "食材"
                minHeight = dp(48)
                setOnClickListener { showIngredientDialog() }
            },
            LinearLayout.LayoutParams(dp(68), dp(48))
        )
        root.addView(bottom)
        return root
    }

    private fun filterStrip(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(text: String, onClick: () -> Unit) { row.addView(compactChip(text, onClick)) }

        listOf(3, 5, 10).forEach { minutes ->
            add("${minutes}分") {
                updateFilters(filters.copy(minutes = minutes))
            }
        }
        listOf("主菜", "副菜", "丼", "麺", "ご飯", "おつまみ").forEach { category ->
            add(category) {
                updateFilters(filters.copy(category = category))
            }
        }
        listOf("なんでも", "レンジ", "フライパン", "火なし").forEach { method ->
            add(method) {
                updateFilters(filters.copy(method = method))
            }
        }
        listOf("動画", "画像").forEach { displayMode ->
            add(displayMode) {
                updateFilters(filters.copy(displayMode = displayMode))
            }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun updateFilters(next: FilterState) {
        if (next == filters) return
        val searchChanged = next.searchKey() != filters.searchKey()
        filters = next
        refreshChipSelection()
        if (searchChanged) {
            repository.clearQueues()
            loadNext()
        } else {
            render(current)
        }
    }

    private fun refreshChipSelection() {
        chipButtons.forEach { button ->
            val text = button.text.toString()
            button.isSelected = text == "${filters.minutes}分" ||
                text == filters.category ||
                text == filters.method ||
                text == filters.displayMode
            button.alpha = if (button.isSelected) 1f else 0.68f
        }
    }

    private fun compactChip(text: String, onClick: () -> Unit): View {
        val button = Button(this).apply {
            this.text = text
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = false
            isFocusable = false
        }
        chipButtons += button
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                marginEnd = dp(2)
            }
            isClickable = true
            isFocusable = true
            contentDescription = text
            setOnClickListener { onClick() }
            addView(
                button,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(36), Gravity.CENTER)
            )
        }
    }

    private fun smallTapButton(text: String, description: String, onClick: () -> Unit): View {
        val button = Button(this).apply {
            this.text = text
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = false
            isFocusable = false
        }
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
            addView(button, FrameLayout.LayoutParams(dp(44), dp(40), Gravity.CENTER))
        }
    }

    private fun loadNext() {
        cancelPendingSearch()
        val serial = ++requestSerial
        val snapshot = filters
        status.text = "候補を探しています…"
        nextButton.isEnabled = false
        favoriteButton.isEnabled = false
        activeRequest = searchExecutor.submit {
            val result = runCatching { repository.next(snapshot) }
            main.post {
                if (serial != requestSerial || isFinishing || isDestroyed) return@post
                nextButton.isEnabled = true
                result.onSuccess { card ->
                    current = card
                    status.text = if (card == null) "候補がありません。条件を変えてください。" else ""
                    render(card)
                }.onFailure { error ->
                    status.text = error.message ?: "献立の取得に失敗しました"
                    render(null)
                }
            }
        }
    }

    private fun render(card: RecipeCard?) {
        content.removeAllViews()
        favoriteButton.isEnabled = card != null
        if (card == null) {
            content.addView(TextView(this).apply {
                text = "候補がありません"
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, 0)
            })
            return
        }
        favoriteButton.text = if (card.favorite) "♥" else "♡"
        favoriteButton.contentDescription = if (card.favorite) "お気に入りから削除" else "お気に入りに追加"
        addSection(card.title, listOf(card.description), false)
        addSection("材料", card.ingredients, true)
        addSection("作り方", card.steps, true)
        addSection("ポイント", card.tips, true)
        if (filters.displayMode == "動画" && card.videoUrl.isNotBlank()) {
            content.addView(videoView(card.videoUrl))
        } else if (card.imageUrl.isNotBlank()) {
            content.addView(imageView(card.imageUrl))
        }
    }

    private fun videoView(url: String): View {
        playerWebView?.destroy()
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            loadUrl(url)
        }
        playerWebView = web
        return web
    }

    private fun imageView(url: String): View {
        val view = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        imageRequest?.cancel(true)
        imageRequest = imageExecutor.submit {
            val bitmap = runCatching {
                java.net.URL(url).openConnection().apply { connectTimeout = 5000; readTimeout = 7000 }
                    .getInputStream().use(BitmapFactory::decodeStream)
            }.getOrNull()
            main.post {
                if (!isFinishing && !isDestroyed && bitmap != null) view.setImageBitmap(bitmap)
            }
        }
        return view
    }

    private fun addSection(title: String, lines: List<String>, bullets: Boolean) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setPadding(0, dp(10), 0, dp(3))
        })
        lines.forEach { line ->
            content.addView(TextView(this).apply {
                text = if (bullets) "・$line" else line
                textSize = 14f
                setPadding(dp(4), dp(1), 0, dp(1))
            })
        }
    }

    private fun showIngredientDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
        }
        val include = EditText(this).apply {
            hint = "使いたい食材（例: 卵, 豆腐）"
            setText(this@MainActivity.filters.includeIngredients)
        }
        val exclude = EditText(this).apply {
            hint = "使わない食材（例: にんにく）"
            setText(this@MainActivity.filters.excludeIngredients)
        }
        box.addView(include)
        box.addView(exclude)
        AlertDialog.Builder(this)
            .setTitle("食材条件")
            .setView(box)
            .setPositiveButton("適用") { _, _ ->
                updateFilters(
                    filters.copy(
                        includeIngredients = parseIngredientTerms(include.text.toString()).joinToString(", "),
                        excludeIngredients = parseIngredientTerms(exclude.text.toString()).joinToString(", ")
                    )
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showFavorites() {
        val favorites = store.favorites()
        showCardListDialog("お気に入り", favorites)
    }

    private fun showHistory() {
        val history = store.history()
        showCardListDialog("閲覧履歴", history)
    }

    private fun showCardListDialog(title: String, cards: List<RecipeCard>) {
        if (cards.isEmpty()) {
            Toast.makeText(this, "$title はまだありません", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = cards.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which ->
                current = cards[which]
                render(current)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showIntro() {
        AlertDialog.Builder(this)
            .setTitle("今日の献立")
            .setMessage("条件を選ぶだけで、短時間で作れる献立候補を探します。設定からDeepSeek APIキーを登録すると利用できます。")
            .setPositiveButton("はじめる") { _, _ ->
                getSharedPreferences("app_state", MODE_PRIVATE).edit().putBoolean("intro_seen", true).apply()
                if (secure.has("deepseek_api_key")) loadNext() else openSettings()
            }
            .setCancelable(false)
            .show()
    }

    private fun openSettings() {
        if (settingsOpened) return
        settingsOpened = true
        val key = EditText(this).apply {
            hint = "DeepSeek APIキー"
            setText(secure.get("deepseek_api_key").orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setView(key)
            .setPositiveButton("保存") { _, _ ->
                val value = key.text.toString().trim()
                if (value.isBlank()) secure.remove("deepseek_api_key") else secure.put("deepseek_api_key", value)
                settingsOpened = false
                if (value.isNotBlank()) loadNext()
            }
            .setNegativeButton("キャンセル") { _, _ -> settingsOpened = false }
            .setOnCancelListener { settingsOpened = false }
            .show()
    }

    private fun removeLegacyYouTubeKeyOnce() {
        val prefs = getSharedPreferences("migration", MODE_PRIVATE)
        if (prefs.getBoolean("youtube_key_removed", false)) return
        secure.remove("youtube_api_key")
        prefs.edit().putBoolean("youtube_key_removed", true).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("filter_minutes", filters.minutes)
        outState.putString("filter_category", filters.category)
        outState.putString("filter_method", filters.method)
        outState.putString("filter_display", filters.displayMode)
        outState.putString("filter_include", filters.includeIngredients)
        outState.putString("filter_exclude", filters.excludeIngredients)
        outState.putString("current_video_id", current?.videoId)
    }

    override fun onStop() {
        if (isFinishing) cancelPendingSearch()
        super.onStop()
    }

    override fun onDestroy() {
        cancelPendingSearch()
        imageRequest?.cancel(true)
        playerWebView?.stopLoading()
        playerWebView?.destroy()
        playerWebView = null
        searchExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun cancelPendingSearch() {
        requestSerial++
        activeRequest?.cancel(true)
        activeRequest = null
    }

    private fun applySystemBarInsets(view: View, l: Int, t: Int, r: Int, b: Int) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
