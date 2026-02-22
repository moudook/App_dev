package com.example.smarty.server.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

sealed class DFAState {
    abstract val stateId: Int
    abstract val stateName: String
    abstract val isFinal: Boolean
    abstract val isError: Boolean
    abstract val isTransient: Boolean
    abstract val enteredAt: Long
    abstract val exitCount: Long
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
    object SUSPEND : TransitionEvent()
    object WAKE : TransitionEvent()
    object INTERRUPT : TransitionEvent()
    data class ERROR(val code: Int, val message: String) : TransitionEvent()
    data class MESSAGE(val type: String, val payload: String? = null) : TransitionEvent()
    data class TOOL_ACQUIRE(val tool: String) : TransitionEvent()
    data class TOOL_RELEASE(val tool: String) : TransitionEvent()
    data class CONDITION(val predicate: String, val result: Boolean) : TransitionEvent()
    data class GUARD(val expression: String, val satisfied: Boolean) : TransitionEvent()
    data class WEIGHT(val resource: String, val amount: Int) : TransitionEvent()
}

class DeterministicFiniteAutomaton<S : DFAState>(
    private val initialState: S,
    private val transitionTable: Map<S, Map<TransitionEvent, S>>,
    private val errorState: S,
    private val stateValidators: Map<S, (S) -> Boolean> = emptyMap(),
    private val transitionGuards: Map<Pair<S, TransitionEvent>, (S, TransitionEvent) -> Boolean> = emptyMap()
) {
    private val currentState = AtomicReference(initialState)
    private val stateHistory = ConcurrentLinkedQueue<StateTransition<S>>()
    private val eventHistory = ConcurrentLinkedQueue<TransitionEvent>()
    private val stateMetrics = ConcurrentHashMap<String, StateMetrics>()
    private val lock = ReentrantReadWriteLock()
    
    private val listeners = ConcurrentLinkedQueue<StateChangeListener<S>>()
    
    data class StateTransition<S>(
        val fromState: S,
        val toState: S,
        val event: TransitionEvent,
        val timestamp: Long,
        val durationMs: Long
    )
    
    data class StateMetrics(
        var enterCount: Long = 0,
        var totalTimeMs: Long = 0,
        var lastEntered: Long = 0,
        var transitionCount: Long = 0
    )
    
    val state: S get() = currentState.get()
    
    val history: List<StateTransition<S>> get() = stateHistory.toList()
    
    val canUndo: Boolean get() = stateHistory.isNotEmpty()
    
    fun addListener(listener: StateChangeListener<S>) {
        listeners.add(listener)
    }
    
    fun transition(event: TransitionEvent): TransitionResult<S> {
        return lock.write {
            val current = currentState.get()
            
            val guard = transitionGuards[current to event]
            if (guard != null && !guard(current, event)) {
                return@write TransitionResult(false, current, "Guard condition not satisfied")
            }
            
            val validator = stateValidators[current]
            if (validator != null && !validator(current)) {
                return@write TransitionResult(false, current, "State validation failed")
            }
            
            val nextState = transitionTable[current]?.get(event)
            
            if (nextState == null) {
                val validTransitions = transitionTable[current]?.keys?.map { it.toString() } ?: emptyList()
                return@write TransitionResult(false, current, "No transition for event $event. Valid: $validTransitions")
            }
            
            val transitionStart = System.currentTimeMillis()
            
            val previous = currentState.getAndSet(nextState)
            
            val transition = StateTransition(previous, nextState, event, transitionStart, transitionStart - (stateMetrics[previous.stateName]?.lastEntered ?: transitionStart))
            stateHistory.add(transition)
            
            updateMetrics(previous.stateName, transitionStart)
            
            listeners.forEach { it.onTransition(previous, nextState, event) }
            
            TransitionResult(true, nextState, "Transition successful")
        }
    }
    
    private fun updateMetrics(stateName: String, timestamp: Long) {
        val metrics = stateMetrics.getOrPut(stateName) { StateMetrics() }
        metrics.enterCount++
        metrics.lastEntered = timestamp
        
        if (stateHistory.isNotEmpty()) {
            val lastTransition = stateHistory.lastOrNull()
            if (lastTransition != null) {
                val prevMetrics = stateMetrics[lastTransition.fromState.stateName]
                prevMetrics?.let {
                    it.totalTimeMs += lastTransition.durationMs
                    it.transitionCount++
                }
            }
        }
    }
    
    fun transitionWithCallback(event: TransitionEvent, callback: (S) -> Unit): TransitionResult<S> {
        val result = transition(event)
        if (result.success) {
            callback(result.newState)
        }
        return result
    }
    
    fun canTransition(event: TransitionEvent): Boolean {
        val current = currentState.get()
        
        val guard = transitionGuards[current to event]
        if (guard != null && !guard(current, event)) return false
        
        return transitionTable[current]?.containsKey(event) == true
    }
    
    fun getValidTransitions(): List<TransitionEvent> {
        val current = currentState.get()
        return transitionTable[current]?.keys?.filter { event ->
            val guard = transitionGuards[current to event]
            guard == null || guard(current, event)
        } ?: emptyList()
    }
    
    fun forceState(newState: S): Boolean {
        return lock.write {
            val previous = currentState.getAndSet(newState)
            stateHistory.add(StateTransition(previous, newState, TransitionEvent.MESSAGE("force"), System.currentTimeMillis(), 0))
            listeners.forEach { it.onTransition(previous, newState, TransitionEvent.MESSAGE("force")) }
            true
        }
    }
    
    fun undo(): S? {
        return lock.write {
            val last = stateHistory.pollLast() ?: return@write null
            currentState.set(last.fromState)
            listeners.forEach { it.onTransition(last.toState, last.fromState, TransitionEvent.MESSAGE("undo")) }
            last.fromState
        }
    }
    
    fun isInErrorState(): Boolean = currentState.get().isError
    fun isInFinalState(): Boolean = currentState.get().isFinal
    fun isInState(stateName: String): Boolean = currentState.get().stateName == stateName
    
    fun reset() {
        lock.write {
            currentState.set(initialState)
            stateHistory.clear()
            eventHistory.clear()
        }
    }
    
    fun getMetrics(stateName: String): StateMetrics? = stateMetrics[stateName]
    
    fun getAllMetrics(): Map<String, StateMetrics> = stateMetrics.toMap()
    
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        
        for ((state, transitions) in transitionTable) {
            if (transitions.isEmpty() && !state.isFinal && !state.isError) {
                errors.add("State ${state.stateName} has no outgoing transitions and is not final")
            }
        }
        
        var reachable = setOf(initialState)
        val visited = mutableSetOf<S>()
        
        fun dfs(state: S) {
            if (state in visited) return
            visited.add(state)
            
            transitionTable[state]?.values?.forEach { next ->
                reachable = reachable + next
                dfs(next)
            }
        }
        
        dfs(initialState)
        
        transitionTable.keys.filter { it !in reachable }.forEach { unreachable ->
            errors.add("State ${unreachable.stateName} is unreachable from initial state")
        }
        
        return ValidationResult(errors.isEmpty(), errors)
    }
    
    interface StateChangeListener<S> {
        fun onTransition(from: S, to: S, event: TransitionEvent)
    }
}

