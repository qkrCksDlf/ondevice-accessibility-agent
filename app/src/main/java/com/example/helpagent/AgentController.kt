// AgentController.kt 안의 정확한 코드

package com.example.helpagent // 맨 윗줄 패키지명도 MainActivity와 똑같은지 확인!

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// 주의: class가 아니라 object 입니다!
object AgentController {
    // UI -> Service 명령 전달
    private val _commandFlow = MutableSharedFlow<AgentCommand>(extraBufferCapacity = 1)
    val commandFlow = _commandFlow.asSharedFlow()

    // Service -> UI 결과 전달 (상품 리스트 전송용)
    private val _resultFlow = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    val resultFlow = _resultFlow.asSharedFlow()

    fun sendCommand(command: AgentCommand) = _commandFlow.tryEmit(command)
    fun sendResults(results: List<String>) = _resultFlow.tryEmit(results)
}