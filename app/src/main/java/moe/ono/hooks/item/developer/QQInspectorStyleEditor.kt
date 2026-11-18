package moe.ono.hooks.item.developer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Per-view style editor with curated color swatches. No manual HEX input is needed.
 * It deliberately edits view appearance, not click listeners, images or network content.
 */
internal object QQInspectorStyleEditor {
    private data class PaletteUi(
        val view: View,
        val enabled: CheckBox,
        val selectedColor: () -> Int?
    )

    private data class ColorChoice(val name: String, val color: Int)

    private val palette = listOf(
        ColorChoice("冰川蓝", 0xFF55C7F2.toInt()),
        ColorChoice("天际蓝", 0xFF2196F3.toInt()),
        ColorChoice("深海蓝", 0xFF145B9B.toInt()),
        ColorChoice("青蓝", 0xFF00BCD4.toInt()),
        ColorChoice("薄荷绿", 0xFF4DB6AC.toInt()),
        ColorChoice("翠绿", 0xFF2EAD69.toInt()),
        ColorChoice("荧光绿", 0xFF8BC34A.toInt()),
        ColorChoice("柠檬黄", 0xFFFFD54F.toInt()),
        ColorChoice("琥珀橙", 0xFFFF9800.toInt()),
        ColorChoice("珊瑚红", 0xFFFF6B6B.toInt()),
        ColorChoice("玫红", 0xFFE91E63.toInt()),
        ColorChoice("薰衣草", 0xFF9C8AE8.toInt()),
        ColorChoice("紫罗兰", 0xFF673AB7.toInt()),
        ColorChoice("石墨灰", 0xFF455A64.toInt()),
        ColorChoice("银灰", 0xFFB0BEC5.toInt()),
        ColorChoice("纯白", 0xFFFFFFFF.toInt()),
        ColorChoice("纯黑", 0xFF101820.toInt()),
        ColorChoice("透明", 0x00000000)
    )