data class TransitionResult<S>(
    val success: Boolean,
    val newState: S?,
    val message: String
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>
)

sealed class AgentDFAState(
    override val stateId: Int,
    override val stateName: String,
    override val isFinal: Boolean,
    override val isError: Boolean,
    override val isTransient: Boolean,
    override val enteredAt: Long,
    override val exitCount: Long
) : DFAState() {
    object CREATED : AgentDFAState(0, "CREATED", false, false, true, System.currentTimeMillis(), 0)
    object INITIALIZING : AgentDFAState(1, "INITIALIZING", false, false, true, System.currentTimeMillis(), 0)
    object READY : AgentDFAState(2, "READY", false, false, false, System.currentTimeMillis(), 0)
    object RUNNING : AgentDFAState(3, "RUNNING", false, false, false, System.currentTimeMillis(), 0)
    object PAUSED : AgentDFAState(4, "PAUSED", false, false, false, System.currentTimeMillis(), 0)
    object BLOCKED : AgentDFAState(5, "BLOCKED", false, false, false, System.currentTimeMillis(), 0)
    object WAITING_MESSAGE : AgentDFAState(6, "WAITING_MESSAGE", false, false, false, System.currentTimeMillis(), 0)
    object SUSPENDED : AgentDFAState(7, "SUSPENDED", false, false, false, System.currentTimeMillis(), 0)
    object COMPLETED : AgentDFAState(8, "COMPLETED", true, false, false, System.currentTimeMillis(), 0)
    object FAILED : AgentDFAState(9, "FAILED", false, true, false, System.currentTimeMillis(), 0)
    object TERMINATED : AgentDFAState(10, "TERMINATED", true, false, false, System.currentTimeMillis(), 0)
}

