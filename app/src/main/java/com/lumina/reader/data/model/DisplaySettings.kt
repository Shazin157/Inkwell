package com.lumina.reader.data.model

data class DisplaySettings(
    var theme: String = "paperwhite", // Strictly 4 presets: "paperwhite", "sand", "charcoal", "oled"
    var fontFamily: String = "serif", // "serif", "sans", "mono"
    var fontSize: Int = 18, // sp
    var lineSpacing: Float = 1.6f, // 1.3f, 1.6f, 2.0f
    var margin: Int = 20, // 12, 20, 36 dp
    var dimmerPercent: Int = 0, // 0..85
    var keepScreenAwake: Boolean = true,
    var readerName: String = "Reader",
    var appThemeMode: String = "system", // "system", "light", "dark"
    var einkMatteOverlay: Boolean = true,
    var einkPageRefreshFlash: Boolean = false,
    var pdfFitMode: String = "screen", // "screen" (default whole screen), "width", "page"
    var showJumpBackInWidget: Boolean = true
)
