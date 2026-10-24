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
        nextButton.isEnabled = false
        favoriteButton.isEnabled = false
        favoriteButton.text = "♡"
        current = null
        status.text = "検索中…"
        clearContent()

        activeRequest = searchExecutor.submit {
            val result = runCatching { repository.next(snapshot) }
            if (Thread.currentThread().isInterrupted) return@submit
            main.post {
                if (serial != requestSerial || isFinishing || isDestroyed) return@post
                activeRequest = null
                nextButton.isEnabled = true
                result.onSuccess { card ->
                    current = card
                    if (card == null) {
                        favoriteButton.isEnabled = false
                        status.text = "この検索範囲では献立が見つかりませんでした。次へでもう少し探すか、条件を少し広げてください。"
                        render(null)
                    } else {
                        status.text = "条件を厳格に確認した献立です"
                        render(card)
                    }
                }.onFailure { error ->
                    favoriteButton.isEnabled = false
                    when (error) {
                        is MissingKey -> {
                            status.text = "${error.label}が未設定です"
                            openSettings()
                        }
                        is InterruptedException -> Unit
                        is HttpStatusException -> {
                            status.text = "DeepSeek通信に失敗しました（HTTP ${error.code}）。APIキー・残高・レート制限・通信状態を確認してください。"
                        }
                        is InvalidAiResponseException -> {
                            status.text = "DeepSeekの応答を確認できませんでした。同じ条件でもう一度お試しください。"
                        }
                        else -> {
                            status.text = "読み込みに失敗しました。通信状態またはAPI設定を確認してください。"
                            Toast.makeText(this, error.message ?: "エラー", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun render(card: RecipeCard?) {
        clearContent()
        if (card == null) {
            favoriteButton.isEnabled = false
            return
        }

        favoriteButton.isEnabled = true
        favoriteButton.text = if (card.favorite) "♥" else "♡"
        favoriteButton.contentDescription = if (card.favorite) "お気に入りから削除" else "お気に入りに追加"

        if (filters.displayMode == "動画") {
            val media = runCatching { buildPlayer(card.videoId) }
                .getOrElse {
                    status.text = "動画プレーヤーを初期化できないため画像表示に切り替えました"
                    buildThumbnail(card.thumbnailUrl)
                }
            content.addView(media)
        } else {
            content.addView(buildThumbnail(card.thumbnailUrl))
        }

        content.addView(TextView(this).apply {
            text = card.title
            textSize = 19f
            setPadding(0, dp(8), 0, dp(2))
        })

        val recipeTime = if (card.recipeMinutes > 0) "調理 約${card.recipeMinutes}分" else "調理時間 記録なし"
        content.addView(TextView(this).apply {
            text = "$recipeTime ・ YouTube"
            textSize = 12f
        })

        addSection("材料", card.ingredients, bullets = true)
        addSection("作り方", card.steps.mapIndexed { index, step -> "${index + 1}. $step" }, bullets = false)
        content.addView(TextView(this).apply {
            text = "判定根拠: ${card.evidence}"
            textSize = 11f
            setPadding(0, dp(8), 0, dp(6))
        })
        content.addView(Button(this).apply {
            text = "YouTubeで開く"
            minHeight = dp(48)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${card.videoId}"))
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this@MainActivity, "YouTubeを開けるアプリが見つかりません", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun buildPlayer(id: String): View {
        val webView = WebView(this)
        try {
            webView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, playerHeight())
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = true
            webView.settings.allowFileAccess = false
            webView.settings.allowContentAccess = false
            webView.settings.javaScriptCanOpenWindowsAutomatically = false
            webView.settings.setGeolocationEnabled(false)
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webView.settings.safeBrowsingEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null || !request.isForMainFrame || !request.hasGesture()) return false
                    val uri = request.url
                    val host = uri.host?.lowercase().orEmpty()
                    val isYouTube = host == "youtube.com" || host.endsWith(".youtube.com") ||
                        host == "youtu.be" || host.endsWith(".youtu.be")
                    if (uri.scheme == "https" && isYouTube) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }
            }
            val embedUrl = "https://www.youtube.com/embed/$id?playsinline=1&rel=0"
            webView.loadUrl(embedUrl, mapOf("Referer" to "https://$packageName"))
            playerWebView = webView
            return webView
        } catch (error: RuntimeException) {
            webView.destroy()
            throw error
        }
    }

    private fun buildThumbnail(url: String): View {
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, mediaHeight())
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "料理動画のサムネイル"
        }
        if (url.isBlank()) return image

        imageRequest?.cancel(true)
        imageRequest = imageExecutor.submit imageLoad@{
            val bytes = runCatching { Http.getBytes(url, "ytimg.com") }.getOrNull() ?: return@imageLoad
            if (Thread.currentThread().isInterrupted) return@imageLoad
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@imageLoad
            main.post {
                if (!isFinishing && !isDestroyed && image.isAttachedToWindow) image.setImageBitmap(bitmap)
            }
        }
        return image
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

    private fun showHistory() = showSavedCards(
        title = "閲覧履歴",
        cards = store.history().take(30),
        emptyMessage = "閲覧履歴はまだありません",
        selectedMessage = "閲覧履歴から表示しています"
    )

    private fun showFavorites() = showSavedCards(
        title = "お気に入り",
        cards = store.favorites(),
        emptyMessage = "お気に入りはまだありません",
        selectedMessage = "お気に入りから表示しています"
    )

    private fun showSavedCards(title: String, cards: List<RecipeCard>, emptyMessage: String, selectedMessage: String) {
        if (cards.isEmpty()) {
            Toast.makeText(this, emptyMessage, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(cards.map { it.title }.toTypedArray()) { _, which ->
                cancelPendingSearch()
                current = cards[which]
                status.text = selectedMessage
                render(current)
            }
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun showIntro() {
        AlertDialog.Builder(this)
            .setTitle("初回確認")
            .setMessage(
                "このアプリが必要とするAndroid権限はインターネット通信のみです。これは通常権限のためOSの許可ダイアログは表示されません。" +
                    "カメラ・位置情報・連絡先・ストレージ権限は要求しません。\n\n" +
                    "YouTube候補の検索と条件判定にはDeepSeek APIキーだけを使用します。YouTube APIキーやGoogle Cloud設定は不要です。\n\n" +
                    "検索条件とDeepSeek Searchで取得したYouTube検索情報は、厳格判定のためDeepSeekへ送信されます。"
            )
            .setPositiveButton("設定する") { _, _ ->
                getSharedPreferences("app_state", MODE_PRIVATE).edit().putBoolean("intro_seen", true).apply()
                openSettings()
            }
            .setNegativeButton("あとで") { _, _ ->
                getSharedPreferences("app_state", MODE_PRIVATE).edit().putBoolean("intro_seen", true).apply()
                status.text = "利用するには設定からDeepSeek APIキーを登録してください。"
            }
            .setCancelable(false)
            .show()
    }

    private fun openSettings() {
        cancelPendingSearch()
        settingsOpened = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun cancelPendingSearch() {
        requestSerial++
        activeRequest?.cancel(true)
        Http.cancelJsonRequests()
        activeRequest = null
        if (::nextButton.isInitialized) nextButton.isEnabled = true
    }

    private fun clearContent() {
        imageRequest?.cancel(true)
        Http.cancelBinaryRequests()
        imageRequest = null
        playerWebView?.let { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
        playerWebView = null
        if (::content.isInitialized) content.removeAllViews()
    }

    private fun mediaWidth(): Int {
        val measured = if (::content.isInitialized) content.width else 0
        val fallback = resources.displayMetrics.widthPixels - dp(20)
        return (if (measured > dp(160)) measured else fallback).coerceAtLeast(dp(240))
    }

    private fun mediaHeight(): Int =
        (mediaWidth() * 9f / 16f).toInt().coerceIn(dp(135), dp(220))

    private fun playerHeight(): Int =
        (mediaWidth() * 9f / 16f).toInt().coerceIn(dp(200), dp(240))

    private fun applySystemBarInsets(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    private fun removeLegacyYouTubeKeyOnce() {
        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        if (prefs.getBoolean("deepseek_only_migrated", false)) return
        secure.remove("youtube_api_key")
        prefs.edit().putBoolean("deepseek_only_migrated", true).apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("filter_minutes", filters.minutes)
        outState.putString("filter_category", filters.category)
        outState.putString("filter_method", filters.method)
        outState.putString("filter_display", filters.displayMode)
        outState.putString("filter_include", filters.includeIngredients)
        outState.putString("filter_exclude", filters.excludeIngredients)
        current?.videoId?.let { outState.putString("current_video_id", it) }
    }

    override fun onResume() {
        super.onResume()
        playerWebView?.onResume()
        if (settingsOpened) {
            settingsOpened = false
            repository.clearQueues()
            if (secure.has("deepseek_api_key")) {
                loadNext()
            } else {
                status.text = "DeepSeek APIキーを設定してください。"
            }
        }
    }

    override fun onPause() {
        playerWebView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        cancelPendingSearch()
        clearContent()
        searchExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }
}