sealed class ToolDFAState(
    override val stateId: Int,
    override val stateName: String,
    override val isFinal: Boolean,
    override val isError: Boolean,
    override val isTransient: Boolean,
    override val enteredAt: Long,
    override val exitCount: Long
) : DFAState() {
    object FREE : ToolDFAState(0, "FREE", false, false, false, System.currentTimeMillis(), 0)
    object RESERVED : ToolDFAState(1, "RESERVED", false, false, true, System.currentTimeMillis(), 0)
    object ACQUIRED : ToolDFAState(2, "ACQUIRED", false, false, false, System.currentTimeMillis(), 0)
    object EXECUTING : ToolDFAState(3, "EXECUTING", false, false, true, System.currentTimeMillis(), 0)
    object RELEASING : ToolDFAState(4, "RELEASING", false, false, true, System.currentTimeMillis(), 0)
    object ERROR : ToolDFAState(5, "ERROR", false, true, false, System.currentTimeMillis(), 0)
    object MAINTENANCE : ToolDFAState(6, "MAINTENANCE", false, false, false, System.currentTimeMillis(), 0)
}

sealed class MessageDFAState(
    override val stateId: Int,
    override val stateName: String,
    override val isFinal: Boolean,
    override val isError: Boolean,
    override val isTransient: Boolean,
    override val enteredAt: Long,
    override val exitCount: Long
) : DFAState() {
    object PENDING : MessageDFAState(0, "PENDING", false, false, true, System.currentTimeMillis(), 0)
    object QUEUED : MessageDFAState(1, "QUEUED", false, false, false, System.currentTimeMillis(), 0)
    object DELIVERING : MessageDFAState(2, "DELIVERING", false, false, true, System.currentTimeMillis(), 0)
    object DELIVERED : MessageDFAState(3, "DELIVERED", true, false, false, System.currentTimeMillis(), 0)
    object FAILED : MessageDFAState(4, "FAILED", false, true, false, System.currentTimeMillis(), 0)
    object ACKNOWLEDGED : MessageDFAState(5, "ACKNOWLEDGED", true, false, false, System.currentTimeMillis(), 0)
    object RETRY : MessageDFAState(6, "RETRY", false, false, true, System.currentTimeMillis(), 0)
}

