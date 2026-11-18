package moe.ono.hooks.item.developer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
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
import android.graphics.drawable.GradientDrawable

/**
 * Per-view style editor. Changes stay local to the selected QQ control until restored.
 * It deliberately edits text views and drawable tints, not image payloads or click logic.
 */
internal object QQInspectorStyleEditor {
    private data class Field(val enabled: CheckBox, val input: EditText)

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
        fun field(title: String, hint: String, initial: String, inputType: Int, multiline: Boolean = false): Field {
            val enabled = CheckBox(activity).apply {
                text = title
                textSize = 13f
                setTextColor(0xFF19334D.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF168DCE.toInt())
            }
            val input = EditText(activity).apply {
                this.hint = hint
                setText(initial)
                textSize = 13f
                setTextColor(0xFF1D344B.toInt())
                setPadding(dp(11), dp(8), dp(11), dp(8))
                this.inputType = inputType
                if (multiline) {
                    minLines = 2
                    maxLines = 4
                    gravity = Gravity.TOP or Gravity.START
                } else {
                    setSingleLine(true)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(dp(1), 0xFFD3E2EF.toInt())
                }
            }
            return Field(enabled, input)
        }
        val isText = target is TextView && target !is EditText &&
            target.transformationMethod !is PasswordTransformationMethod
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
            text = "只修改选中的控件；可同时应用多项样式。文字同步仅作用于同类 TextView，不会复制 ImageView 的图片内容。"
            textSize = 11f
            setTextColor(0xFF60758B.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val textField = if (isText) field(
            "修改显示文字",
            "输入新的显示内容（可留空以清空）",
            "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            multiline = true
        ) else null
        textField?.let {
            content.addView(it.enabled)
            content.addView(it.input)
            content.addView(label("最多 120 个字符；不会读取或预填当前聊天文字"))
        }

        val textColorField = if (isText) field(
            "修改文字颜色",
            "#FFFFFFFF",
            "#FFFFFFFF",
            InputType.TYPE_CLASS_TEXT
        ) else null
        textColorField?.let {
            content.addView(it.enabled)
            content.addView(it.input)
        }

        val backgroundField = field(
            "修改背景颜色",
            "#FF1888CC",
            "#FF1888CC",
            InputType.TYPE_CLASS_TEXT
        )
        content.addView(backgroundField.enabled)
        content.addView(backgroundField.input)
        content.addView(label("保留原控件的圆角与背景形状；无背景的控件会创建纯色背景"))

        val sizeField = if (isText) field(
            "修改文字大小（sp）",
            "例如 14",
            ((target as TextView).textSize / density).toInt().toString(),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        ) else null
        sizeField?.let {
            content.addView(it.enabled)
            content.addView(it.input)
        }

        val alphaField = field(
            "修改透明度（%）",
            "10 - 100",
            (target.alpha * 100f).toInt().coerceIn(10, 100).toString(),
            InputType.TYPE_CLASS_NUMBER
        )
        content.addView(alphaField.enabled)
        content.addView(alphaField.input)

        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("控件改变 · 样式编辑")
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
                fun parseColor(field: Field?, name: String): Int? {
                    if (field?.enabled?.isChecked != true) return null
                    return runCatching { Color.parseColor(field.input.text.toString().trim()) }
                        .getOrElse {
                            field.input.error = "$name 颜色格式无效，请使用 #RRGGBB 或 #AARRGGBB"
                            throw IllegalArgumentException("invalid color")
                        }
                }
                val textColor = try { parseColor(textColorField, "文字") } catch (_: IllegalArgumentException) { return@setOnClickListener }
                val backgroundColor = try { parseColor(backgroundField, "背景") } catch (_: IllegalArgumentException) { return@setOnClickListener }
                val newSize = if (sizeField?.enabled?.isChecked == true) {
                    sizeField.input.text.toString().trim().toFloatOrNull()?.takeIf { it in 6f..48f }
                        ?: run { sizeField.input.error = "文字大小需在 6 到 48sp 之间"; return@setOnClickListener }
                } else null
                val newAlpha = if (alphaField.enabled.isChecked) {
                    alphaField.input.text.toString().trim().toIntOrNull()?.takeIf { it in 10..100 }
                        ?: run { alphaField.input.error = "透明度需在 10 到 100 之间"; return@setOnClickListener }
                } else null
                val newText = if (textField?.enabled?.isChecked == true) {
                    textField.input.text?.toString()?.takeIf { it.length <= 120 }
                        ?: run { textField.input.error = "文字最多 120 个字符"; return@setOnClickListener }
                } else null

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
                (metrics.heightPixels * .78f).toInt().coerceAtMost(dp(680))
            )
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
    }
}