    fun show(activity: Activity, target: View, onApplied: (Int) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed || target.parent == null) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun label(value: String) = TextView(activity).apply {
            text = value
            textSize = 11f
            setTextColor(0xFF49627C.toInt())
            setPadding(dp(3), dp(9), dp(3), dp(3))
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFFF1F6FB.toInt())
                setStroke(dp(1), 0xFFDAE7F1.toInt())
            }
        }
        content.addView(TextView(activity).apply {
            text = "勾选要修改的项目，再直接点选预设色块。修改会保存在 TNT 本地；有唯一稳定资源 ID 的控件可在 QQ 页面重建后自动恢复。"
            textSize = 11f
            setTextColor(0xFF60758B.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        })

        fun textField(title: String, hint: String, inputType: Int, multiline: Boolean = false): Pair<CheckBox, EditText> {
            val enabled = CheckBox(activity).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF19334D.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF168DCE.toInt())
            }
            val input = EditText(activity).apply {
                this.hint = hint
                textSize = 13f
                setTextColor(0xFF1D344B.toInt())
                setPadding(dp(11), dp(8), dp(11), dp(8))
                this.inputType = inputType
                if (multiline) {
                    minLines = 2
                    maxLines = 4
                    gravity = Gravity.TOP or Gravity.START
                } else setSingleLine(true)
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dp(1), 0xFFD3E2EF.toInt())
                }
            }
            return enabled to input
        }

        fun paletteField(title: String, current: Int, defaultColor: Int): PaletteUi {
            val enabled = CheckBox(activity).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF19334D.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF168DCE.toInt())
            }
            var selected: Int? = palette.firstOrNull { it.color == current }?.color
                ?: palette.firstOrNull { it.color == defaultColor }?.color
            val section = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }
            val chips = ArrayList<Pair<TextView, ColorChoice>>()

            fun contrastColor(color: Int): Int {
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                return if (Color.alpha(color) < 80 || luminance > 164) 0xFF20364A.toInt() else Color.WHITE
            }

            fun refresh() {
                chips.forEach { (chip, choice) ->
                    val chosen = selected == choice.color
                    chip.background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(if (choice.color == Color.TRANSPARENT) 0xFFE6EBF1.toInt() else choice.color)
                        setStroke(dp(if (chosen) 2 else 1), if (chosen) 0xFF087DBB.toInt() else 0x553D6385)
                    }
                    chip.setTextColor(contrastColor(choice.color))
                    chip.text = (if (chosen) "✓ " else "") + choice.name
                    chip.alpha = if (chosen) 1f else 0.88f
                }
            }

            palette.chunked(3).forEach { rowChoices ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                rowChoices.forEach { choice ->
                    val chip = TextView(activity).apply {
                        textSize = 10.5f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        includeFontPadding = false
                        setPadding(dp(3), dp(8), dp(3), dp(8))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            selected = choice.color
                            enabled.isChecked = true
                            refresh()
                        }
                    }
                    chips.add(chip to choice)
                    row.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                        if (row.childCount > 0) marginStart = dp(5)
                    })
                }
                repeat(3 - rowChoices.size) {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                        if (row.childCount > 0) marginStart = dp(5)
                    })
                }
                section.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) })
            }
            refresh()
            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(enabled)
                addView(section)
            }
            return PaletteUi(wrapper, enabled) { selected }
        }

        val isText = target is TextView && target !is EditText &&
            target.transformationMethod !is PasswordTransformationMethod
        var textInput: EditText? = null
        var textEnabled: CheckBox? = null
        if (isText) {
            val pair = textField(
                "修改显示文字",
                "输入新的显示内容（最多 120 字）",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                multiline = true
            )
            textEnabled = pair.first
            textInput = pair.second
            content.addView(pair.first)
            content.addView(pair.second)
            content.addView(label("不会自动读取或预填当前聊天文字"))
        }

        val textColorUi = if (isText) {
            paletteField("修改文字颜色", (target as TextView).textColors.defaultColor, 0xFFFFFFFF.toInt())
        } else null
        textColorUi?.let {
            content.addView(it.view)
            content.addView(label("文字颜色 · 预设色板"))
        }

        val currentBackground = target.backgroundTintList?.defaultColor
            ?: (target.background as? android.graphics.drawable.ColorDrawable)?.color
            ?: 0xFF1888CC.toInt()
        val backgroundUi = paletteField("修改背景颜色", currentBackground, 0xFF1888CC.toInt())
        content.addView(backgroundUi.view)
        content.addView(label("保留原控件圆角和背景形状；无背景控件会创建纯色背景"))

        val sizeEnabled: CheckBox?
        val sizeInput: EditText?
        if (isText) {
            val pair = textField(
                "修改文字大小（sp）",
                "例如 14",
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            )
            sizeEnabled = pair.first
            sizeInput = pair.second
            pair.second.setText(((target as TextView).textSize / density).toInt().toString())
            content.addView(sizeEnabled)
            content.addView(sizeInput)
        } else {
            sizeEnabled = null
            sizeInput = null
        }

        val alphaPair = textField(
            "修改透明度（%）",
            "10 - 100",
            InputType.TYPE_CLASS_NUMBER
        )
        val alphaEnabled = alphaPair.first
        val alphaInput = alphaPair.second
        alphaInput.setText((target.alpha * 100f).toInt().coerceIn(10, 100).toString())
        content.addView(alphaEnabled)
        content.addView(alphaInput)

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("控件改变 · 预设样式")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("应用更改", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (target.parent == null) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val newSize = if (sizeEnabled?.isChecked == true) {
                    sizeInput?.text?.toString()?.trim()?.toFloatOrNull()?.takeIf { it in 6f..48f }
                        ?: run { sizeInput?.error = "文字大小需在 6 到 48sp 之间"; return@setOnClickListener }
                } else null
                val newAlpha = if (alphaEnabled.isChecked) {
                    alphaInput.text.toString().trim().toIntOrNull()?.takeIf { it in 10..100 }
                        ?: run { alphaInput.error = "透明度需在 10 到 100 之间"; return@setOnClickListener }
                } else null
                val newText = if (textEnabled?.isChecked == true) {
                    textInput?.text?.toString()?.takeIf { it.length <= 120 }
                        ?: run { textInput?.error = "文字最多 120 个字符"; return@setOnClickListener }
                } else null

                val textColor = textColorUi?.takeIf { it.enabled.isChecked }?.selectedColor()
                val backgroundColor = backgroundUi.takeIf { it.enabled.isChecked }?.selectedColor()
                var changed = 0
                if (newText != null && QQInspectorGeometry.setText(target, newText)) changed++
                if (textColor != null && QQInspectorGeometry.setTextColor(target, textColor)) changed++
                if (backgroundColor != null && QQInspectorGeometry.setBackgroundColor(target, backgroundColor)) changed++
                if (newSize != null && QQInspectorGeometry.setTextSizeSp(target, newSize)) changed++
                if (newAlpha != null && QQInspectorGeometry.setAlpha(target, newAlpha / 100f)) changed++
                QQInspectorGeometry.applySameClassFollow(target)
                dialog.dismiss()
                onApplied(changed)
            }
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xFFF7FAFD.toInt())
                setStroke(dp(1), 0x9957BFE5.toInt())
            })
            val metrics = activity.resources.displayMetrics
            window.setLayout(
                (metrics.widthPixels * .92f).toInt().coerceAtMost(dp(500)),
                (metrics.heightPixels * .78f).toInt().coerceAtMost(dp(700))
            )
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
    }
}