sealed class KeyPoolDFAState(
    override val stateId: Int,
    override val stateName: String,
    override val isFinal: Boolean,
    override val isError: Boolean,
    override val isTransient: Boolean,
    override val enteredAt: Long,
    override val exitCount: Long
) : DFAState() {
    object AVAILABLE : KeyPoolDFAState(0, "AVAILABLE", false, false, false, System.currentTimeMillis(), 0)
    object DEGRADED : KeyPoolDFAState(1, "DEGRADED", false, false, false, System.currentTimeMillis(), 0)
    object EXHAUSTED : KeyPoolDFAState(2, "EXHAUSTED", false, true, false, System.currentTimeMillis(), 0)
    object RECOVERING : KeyPoolDFAState(3, "RECOVERING", false, false, true, System.currentTimeMillis(), 0)
    object CRITICAL : KeyPoolDFAState(4, "CRITICAL", false, true, false, System.currentTimeMillis(), 0)
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
            TransitionEvent.SUSPEND to AgentDFAState.SUSPENDED,
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
        AgentDFAState.SUSPENDED to mapOf(
            TransitionEvent.WAKE to AgentDFAState.READY,
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
    
    private val guards = mapOf(
        (AgentDFAState.RUNNING to TransitionEvent.PAUSE) to { _: AgentDFAState, _: TransitionEvent -> Random.nextBoolean() },
        (AgentDFAState.BLOCKED to TransitionEvent.TIMEOUT) to { state: AgentDFAState, _: TransitionEvent -> state.exitCount > 3 }
    )
    
    fun create(): DeterministicFiniteAutomaton<AgentDFAState> {
        return DeterministicFiniteAutomaton(
            initialState = AgentDFAState.CREATED,
            transitionTable = transitions,
            errorState = errorState,
            transitionGuards = guards
        )
    }
    
    fun validateTransition(from: AgentDFAState, event: TransitionEvent): Boolean {
        return transitions[from]?.containsKey(event) == true
    }
    
    fun getStateByName(name: String): AgentDFAState {
        return when (name) {
            "CREATED" -> AgentDFAState.CREATED
            "INITIALIZING" -> AgentDFAState.INITIALIZING
            "READY" -> AgentDFAState.READY
            "RUNNING" -> AgentDFAState.RUNNING
            "PAUSED" -> AgentDFAState.PAUSED
            "BLOCKED" -> AgentDFAState.BLOCKED
            "WAITING_MESSAGE" -> AgentDFAState.WAITING_MESSAGE
            "SUSPENDED" -> AgentDFAState.SUSPENDED
            "COMPLETED" -> AgentDFAState.COMPLETED
            "FAILED" -> AgentDFAState.FAILED
            "TERMINATED" -> AgentDFAState.TERMINATED
            else -> AgentDFAState.CREATED
        }
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
            TransitionEvent.RESET to ToolDFAState.FREE,
            TransitionEvent.MESSAGE("maintenance") to ToolDFAState.MAINTENANCE
        ),
        ToolDFAState.MAINTENANCE to mapOf(
            TransitionEvent.COMPLETE to ToolDFAState.FREE
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
            TransitionEvent.TIMEOUT to MessageDFAState.RETRY
        ),
        MessageDFAState.DELIVERING to mapOf(
            TransitionEvent.COMPLETE to MessageDFAState.DELIVERED,
            TransitionEvent.FAIL to MessageDFAState.RETRY,
            TransitionEvent.TIMEOUT to MessageDFAState.RETRY
        ),
        MessageDFAState.RETRY to mapOf(
            TransitionEvent.START to MessageDFAState.DELIVERING,
            TransitionEvent.FAIL to MessageDFAState.FAILED,
            TransitionEvent.TIMEOUT to MessageDFAState.FAILED
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
    val isTransient: Boolean,
    val history: List<String>,
    val metrics: Map<String, Any>
)

class StateMachineManager {
    private val logger = LoggerFactory.getLogger(StateMachineManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val agentMachines = ConcurrentHashMap<String, DeterministicFiniteAutomaton<AgentDFAState>>()
    private val toolMachines = ConcurrentHashMap<String, DeterministicFiniteAutomaton<ToolDFAState>>()
    private val messageMachines = ConcurrentHashMap<String, DeterministicFiniteAutomaton<MessageDFAState>>()
    
    private val hierarchicalMachines = ConcurrentHashMap<String, HierarchicalStateMachine>()
    private val petriNets = ConcurrentHashMap<String, PetriNet>()
    
    private val stateValidator = StateValidator()
    private val modelChecker = ModelChecker()
    private val deadlockDetector = AdvancedDeadlockDetector()
    
    private val listeners = ConcurrentLinkedQueue<StateMachineEventListener>()
    
    init {
        startStateMonitoring()
        startDeadlockDetection()
    }
    
    private fun startStateMonitoring() {
        scope.launch {
            while (true) {
                delay(10000)
                validateAllStates()
            }
        }
    }
    
    private fun startDeadlockDetection() {
        scope.launch {
            while (true) {
                delay(15000)
                detectDeadlocks()
            }
        }
    }
    
    private fun validateAllStates() {
        agentMachines.forEach { (id, machine) ->
            if (machine.isInErrorState()) {
                notifyListeners(StateMachineEvent(id, "ERROR_STATE", machine.state.stateName))
            }
        }
    }
    
    private fun detectDeadlocks() {
        val cycles = deadlockDetector.detectCycles(agentMachines)
        if (cycles.isNotEmpty()) {
            logger.warn("Deadlock cycles detected: $cycles")
            notifyListeners(StateMachineEvent("system", "DEADLOCK", cycles.toString()))
        }
    }
    
    private fun notifyListeners(event: StateMachineEvent) {
        listeners.forEach { it.onEvent(event) }
    }
    
    fun createAgentMachine(agentId: String): DeterministicFiniteAutomaton<AgentDFAState> {
        val machine = AgentDFA.create()
        machine.addListener(object : DeterministicFiniteAutomaton.StateChangeListener<AgentDFAState> {
            override fun onTransition(from: AgentDFAState, to: AgentDFAState, event: TransitionEvent) {
                notifyListeners(StateMachineEvent(agentId, from.stateName, to.stateName))
                logger.debug("$agentId: $from -> $to via $event")
            }
        })
        agentMachines[agentId] = machine
        logger.debug("Created agent state machine: $agentId")
        return machine
    }
    
    fun createToolMachine(toolName: String): DeterministicFiniteAutomaton<ToolDFAState> {
        val machine = ToolDFA.create()
        toolMachines[toolName] = machine
        logger.debug("Created tool state machine: $toolName")
        return machine
    }
    
    fun createMessageMachine(messageId: String): DeterministicFiniteAutomaton<MessageDFAState> {
        val machine = MessageDFA.create()
        messageMachines[messageId] = machine
        logger.debug("Created message state machine: $messageId")
        return machine
    }
    
    fun createHierarchicalMachine(id: String, config: HierarchicalConfig): HierarchicalStateMachine {
        val hsm = HierarchicalMachineImpl(id, config)
        hierarchicalMachines[id] = hsm
        return hsm
    }
    
    fun createPetriNet(id: String, places: Int, transitions: Int): PetriNet {
        val net = PetriNet(id, places, transitions)
        petriNets[id] = net
        return net
    }
    
    fun removeAgentMachine(agentId: String) = agentMachines.remove(agentId)
    fun removeToolMachine(toolName: String) = toolMachines.remove(toolName)
    fun removeMessageMachine(messageId: String) = messageMachines.remove(messageId)
    
    fun getAgentSnapshot(agentId: String): StateMachineSnapshot? {
        val machine = agentMachines[agentId] ?: return null
        return StateMachineSnapshot(
            stateType = "AGENT",
            currentState = machine.state.stateName,
            isValid = !machine.isInErrorState(),
            isFinal = machine.isInFinalState(),
            isError = machine.isInErrorState(),
            isTransient = machine.state.isTransient,
            history = machine.history.map { "${it.fromState.stateName} --${it.event}--> ${it.toState.stateName}" },
            metrics = machine.getAllMetrics().mapValues { it.value.enterCount }
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
            isTransient = machine.state.isTransient,
            history = machine.history.map { "${it.fromState.stateName} --${it.event}--> ${it.toState.stateName}" },
            metrics = emptyMap()
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
            isTransient = machine.state.isTransient,
            history = machine.history.map { "${it.fromState.stateName} --${it.event}--> ${it.toState.stateName}" },
            metrics = emptyMap()
        )
    }
    
    fun getAllSnapshots(): List<StateMachineSnapshot> {
        return agentMachines.map { getAgentSnapshot(it.key) } +
                toolMachines.map { getToolSnapshot(it.key) } +
                messageMachines.map { getMessageSnapshot(it.key) }
    }
    
    fun validateMachine(agentId: String): ValidationResult {
        return agentMachines[agentId]?.validate() ?: ValidationResult(false, listOf("Machine not found"))
    }
    
    fun addListener(listener: StateMachineEventListener) {
        listeners.add(listener)
    }
    
    fun formatStateMachines(): String {
        return buildString {
            appendLine("=".repeat(70))
            appendLine("STATE MACHINE MONITOR")
            appendLine("=".repeat(70))
            appendLine()
            
            appendLine("Agent State Machines (${agentMachines.size}):")
            appendLine("-".repeat(50))
            agentMachines.forEach { (id, machine) ->
                val status = when {
                    machine.isInErrorState() -> "ERROR"
                    machine.isInFinalState() -> "FINAL"
                    machine.state.isTransient -> "TRANSIENT"
                    else -> "STABLE"
                }
                appendLine("  $id: ${machine.state.stateName} [$status]")
                if (machine.history.size > 3) {
                    appendLine("    Last: ${machine.history.last()}")
                }
            }
            appendLine()
            
            appendLine("Tool State Machines (${toolMachines.size}):")
            appendLine("-".repeat(50))
            toolMachines.forEach { (name, machine) ->
                appendLine("  $name: ${machine.state.stateName} [${if (machine.isInErrorState()) "ERROR" else "OK"}]")
            }
            appendLine()
            
            appendLine("Message State Machines (${messageMachines.size}):")
            appendLine("-".repeat(50))
            messageMachines.forEach { (id, machine) ->
                appendLine("  $id: ${machine.state.stateName} [${if (machine.isInErrorState()) "ERROR" else "OK"}]")
            }
            appendLine()
            
            appendLine("Hierarchical State Machines (${hierarchicalMachines.size}):")
            appendLine("-".repeat(50))
            hierarchicalMachines.forEach { (id, hsm) ->
                appendLine("  $id: ${hsm.getCurrentState()}")
            }
            
            appendLine()
            appendLine("Petri Nets (${petriNets.size}):")
            appendLine("-".repeat(50))
            petriNets.forEach { (id, net) ->
                appendLine("  $id: ${net.getMarking()}")
            }
        }
    }
}

data class StateMachineEvent(
    val machineId: String,
    val fromState: String,
    val toState: String
)

interface StateMachineEventListener {
    fun onEvent(event: StateMachineEvent)
}

class StateValidator {
    fun validateState(state: DFAState): Boolean {
        return state.stateName.isNotEmpty() && state.stateId >= 0
    }
    
    fun validateTransition(from: DFAState, to: DFAState, event: TransitionEvent): List<String> {
        val errors = mutableListOf<String>()
        
        if (from.isFinal && event !is TransitionEvent.RESET) {
            errors.add("Cannot transition from final state $from with event $event")
        }
        
        if (to.isError && event !is TransitionEvent.FAIL && event !is TransitionEvent.ERROR) {
            errors.add("Cannot directly transition to error state $to without failure event")
        }
        
        return errors
    }
}

class ModelChecker {
    fun checkSafety(machine: DeterministicFiniteAutomaton<*>): Boolean {
        return !machine.state.isError
    }
    
    fun checkLiveness(machine: DeterministicFiniteAutomaton<*>): Boolean {
        return machine.getValidTransitions().isNotEmpty() || machine.state.isFinal
    }
    
    fun checkReachability(machine: DeterministicFiniteAutomaton<*>, targetState: String): Boolean {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(machine.state.stateName))
        
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == targetState) return true
            if (current in visited) continue
            visited.add(current)
            
            val validEvents = machine.getValidTransitions()
            // Simplified reachability check
        }
        
        return false
    }
}

