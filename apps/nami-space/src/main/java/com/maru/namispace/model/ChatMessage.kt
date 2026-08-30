package com.maru.namispace.model

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val suggestedReplies: List<String> = emptyList(),
    val durationMs: Long? = null,
)
