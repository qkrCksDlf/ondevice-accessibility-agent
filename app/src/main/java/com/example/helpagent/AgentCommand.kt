// AgentCommand.kt 파일 안의 정확한 모습
package com.example.helpagent

data class AgentCommand(
    val intent: String,
    val stage: String,
    val systemAction: String,
    val query: String,
    val confirmationText: String = "" // 여기 대문자 T 확인!
)