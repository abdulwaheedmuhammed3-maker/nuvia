package com.nuvia.app.data

data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val message: String,
    val postedAt: Long,
    val hasActions: Boolean
)
