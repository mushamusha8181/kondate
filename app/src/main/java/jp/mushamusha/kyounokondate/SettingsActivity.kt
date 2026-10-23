package jp.mushamusha.kyounokondate

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var secure: SecureStore
    private lateinit var store: LocalStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        secure = SecureStore(this)
        store = LocalStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        applySystemBarInsets(root, dp(16), dp(12), dp(16), dp(16))
        root.addView(TextView(this).apply {
            text = "設定"
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "DeepSeek APIキーだけを使用します。キーは端末内で暗号化して保存し、保存済みの値は画面へ再表示しません。"
            textSize = 13f
        })
        addKeyRow(root)
        root.addView(Button(this).apply {
            text = "閲覧済み判定をリセット"
            minHeight = dp(48)
            setOnClickListener {
                store.clearSeen()
                Toast.makeText(this@SettingsActivity, "閲覧済み判定をリセットしました", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(Button(this).apply {
            text = "戻る"
            minHeight = dp(48)
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addKeyRow(root: LinearLayout) {
        val key = "deepseek_api_key"
        val status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(14), 0, dp(4))
        }
        fun refreshStatus() {
            status.text = "DeepSeek APIキー" + if (secure.has(key)) "（設定済み）" else "（未設定）"
        }
        refreshStatus()
        root.addView(status)

        val input = EditText(this).apply {
            hint = "新しいキーを入力"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            minHeight = dp(48)
        }
        root.addView(input)
        root.addView(TextView(this).apply {
            text = "YouTube候補のWeb検索と、調理時間・ジャンル・材料・手順の厳格判定に使います。YouTube APIキーやGoogle Cloud設定は不要です。"
            textSize = 12f
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            Button(this).apply {
                text = "保存"
                minHeight = dp(48)
                setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isBlank()) {
                        Toast.makeText(this@SettingsActivity, "キーを入力してください", Toast.LENGTH_SHORT).show()
                    } else {
                        runCatching { secure.put(key, value) }
                            .onSuccess {
                                input.text.clear()
                                refreshStatus()
                                Toast.makeText(this@SettingsActivity, "保存しました", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                Toast.makeText(this@SettingsActivity, "保存に失敗しました", Toast.LENGTH_LONG).show()
                            }
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        buttons.addView(
            Button(this).apply {
                text = "削除"
                minHeight = dp(48)
                setOnClickListener {
                    secure.remove(key)
                    input.text.clear()
                    refreshStatus()
                    Toast.makeText(this@SettingsActivity, "削除しました", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
        )
        root.addView(buttons)
    }

    private fun applySystemBarInsets(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