class AdvancedDeadlockDetector {
    fun detectCycles(machines: Map<String, DeterministicFiniteAutomaton<*>>): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        
        val dependencyGraph = buildDependencyGraph(machines)
        
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        val path = mutableListOf<String>()
        
        fun dfs(node: String): Boolean {
            visited.add(node)
            recursionStack.add(node)
            path.add(node)
            
            val dependencies = dependencyGraph[node] ?: emptyList()
            
            for (dep in dependencies) {
                if (dep !in visited) {
                    if (dfs(dep)) {
                        val cycleStart = path.indexOf(dep)
                        if (cycleStart >= 0) {
                            cycles.add(path.subList(cycleStart, path.size))
                        }
                        return true
                    }
                } else if (dep in recursionStack) {
                    val cycleStart = path.indexOf(dep)
                    if (cycleStart >= 0) {
                        cycles.add(path.subList(cycleStart, path.size + 1))
                    }
                }
            }
            
            path.removeAt(path.size - 1)
            recursionStack.remove(node)
            return false
        }
        
        dependencyGraph.keys.forEach { node ->
            if (node !in visited) {
                dfs(node)
            }
        }
        
        return cycles
    }
    
    private fun buildDependencyGraph(machines: Map<String, DeterministicFiniteAutomaton<*>>): Map<String, List<String>> {
        val graph = mutableMapOf<String, MutableList<String>>()
        
        machines.forEach { (id, machine) ->
            val currentState = machine.state.stateName
            val validTransitions = machine.getValidTransitions()
            
            graph.getOrPut(currentState) { mutableListOf() }
        }
        
        return graph
    }
}

