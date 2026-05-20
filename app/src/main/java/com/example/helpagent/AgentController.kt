package com.example.helpagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AgentController {
    private val _commandFlow = MutableSharedFlow<AgentCommand>(extraBufferCapacity = 1)
    val commandFlow = _commandFlow.asSharedFlow()

    private val _resultFlow = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    val resultFlow = _resultFlow.asSharedFlow()

    fun sendCommand(command: AgentCommand) = _commandFlow.tryEmit(command)
    fun sendResults(results: List<String>) = _resultFlow.tryEmit(results)
}