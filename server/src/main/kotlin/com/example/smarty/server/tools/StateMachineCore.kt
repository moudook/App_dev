package com.example.smarty.server.tools

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

sealed class DFAState {
    abstract val stateId: Int
    abstract val stateName: String
    abstract val isFinal: Boolean
    abstract val isError: Boolean
}

sealed class TransitionEvent {
    object SPAWN : TransitionEvent()
    object START : TransitionEvent()
    object PAUSE : TransitionEvent()
    object RESUME : TransitionEvent()
    object COMPLETE : TransitionEvent()
    object FAIL : TransitionEvent()
    object TIMEOUT : TransitionEvent()
    object BLOCK : TransitionEvent()
    object UNBLOCK : TransitionEvent()
    object RESET : TransitionEvent()
    object KILL : TransitionEvent()
    data class ERROR(val code: Int) : TransitionEvent()
    data class MESSAGE(val type: String) : TransitionEvent()
    data class TOOL_ACQUIRE(val tool: String) : TransitionEvent()
    data class TOOL_RELEASE(val tool: String) : TransitionEvent()
}

class DeterministicFiniteAutomaton<S : DFAState>(
    private val initialState: S,
    private val transitionTable: Map<S, Map<TransitionEvent, S>>,
    private val errorState: S
) {
    private val currentState = AtomicReference(initialState)
    private val stateHistory = mutableListOf<S>()
    private val eventHistory = mutableListOf<TransitionEvent>()
    
    val state: S get() = currentState.get()
    val history: List<Pair<S, TransitionEvent?>> get() {
        val result = mutableListOf<Pair<S, TransitionEvent?>>()
        result.add(initialState to null)
        stateHistory.zip(eventHistory).forEach { (s, e) -> result.add(s to e) }
        return result
    }
    
    fun transition(event: TransitionEvent): Boolean {
        val current = currentState.get()
        val nextState = transitionTable[current]?.get(event)
        
        if (nextState != null) {
            stateHistory.add(current)
            eventHistory.add(event)
            currentState.set(nextState)
            return true
        }
        return false
    }
    
    fun canTransition(event: TransitionEvent): Boolean {
        val current = currentState.get()
        return transitionTable[current]?.containsKey(event) == true
    }
    
    fun forceState(newState: S) {
        stateHistory.add(currentState.get())
        eventHistory.add(TransitionEvent.MESSAGE("force"))
        currentState.set(newState)
    }
    
    fun isInErrorState(): Boolean = currentState.get().isError
    fun isInFinalState(): Boolean = currentState.get().isFinal
    fun reset() {
        currentState.set(initialState)
        stateHistory.clear()
        eventHistory.clear()
    }
}

sealed class AgentDFAState(override val stateId: Int, override val stateName: String, override val isFinal: Boolean, override val isError: Boolean) : DFAState() {
    object CREATED : AgentDFAState(0, "CREATED", false, false)
    object INITIALIZING : AgentDFAState(1, "INITIALIZING", false, false)
    object READY : AgentDFAState(2, "READY", false, false)
    object RUNNING : AgentDFAState(3, "RUNNING", false, false)
    object PAUSED : AgentDFAState(4, "PAUSED", false, false)
    object BLOCKED : AgentDFAState(5, "BLOCKED", false, false)
    object WAITING_MESSAGE : AgentDFAState(6, "WAITING_MESSAGE", false, false)
    object COMPLETED : AgentDFAState(7, "COMPLETED", true, false)
    object FAILED : AgentDFAState(8, "FAILED", false, true)
    object TERMINATED : AgentDFAState(9, "TERMINATED", true, false)
}

sealed class ToolDFAState(override val stateId: Int, override val stateName: String, override val isFinal: Boolean, override val isError: Boolean) : DFAState() {
    object FREE : ToolDFAState(0, "FREE", false, false)
    object RESERVED : ToolDFAState(1, "RESERVED", false, false)
    object ACQUIRED : ToolDFAState(2, "ACQUIRED", false, false)
    object EXECUTING : ToolDFAState(3, "EXECUTING", false, false)
    object RELEASING : ToolDFAState(4, "RELEASING", false, false)
    object ERROR : ToolDFAState(5, "ERROR", false, true)
}

sealed class MessageDFAState(override val stateId: Int, override val stateName: String, override val isFinal: Boolean, override val isError: Boolean) : DFAState() {
    object PENDING : MessageDFAState(0, "PENDING", false, false)
    object QUEUED : MessageDFAState(1, "QUEUED", false, false)
    object DELIVERING : MessageDFAState(2, "DELIVERING", false, false)
    object DELIVERED : MessageDFAState(3, "DELIVERED", true, false)
    object FAILED : MessageDFAState(4, "FAILED", false, true)
    object ACKNOWLEDGED : MessageDFAState(5, "ACKNOWLEDGED", true, false)
}

