package com.codexserver.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class AppFont(val key: String, val label: String, val description: String) {
    System("system", "跟随系统", "使用手机当前的默认字体"),
    MiSans("misans", "MiSans 小米字体", "清爽利落"),
    NotoSans("noto_sans_sc", "Noto Sans 简体中文", "规整耐看"),
    NotoSerif("noto_serif_sc", "Noto Serif 简体中文", "书卷气息"),
    WenKai("lxgw_wenkai_screen", "霞鹜文楷屏幕阅读版", "柔和的手写韵味");

    companion object {
        fun fromKey(value: String?): AppFont = entries.firstOrNull { it.key == value } ?: System
    }
}

/** App-local appearance; switching fonts never changes the connection or conversation state. */
internal class AppFontSelection(read: () -> String?, private val write: (String) -> Unit) {
    var selected by mutableStateOf(AppFont.fromKey(read()))
        private set

    fun select(font: AppFont) {
        if (selected == font) return
        write(font.key)
        selected = font
    }
}