data class HierarchicalConfig(
    val name: String,
    val states: Map<String, HierarchicalState>,
    val initialState: String,
    val parentState: String? = null
)

data class HierarchicalState(
    val name: String,
    val isFinal: Boolean = false,
    val isInitial: Boolean = false,
    val substates: Map<String, HierarchicalState> = emptyMap(),
    val entryAction: (() -> Unit)? = null,
    val exitAction: (() -> Unit)? = null
)

interface HierarchicalStateMachine {
    fun enter(state: String)
    fun exit(state: String)
    fun getCurrentState(): String
    fun getActiveSubstates(): List<String>
}

class HierarchicalMachineImpl(
    private val id: String,
    private val config: HierarchicalConfig
) : HierarchicalStateMachine {
    private val currentStates = ConcurrentHashMap<String, String>()
    private val stateStack = ArrayDeque<String>()
    
    init {
        stateStack.push(config.initialState)
        currentStates[id] = config.initialState
    }
    
    override fun enter(state: String) {
        val hierarchicalState = config.states[state]
        hierarchicalState?.entryAction?.invoke()
        
        stateStack.push(state)
        currentStates[id] = state
        
        hierarchicalState?.substates?.values?.filter { it.isInitial }?.firstOrNull()?.let { initial ->
            enter(initial.name)
        }
    }
    
    override fun exit(state: String) {
        val hierarchicalState = config.states[state]
        
        hierarchicalState?.substates?.values?.forEach { substate ->
            if (currentStates.values.contains(substate.name)) {
                exit(substate.name)
            }
        }
        
        hierarchicalState?.exitAction?.invoke()
        
        if (stateStack.isNotEmpty() && stateStack.peek() == state) {
            stateStack.pop()
        }
    }
    
    override fun getCurrentState(): String {
        return currentStates[id] ?: config.initialState
    }
    
    override fun getActiveSubstates(): List<String> {
        return stateStack.toList()
    }
}

