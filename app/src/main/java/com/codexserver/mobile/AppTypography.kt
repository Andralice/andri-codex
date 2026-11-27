package com.codexserver.mobile

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontWeight

internal val LocalAppFont = staticCompositionLocalOf { AppFont.System }
internal val LocalAppFontSelection = staticCompositionLocalOf<AppFontSelection> {
    error("App font settings must be provided by AppTheme")
}

// Font resources are bundled with the app and need no network or server connection.
private fun regularAndBoldFamily(regular: Int, bold: Int) = FontFamily(
    Font(regular, FontWeight.Normal, loadingStrategy = FontLoadingStrategy.Async),
    Font(bold, FontWeight.Bold, loadingStrategy = FontLoadingStrategy.Async)
)

private val miSansFamily by lazy { regularAndBoldFamily(R.font.misans_400, R.font.misans_700) }
private val notoSansFamily by lazy { regularAndBoldFamily(R.font.noto_sans_sc_400, R.font.noto_sans_sc_700) }
private val notoSerifFamily by lazy { regularAndBoldFamily(R.font.noto_serif_sc_400, R.font.noto_serif_sc_700) }
private val wenKaiFamily by lazy { FontFamily(Font(R.font.lxgw_wenkai_screen, loadingStrategy = FontLoadingStrategy.Async)) }

internal val AppFont.fontFamily: FontFamily
    get() = when (this) {
        AppFont.System -> FontFamily.Default
        AppFont.MiSans -> miSansFamily
        AppFont.NotoSans -> notoSansFamily
        AppFont.NotoSerif -> notoSerifFamily
        AppFont.WenKai -> wenKaiFamily
    }

internal val AppFont.headingFontFamily: FontFamily
    get() = if (this == AppFont.System) FontFamily.Serif else fontFamily

internal fun appTypography(family: FontFamily): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}

@Composable
internal fun AppTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val selection = remember(context) {
        val settings = context.getSharedPreferences("appearance", 0)
        AppFontSelection(
            read = { settings.getString("font", null) },
            write = { settings.edit().putString("font", it).apply() }
        )
    }
    val font = selection.selected
    val typography = remember(font) { appTypography(font.fontFamily) }
    CompositionLocalProvider(LocalAppFont provides font, LocalAppFontSelection provides selection) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
    }
}
