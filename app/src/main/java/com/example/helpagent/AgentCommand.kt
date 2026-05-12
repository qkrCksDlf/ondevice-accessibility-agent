package com.example.helpagent

data class AgentCommand(
    val intent: String,
    val stage: String = "",
    val systemAction: String = "",
    val query: String,
    val confirmationText: String = "",
    val autoMode: Boolean = false  // 🌟 자동 모드 플래그
)