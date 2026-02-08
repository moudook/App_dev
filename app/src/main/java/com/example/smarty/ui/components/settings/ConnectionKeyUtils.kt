package com.example.smarty.ui.components.settings

fun maskConnectionKey(key: String?): String {
    if (key == null || key.length < 8) return "****"
    return key.take(4) + "****" + key.takeLast(4)
}