sealed class KeyPoolDFAState(override val stateId: Int, override val stateName: String, override val isFinal: Boolean, override val isError: Boolean) : DFAState() {
    object AVAILABLE : KeyPoolDFAState(0, "AVAILABLE", false, false)
    object DEGRADED : KeyPoolDFAState(1, "DEGRADED", false, false)
    object EXHAUSTED : KeyPoolDFAState(2, "EXHAUSTED", false, true)
    object RECOVERING : KeyPoolDFAState(3, "RECOVERING", false, false)
}

object AgentDFA {
    private val errorState = AgentDFAState.FAILED
    
    private val transitions = mapOf(
        AgentDFAState.CREATED to mapOf(
            TransitionEvent.SPAWN to AgentDFAState.INITIALIZING
        ),
        AgentDFAState.INITIALIZING to mapOf(
            TransitionEvent.COMPLETE to AgentDFAState.READY,
            TransitionEvent.FAIL to AgentDFAState.FAILED,
            TransitionEvent.TIMEOUT to AgentDFAState.FAILED
        ),
        AgentDFAState.READY to mapOf(
            TransitionEvent.START to AgentDFAState.RUNNING,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.RUNNING to mapOf(
            TransitionEvent.PAUSE to AgentDFAState.PAUSED,
            TransitionEvent.BLOCK to AgentDFAState.BLOCKED,
            TransitionEvent.MESSAGE("request_help") to AgentDFAState.WAITING_MESSAGE,
            TransitionEvent.COMPLETE to AgentDFAState.COMPLETED,
            TransitionEvent.FAIL to AgentDFAState.FAILED,
            TransitionEvent.TIMEOUT to AgentDFAState.FAILED,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.PAUSED to mapOf(
            TransitionEvent.RESUME to AgentDFAState.RUNNING,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.BLOCKED to mapOf(
            TransitionEvent.UNBLOCK to AgentDFAState.RUNNING,
            TransitionEvent.TIMEOUT to AgentDFAState.FAILED,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.WAITING_MESSAGE to mapOf(
            TransitionEvent.MESSAGE("response") to AgentDFAState.RUNNING,
            TransitionEvent.TIMEOUT to AgentDFAState.RUNNING,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.COMPLETED to mapOf(
            TransitionEvent.RESET to AgentDFAState.CREATED,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.FAILED to mapOf(
            TransitionEvent.RESET to AgentDFAState.CREATED,
            TransitionEvent.KILL to AgentDFAState.TERMINATED
        ),
        AgentDFAState.TERMINATED to emptyMap()
    )
    
    fun create(): DeterministicFiniteAutomaton<AgentDFAState> {
        return DeterministicFiniteAutomaton(
            initialState = AgentDFAState.CREATED,
            transitionTable = transitions,
            errorState = errorState
        )
    }
    
    fun validateTransition(from: AgentDFAState, event: TransitionEvent): Boolean {
        return transitions[from]?.containsKey(event) == true
    }
}

object ToolDFA {
    private val errorState = ToolDFAState.ERROR
    
    private val transitions = mapOf(
        ToolDFAState.FREE to mapOf(
            TransitionEvent.TOOL_ACQUIRE("") to ToolDFAState.RESERVED
        ),
        ToolDFAState.RESERVED to mapOf(
            TransitionEvent.TOOL_ACQUIRE("") to ToolDFAState.ACQUIRED,
            TransitionEvent.TIMEOUT to ToolDFAState.FREE
        ),
        ToolDFAState.ACQUIRED to mapOf(
            TransitionEvent.START to ToolDFAState.EXECUTING,
            TransitionEvent.TOOL_RELEASE("") to ToolDFAState.RELEASING
        ),
        ToolDFAState.EXECUTING to mapOf(
            TransitionEvent.COMPLETE to ToolDFAState.RELEASING,
            TransitionEvent.FAIL to ToolDFAState.ERROR,
            TransitionEvent.TIMEOUT to ToolDFAState.ERROR
        ),
        ToolDFAState.RELEASING to mapOf(
            TransitionEvent.TOOL_RELEASE("") to ToolDFAState.FREE
        ),
        ToolDFAState.ERROR to mapOf(
            TransitionEvent.RESET to ToolDFAState.FREE
        )
    )
    
    fun create(): DeterministicFiniteAutomaton<ToolDFAState> {
        return DeterministicFiniteAutomaton(
            initialState = ToolDFAState.FREE,
            transitionTable = transitions,
            errorState = errorState
        )
    }
}

object MessageDFA {
    private val errorState = MessageDFAState.FAILED
    
    private val transitions = mapOf(
        MessageDFAState.PENDING to mapOf(
            TransitionEvent.START to MessageDFAState.QUEUED
        ),
        MessageDFAState.QUEUED to mapOf(
            TransitionEvent.START to MessageDFAState.DELIVERING,
            TransitionEvent.TIMEOUT to MessageDFAState.FAILED
        ),
        MessageDFAState.DELIVERING to mapOf(
            TransitionEvent.COMPLETE to MessageDFAState.DELIVERED,
            TransitionEvent.FAIL to MessageDFAState.FAILED
        ),
        MessageDFAState.DELIVERED to mapOf(
            TransitionEvent.MESSAGE("ack") to MessageDFAState.ACKNOWLEDGED,
            TransitionEvent.TIMEOUT to MessageDFAState.ACKNOWLEDGED
        ),
        MessageDFAState.ACKNOWLEDGED to emptyMap(),
        MessageDFAState.FAILED to emptyMap()
    )
    
    fun create(): DeterministicFiniteAutomaton<MessageDFAState> {
        return DeterministicFiniteAutomaton(
            initialState = MessageDFAState.PENDING,
            transitionTable = transitions,
            errorState = errorState
        )
    }
}

data class StateMachineSnapshot(
    val stateType: String,
    val currentState: String,
    val isValid: Boolean,
    val isFinal: Boolean,
    val isError: Boolean,
    val history: List<String>
)

class StateMachineManager {
    private val logger = LoggerFactory.getLogger(StateMachineManager::class.java)
    
    private val agentMachines = ConcurrentHashMap<String, DeterministicFiniteAutomaton<AgentDFAState>>()
    private val toolMachines = ConcurrentHashMap<String, DeterministicFiniteAutomaton<ToolDFAState>>()
    private val messageMachines = ConcurrentHashMap<String, DeterministicFiniteAutomaton<MessageDFAState>>()
    
    fun createAgentMachine(agentId: String): DeterministicFiniteAutomaton<AgentDFAState> {
        val machine = AgentDFA.create()
        agentMachines[agentId] = machine
        logger.debug("Created agent state machine for: $agentId")
        return machine
    }
    
    fun createToolMachine(toolName: String): DeterministicFiniteAutomaton<ToolDFAState> {
        val machine = ToolDFA.create()
        toolMachines[toolName] = machine
        logger.debug("Created tool state machine for: $toolName")
        return machine
    }
    
    fun createMessageMachine(messageId: String): DeterministicFiniteAutomaton<MessageDFAState> {
        val machine = MessageDFA.create()
        messageMachines[messageId] = machine
        logger.debug("Created message state machine for: $messageId")
        return machine
    }
    
    fun removeAgentMachine(agentId: String) {
        agentMachines.remove(agentId)
    }
    
    fun removeToolMachine(toolName: String) {
        toolMachines.remove(toolName)
    }
    
    fun removeMessageMachine(messageId: String) {
        messageMachines.remove(messageId)
    }
    
    fun getAgentSnapshot(agentId: String): StateMachineSnapshot? {
        val machine = agentMachines[agentId] ?: return null
        return StateMachineSnapshot(
            stateType = "AGENT",
            currentState = machine.state.stateName,
            isValid = !machine.isInErrorState(),
            isFinal = machine.isInFinalState(),
            isError = machine.isInErrorState(),
            history = machine.history.map { "${it.first?.stateName} --${it.second}-->" }
        )
    }
    
    fun getToolSnapshot(toolName: String): StateMachineSnapshot? {
        val machine = toolMachines[toolName] ?: return null
        return StateMachineSnapshot(
            stateType = "TOOL",
            currentState = machine.state.stateName,
            isValid = !machine.isInErrorState(),
            isFinal = machine.isInFinalState(),
            isError = machine.isInErrorState(),
            history = machine.history.map { "${it.first?.stateName} --${it.second}-->" }
        )
    }
    
    fun getMessageSnapshot(messageId: String): StateMachineSnapshot? {
        val machine = messageMachines[messageId] ?: return null
        return StateMachineSnapshot(
            stateType = "MESSAGE",
            currentState = machine.state.stateName,
            isValid = !machine.isInErrorState(),
            isFinal = machine.isInFinalState(),
            isError = machine.isInErrorState(),
            history = machine.history.map { "${it.first?.stateName} --${it.second}-->" }
        )
    }
    
    fun getAllSnapshots(): List<StateMachineSnapshot> {
        val snapshots = mutableListOf<StateMachineSnapshot>()
        
        agentMachines.forEach { (id, machine) ->
            snapshots.add(StateMachineSnapshot(
                stateType = "AGENT",
                currentState = machine.state.stateName,
                isValid = !machine.isInErrorState(),
                isFinal = machine.isInFinalState(),
                isError = machine.isInErrorState(),
                history = machine.history.map { "${it.first?.stateName} --${it.second}-->" }
            ))
        }
        
        return snapshots
    }
    
    fun formatStateMachines(): String {
        return buildString {
            appendLine("=".repeat(60))
            appendLine("STATE MACHINE MONITOR")
            appendLine("=".repeat(60))
            appendLine()
            
            appendLine("Agent State Machines (${agentMachines.size}):")
            appendLine("-".repeat(40))
            agentMachines.forEach { (id, machine) ->
                appendLine("  $id: ${machine.state.stateName} [${if (machine.isInErrorState()) "ERROR" else "OK"}]")
            }
            appendLine()
            
            appendLine("Tool State Machines (${toolMachines.size}):")
            appendLine("-".repeat(40))
            toolMachines.forEach { (name, machine) ->
                appendLine("  $name: ${machine.state.stateName} [${if (machine.isInErrorState()) "ERROR" else "OK"}]")
            }
            appendLine()
            
            appendLine("Message State Machines (${messageMachines.size}):")
            appendLine("-".repeat(40))
            messageMachines.forEach { (id, machine) ->
                appendLine("  $id: ${machine.state.stateName} [${if (machine.isInErrorState()) "ERROR" else "OK"}]")
            }
        }
    }
}

class ResourceAllocationGraph {
    private val logger = LoggerFactory.getLogger(ResourceAllocationGraph::class.java)
    
    private val allocationMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val requestMap = ConcurrentHashMap<String, MutableSet<String>>()
    
    fun requestResource(agentId: String, resource: String) {
        requestMap.getOrPut(agentId) { mutableSetOf() }.add(resource)
    }
    
    fun allocateResource(agentId: String, resource: String) {
        requestMap[agentId]?.remove(resource)
        allocationMap.getOrPut(agentId) { mutableSetOf() }.add(resource)
        logger.debug("Allocated $resource to $agentId")
    }
    
    fun releaseResource(agentId: String, resource: String) {
        allocationMap[agentId]?.remove(resource)
        logger.debug("Released $resource from $agentId")
    }
    
    fun isHeldBy(resource: String, agentId: String): Boolean {
        return allocationMap[agentId]?.contains(resource) == true
    }
    
    fun getHolder(resource: String): String? {
        return allocationMap.entries.find { it.value.contains(resource) }?.key
    }
    
    fun getHeldResources(agentId: String): Set<String> {
        return allocationMap[agentId]?.toSet() ?: emptySet()
    }
    
    fun getRequestedResources(agentId: String): Set<String> {
        return requestMap[agentId]?.toSet() ?: emptySet()
    }
    
    fun detectDeadlock(): Set<String>? {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        
        fun dfs(agentId: String): Boolean {
            visited.add(agentId)
            recursionStack.add(agentId)
            
            val requested = requestMap[agentId] ?: emptySet()
            for (resource in requested) {
                val holder = getHolder(resource)
                if (holder != null) {
                    if (holder in recursionStack) {
                        logger.warn("DEADLOCK DETECTED: Circular wait involving $agentId -> $resource -> $holder")
                        return true
                    }
                    if (holder !in visited) {
                        if (dfs(holder)) return true
                    }
                }
            }
            
            recursionStack.remove(agentId)
            return false
        }
        
        allocationMap.keys.forEach { agentId ->
            if (agentId !in visited) {
                if (dfs(agentId)) {
                    return recursionStack.toSet()
                }
            }
        }
        
        return null
    }
    
    fun formatGraph(): String {
        return buildString {
            appendLine("Resource Allocation Graph:")
            appendLine("-".repeat(40))
            allocationMap.forEach { (agent, resources) ->
                if (resources.isNotEmpty()) {
                    appendLine("$agent holds: ${resources.joinToString(", ")}")
                }
            }
            requestMap.forEach { (agent, resources) ->
                if (resources.isNotEmpty()) {
                    appendLine("$agent requests: ${resources.joinToString(", ")}")
                }
            }
            
            val deadlock = detectDeadlock()
            if (deadlock != null) {
                appendLine()
                appendLine("DEADLOCK DETECTED: $deadlock")
            }
        }
    }
}