class PetriNet(
    val id: String,
    private val placeCount: Int,
    private val transitionCount: Int
) {
    private val places = ConcurrentHashMap<Int, Int>()
    private val transitions = ConcurrentHashMap<Int, Transition>()
    private val arcs = ConcurrentHashMap<Pair<Int, Int>, Arc>()
    
    data class Transition(
        val id: Int,
        val guard: () -> Boolean = { true },
        val action: () -> Unit = {}
    )
    
    data class Arc(
        val from: Int,
        val to: Int,
        val weight: Int,
        val type: ArcType
    )
    
    enum class ArcType { PLACE_TO_TRANSITION, TRANSITION_TO_PLACE }
    
    init {
        places[0] = 1
    }
    
    fun addTransition(id: Int, guard: () -> Boolean = { true }, action: () -> Unit = {}) {
        transitions[id] = Transition(id, guard, action)
    }
    
    fun addArc(from: Int, to: Int, weight: Int = 1, type: ArcType) {
        arcs[from to to] = Arc(from, to, weight, type)
    }
    
    fun fire(transitionId: Int): Boolean {
        val transition = transitions[transitionId] ?: return false
        
        if (!transition.guard()) return false
        
        val inputPlaces = arcs.filter { it.key.second == transitionId && it.value.type == ArcType.PLACE_TO_TRANSITION }
        
        for ((_, arc) in inputPlaces) {
            val tokens = places[arc.from] ?: 0
            if (tokens < arc.weight) return false
        }
        
        for ((_, arc) in inputPlaces) {
            places[arc.from] = (places[arc.from] ?: 0) - arc.weight
        }
        
        transition.action()
        
        val outputPlaces = arcs.filter { it.key.first == transitionId && it.value.type == ArcType.TRANSITION_TO_PLACE }
        
        for ((_, arc) in outputPlaces) {
            places[arc.to] = (places[arc.to] ?: 0) + arc.weight
        }
        
        return true
    }
    
    fun getMarking(): Map<Int, Int> = places.toMap()
    
    fun isDeadlocked(): Boolean {
        return transitions.values.all { transition ->
            val inputPlaces = arcs.filter { it.key.second == transition.id && it.value.type == ArcType.PLACE_TO_TRANSITION }
            inputPlaces.any { (_, arc) -> (places[arc.from] ?: 0) < arc.weight }
        }
    }
    
    fun getEnabledTransitions(): List<Int> {
        return transitions.filter { (_, transition) ->
            val inputPlaces = arcs.filter { it.key.second == transition.id && it.value.type == ArcType.PLACE_TO_TRANSITION }
            inputPlaces.all { (_, arc) -> (places[arc.from] ?: 0) >= arc.weight }
        }.keys.toList()
    }
}

