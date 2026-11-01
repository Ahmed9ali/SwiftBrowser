package com.swift.browser

data class BrowserTab(
    val id: Long,
    val url: String = "swift://home",
    val title: String = "New tab",
    val blockedCount: Int = 0
)

data class BrowserPreferences(
    val blockingEnabled: Boolean = true,
    val privateMode: Boolean = false,
    val homePage: String = "https://search.brave.com"
)
