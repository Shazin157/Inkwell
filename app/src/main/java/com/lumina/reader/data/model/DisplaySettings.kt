package com.lumina.reader.data.model

data class DisplaySettings(
    var theme: String = "paperwhite", // "paperwhite", "warm_light", "sepia", "sand", "charcoal", "oled"
    var fontFamily: String = "serif", // "serif", "sans", "mono"
    var fontSize: Int = 18, // sp
    var lineSpacing: Float = 1.6f,
    var margin: Int = 20, // dp
    var dimmerPercent: Int = 0, // 0..85
    var keepScreenAwake: Boolean = true,
    var readerName: String = "Reader",
    var einkMatteOverlay: Boolean = true,
    var einkWarmthPercent: Int = 12, // 0..100% Kindle warm frontlight
    var einkPageRefreshFlash: Boolean = false
)