class ResourceAllocationGraph {
    private val logger = LoggerFactory.getLogger(ResourceAllocationGraph::class.java)
    
    private val allocationMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val requestMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val waitGraph = WaitGraph()
    
    fun requestResource(agentId: String, resource: String) {
        requestMap.getOrPut(agentId) { mutableSetOf() }.add(resource)
        waitGraph.addEdge(agentId, resource)
    }
    
    fun allocateResource(agentId: String, resource: String) {
        requestMap[agentId]?.remove(resource)
        allocationMap.getOrPut(agentId) { mutableSetOf() }.add(resource)
        waitGraph.removeEdge(agentId, resource)
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
        return waitGraph.detectCycle()
    }
    
    fun formatGraph(): String {
        return buildString {
            appendLine("Resource Allocation Graph:")
            appendLine("-".repeat(50))
            
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

class WaitGraph {
    private val edges = ConcurrentHashMap<String, MutableSet<String>>()
    
    fun addEdge(from: String, to: String) {
        edges.getOrPut(from) { mutableSetOf() }.add(to)
    }
    
    fun removeEdge(from: String, to: String) {
        edges[from]?.remove(to)
    }
    
    fun detectCycle(): Set<String>? {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        
        fun dfs(node: String, path: MutableList<String>): Boolean {
            visited.add(node)
            recursionStack.add(node)
            path.add(node)
            
            val neighbors = edges[node] ?: emptySet()
            
            for (neighbor in neighbors) {
                if (neighbor !in visited) {
                    if (dfs(neighbor, path)) return true
                } else if (neighbor in recursionStack) {
                    val cycleStart = path.indexOf(neighbor)
                    if (cycleStart >= 0) {
                        return true
                    }
                }
            }
            
            path.removeAt(path.size - 1)
            recursionStack.remove(node)
            return false
        }
        
        edges.keys.forEach { node ->
            if (node !in visited) {
                if (dfs(node, mutableListOf())) {
                    return setOf(node)
                }
            }
        }
        
        return null
    }
}
