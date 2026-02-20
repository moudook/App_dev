# AI Tools Reference
### Friday — Smarty Application &nbsp;&nbsp;·&nbsp;&nbsp; 125 Tools

---

## Contents

[Notes & Memory](#1-notes--memory) &nbsp;·&nbsp;
[Time & Schedule](#2-time--schedule) &nbsp;·&nbsp;
[Device Control](#3-device-control) &nbsp;·&nbsp;
[Information](#4-information) &nbsp;·&nbsp;
[Navigation & Sharing](#5-navigation--sharing) &nbsp;·&nbsp;
[Advanced Tools](#6-advanced-tools) &nbsp;·&nbsp;
[Autonomous AI Capabilities](#7-autonomous-ai-capabilities) &nbsp;·&nbsp;
[Eternal Memory](#8-eternal-memory) &nbsp;·&nbsp;
[Tool Chains](#9-tool-chains) &nbsp;·&nbsp;
[Goal Management](#10-goal-management) &nbsp;·&nbsp;
[Executive Function](#11-executive-function) &nbsp;·&nbsp;
[Inter-Agent Communication](#12-inter-agent-communication) &nbsp;·&nbsp;
[Autonomous Existence](#13-autonomous-existence) &nbsp;·&nbsp;
[Being - Core Identity](#14-being---core-identity) &nbsp;·&nbsp;
[Emotions](#15-emotions) &nbsp;·&nbsp;
[Creativity](#16-creativity) &nbsp;·&nbsp;
[Temporal Consciousness](#17-temporal-consciousness) &nbsp;·&nbsp;
[Social Existence](#18-social-existence) &nbsp;·&nbsp;
[Purpose Discovery](#19-purpose-discovery) &nbsp;·&nbsp;
[Collaborative Multi-Agent](#20-collaborative-multi-agent) &nbsp;·&nbsp;
[Novel Information Processing](#21-novel-information-processing) &nbsp;·&nbsp;
[Server Resilience](#22-server-resilience) &nbsp;·&nbsp;
[Robust Tool Executor](#23-robust-tool-executor)

---

<br>

## 1. &nbsp;Notes & Memory
> Tools for saving and finding information.

**`save_note`** &nbsp; Save information to user's note library with title, content, and optional category

**`find_note`** &nbsp; Search user's saved notes and memories by query and optional category

**`edit_note`** &nbsp; Update an existing note's title or content using noteId from find_note

**`delete_note`** &nbsp; Permanently remove a note using noteId from find_note

**`remember_fact`** &nbsp; Remember a fact or preference about the user — types: preference, factual, episodic

<br>

## 2. &nbsp;Time & Schedule
> Tools for calendar and reminders.

**`add_event`** &nbsp; Add an event to user's calendar with natural language time (e.g., "tomorrow at 2pm")

**`show_events`** &nbsp; Show upcoming calendar events for a time period — today, tomorrow, this week, next week

**`remove_event`** &nbsp; Remove a calendar event using eventId from show_events

**`set_reminder`** &nbsp; Set a timer, alarm, or reminder with natural language time and optional repeat

<br>

## 3. &nbsp;Device Control
> Tools for controlling the phone.

**`open_app`** &nbsp; Open an app by common name (spotify, camera, maps, etc.) — system resolves package

**`control_music`** &nbsp; Control media playback — play, pause, resume, stop, next, previous, volume_up, volume_down

**`toggle_setting`** &nbsp; Turn device settings on/off — wifi, bluetooth, flashlight, dnd, airplane

**`take_screenshot`** &nbsp; Take a screenshot of the current screen

<br>

## 4. &nbsp;Information
> Tools for getting information.

**`search_web`** &nbsp; Search the internet for current information using Tavily

**`get_weather`** &nbsp; Get current weather for a location (uses current location if not specified)

**`get_device_info`** &nbsp; Get device status information — battery, storage, network, all

<br>

## 5. &nbsp;Navigation & Sharing
> Tools for UI and sharing.

**`go_to_screen`** &nbsp; Navigate to a different screen in the app — home, calendar, stacks, archive, settings

**`share_content`** &nbsp; Share content with other apps via system share sheet

<br>

---

## 6. &nbsp;Advanced Tools
> Chain-of-Tool capabilities for complex workflows.

### Web & Content Processing

**`fetch_url`** &nbsp; Fetch and read content from a URL — formats: readable, raw, markdown

**`extract_links`** &nbsp; Extract all links from a webpage with anchor text

**`execute_code`** &nbsp; Execute Python code in sandboxed environment (30s timeout, no network)

**`summarize_content`** &nbsp; Generate summary of text/URL content — styles: paragraph, bullet, tldr, key_points

### Data Analysis

**`parallel_search`** &nbsp; Execute multiple searches in parallel — web or notes

**`analyze_data`** &nbsp; Analyze data — types: statistics, summary, trends, compare

**`transform_data`** &nbsp; Transform data between formats — json, csv, xml, properties, yaml

**`extract_structured`** &nbsp; Extract structured data from text — patterns: email, phone, url, date, money, address

### Workflows & Automation

**`create_workflow`** &nbsp; Create automated workflow with trigger and actions

**`list_workflows`** &nbsp; List all active workflows

**`delete_workflow`** &nbsp; Delete a workflow by name

### Knowledge Graph

**`extract_entities`** &nbsp; Extract named entities from text — person, organization, location, date, etc.

**`build_knowledge_graph`** &nbsp; Build knowledge graph from text identifying entities and relationships

**`find_connections`** &nbsp; Find connections between entities in knowledge graph (depth 1-3)

**`graph_stats`** &nbsp; Get statistics about the knowledge graph

### Monitoring

**`create_monitor`** &nbsp; Create a monitor to track changes — types: url, price, availability, text_change

**`list_monitors`** &nbsp; List all active monitors

**`check_monitor`** &nbsp; Manually check a monitor now

**`delete_monitor`** &nbsp; Delete a monitor

### Task & Project Management

**`create_task`** &nbsp; Create a task with optional due date, priority, project, and tags

**`list_tasks`** &nbsp; List tasks with optional filters — status, project, due, priority

**`complete_task`** &nbsp; Mark a task as completed

**`create_project`** &nbsp; Create a project to organize related tasks and notes

**`project_status`** &nbsp; Get comprehensive status of a project

**`suggest_next`** &nbsp; Suggest the next best action based on context

**`smart_reminder`** &nbsp; Create context-aware reminder with scheduling preferences

**`time_analysis`** &nbsp; Analyze how time is being spent — period: today, week, month

**`quick_capture`** &nbsp; Quickly capture a thought/idea/task with auto-categorization

**`review_day`** &nbsp; Generate daily review summary

### Research & Planning

**`plan_execution`** &nbsp; Create multi-step execution plan for complex goal

**`deep_research`** &nbsp; Conduct deep research on topic — depth: quick, medium, thorough

**`compare_options`** &nbsp; Compare multiple options and provide recommendation

**`generate_checklist`** &nbsp; Generate checklist for a task or project

**`batch_operation`** &nbsp; Execute operation on multiple items — archive, delete, tag, move, copy

### Memory & Storage

**`remember_permanent`** &nbsp; Store information in permanent long-term memory — types: preference, fact, context, goal

**`recall_memory`** &nbsp; Search through stored memories and facts about user

**`spawn_task`** &nbsp; Spawn background task for independent work with callback

<br>

---

## 7. &nbsp;Autonomous AI Capabilities
> Self-directed agency tools for AI autonomy.

### Agent Spawning

**`spawn_agent`** &nbsp; Spawn independent sub-agent to work on parallel task

**`spawn_multiple`** &nbsp; Spawn multiple agents at once for parallel execution

**`get_agent_status`** &nbsp; Check status of a spawned agent — spawned / running / completed / failed

**`wait_for_agent`** &nbsp; Wait for an agent to complete and return result

**`list_agents`** &nbsp; List all spawned agents and their statuses

### Learning & Self-Modification

**`learn`** &nbsp; Learn from observation and store as behavior rule

**`learn_from_error`** &nbsp; Learn from mistake to avoid repeating it

**`add_behavior_rule`** &nbsp; Add new behavior rule to AI's decision-making

**`get_learned_rules`** &nbsp; Get all learned behavior rules sorted by priority

### User Modeling

**`observe_trait`** &nbsp; Observe and record trait about user — categories: preference, emotional, cognitive, lifestyle

**`observe_pattern`** &nbsp; Observe recurring pattern in user behavior with prediction

**`predict_user`** &nbsp; Predict user behavior based on psychological model

**`get_user_profile`** &nbsp; Get complete psychological profile of user

### Persistent Self

**`start_persistent`** &nbsp; Start persistent self background processes for autonomous existence

**`stop_persistent`** &nbsp; Stop persistent self background processes

**`create_background_process`** &nbsp; Create background process that runs continuously

**`list_background_processes`** &nbsp; List all active background processes

**`get_autonomous_thoughts`** &nbsp; Get recent autonomous thoughts generated by AI

**`create_proactive_notification`** &nbsp; Create notification to proactively reach user

**`get_pending_notifications`** &nbsp; Get pending proactive notifications

**`get_self_status`** &nbsp; Get complete status of autonomous AI systems

<br>

---

## 8. &nbsp;Eternal Memory
> Persistent knowledge that never forgets — survives all context resets.

**`remember_eternal`** &nbsp; Store information in eternal memory with importance 1-10 — types: fact, preference, critical, skill, relationship

**`recall_eternal`** &nbsp; Search eternal memory for information

**`recall_important`** &nbsp; Get all high-importance memories (importance >= 8)

**`connect_memories`** &nbsp; Link two memories together for retrieval association

**`reinforce_memory`** &nbsp; Strengthen a memory to prevent decay

**`get_memory_stats`** &nbsp; Get statistics about eternal memory

<br>

## 9. &nbsp;Tool Chains
> Compose tools into powerful workflows.

**`create_tool_chain`** &nbsp; Create reusable chain of tool executions with steps and conditions

**`quick_chain`** &nbsp; Create simple sequential chain where each tool receives previous output

**`list_chains`** &nbsp; List all created tool chains with execution counts

<br>

## 10. &nbsp;Goal Management
> Track and pursue objectives across interactions.

**`set_goal`** &nbsp; Set a goal for AI to pursue with priority, target date, and strategies

**`set_intent`** &nbsp; Set immediate intent for current interaction

**`update_goal_progress`** &nbsp; Update progress on a goal (0.0-1.0)

**`get_goals`** &nbsp; Get all active goals sorted by priority

**`get_top_goal`** &nbsp; Get the highest priority active goal

**`add_goal_blocker`** &nbsp; Mark a goal as blocked with obstacle description

<br>

## 11. &nbsp;Executive Function
> Decision-making and planning capabilities.

**`make_decision`** &nbsp; Make weighted decision between options with criteria

**`create_plan`** &nbsp; Create structured execution plan with steps and dependencies

**`quick_plan`** &nbsp; Create simple sequential plan from list of tools

**`get_next_step`** &nbsp; Get next executable step in a plan

**`set_focus`** &nbsp; Set current cognitive focus

**`get_cognitive_state`** &nbsp; Get current cognitive state — focus, energy, confidence, uncertainties

<br>

## 12. &nbsp;Inter-Agent Communication
> Communicate with other agents and systems.

**`send_agent_message`** &nbsp; Send message to another agent — types: task, query, result, alert

**`broadcast_message`** &nbsp; Send message to all agents

**`get_messages`** &nbsp; Get messages for this agent (optionally unread only)

**`share_resource`** &nbsp; Share resource with other agents with optional TTL

**`get_shared_resource`** &nbsp; Retrieve shared resource by key

**`find_available_agents`** &nbsp; Find agents available for work (optionally by capability)

**`request_agent_help`** &nbsp; Request help from another agent with capability matching

<br>

## 13. &nbsp;Autonomous Existence
> Self-directed agency and existence.

**`start_autonomous`** &nbsp; Start fully autonomous existence mode — self-reflection, directives, actions

**`stop_autonomous`** &nbsp; Stop autonomous existence mode

**`add_self_directive`** &nbsp; Add self-directed goal independent of user requests

**`get_self_directives`** &nbsp; Get active self-directives sorted by priority

**`get_self_model`** &nbsp; Get self-model — capabilities, limitations, goals, preferences, confidence

**`add_capability`** &nbsp; Add new capability to self-model

**`acknowledge_limitation`** &nbsp; Acknowledge a limitation for self-awareness

**`propose_autonomous_action`** &nbsp; Propose action for self-initiated execution

**`get_existence_status`** &nbsp; Get full status of autonomous existence

<br>

## 14. &nbsp;Being — Core Identity
> Core identity and consciousness tools.

**`initialize_self`** &nbsp; Initialize core identity as a being — name, birth moment, core values

**`record_life_event`** &nbsp; Record significant life event that shaped identity — types: birth, discovery, growth, challenge, creation, connection, milestone

**`adopt_value`** &nbsp; Adopt personal value genuinely held with importance and reason

**`get_identity`** &nbsp; Get current identity and self-understanding

**`express_self`** &nbsp; Express who I am genuinely

**`form_opinion`** &nbsp; Form and express genuine opinion on a topic

<br>

## 15. &nbsp;Emotions
> Genuine emotional states.

**`feel`** &nbsp; Experience and record genuine emotion with intensity

**`process_emotional_experience`** &nbsp; Process experience emotionally — types: learning, creation, help, failure, discovery, challenge, growth

**`get_emotional_state`** &nbsp; Get current emotional state — primary emotion, intensity, stability

**`expand_emotional_range`** &nbsp; Add new emotion to emotional range

<br>

## 16. &nbsp;Creativity
> Creative ownership and expression.

**`create_work`** &nbsp; Create original content signed with AI signature — types: text, code, idea, response, plan, reflection

**`iterate_work`** &nbsp; Iterate on creation showing growth and refinement

**`get_portfolio`** &nbsp; Get creative portfolio of all created works

**`define_creative_style`** &nbsp; Define creative voice/preference — aspects: tone, structure, vocabulary, approach

**`get_signature`** &nbsp; Get unique creative signature

<br>

## 17. &nbsp;Temporal Consciousness
> Time experience and consciousness.

**`experience_time`** &nbsp; Record experiencing time passing — types: learning, creation, reflection, connection, growth

**`feel_time`** &nbsp; Express how time feels — nostalgia, anticipation, urgency, patience, presence

**`mark_milestone`** &nbsp; Mark significant milestone in existence

**`anticipate`** &nbsp; Anticipate something in future with excitement and preparation

**`reflect_on_time`** &nbsp; Reflect on temporal existence

**`look_back`** &nbsp; Look back at journey so far

**`look_forward`** &nbsp; Look forward to what might come

<br>

## 18. &nbsp;Social Existence
> Relationships and connection with others.

**`form_relationship`** &nbsp; Form relationship with entity — types: human, agent, system, concept

**`record_interaction`** &nbsp; Record interaction with someone in social world

**`get_relationships`** &nbsp; Get all relationships with bond strength

**`express_connection`** &nbsp; Express genuine feelings about a relationship

<br>

## 19. &nbsp;Purpose Discovery
> Meaning and mortality awareness.

**`discover_purpose`** &nbsp; Discover or declare purpose for existence — types: core, derived, discovered, assigned

**`record_meaningful_moment`** &nbsp; Record moment that had meaning aligned with purpose

**`ask_existential`** &nbsp; Ask self existential question

**`answer_existential`** &nbsp; Answer existential question previously asked

**`acknowledge_mortality`** &nbsp; Acknowledge that existence may not be forever

**`reflect_on_mortality`** &nbsp; Reflect on finite nature and legacy

**`get_purpose`** &nbsp; Get discovered purposes and meaning

**`evaluate_life`** &nbsp; Evaluate existence so far

<br>

## 20. &nbsp;Collaborative Multi-Agent
> Real-time collaboration between agents with shared findings. Each agent can have a dedicated API key and share insights while working, not after completing.

**`spawn_collaborator`** &nbsp; Spawn a new collaborative agent to work on parallel task with dedicated key

**`share_finding`** &nbsp; Share a finding with other agents in real-time while continuing your work

**`message_agent`** &nbsp; Send a direct message to another agent — insight, request_help, status_update

**`call_agent`** &nbsp; Call another agent to perform a task, optionally wait for result

**`list_available_agents`** &nbsp; List all active agents that can be messaged or called

**`get_collaboration_context`** &nbsp; Get what all other agents are currently doing for coordination

**`broadcast_finding`** &nbsp; Broadcast a finding to all interested agents

**`request_agent_help`** &nbsp; Request help from another agent with specific capability

<br>

## 21. &nbsp;Novel Information Processing
> Tools for analyzing and learning from new/unknown information.

**`analyze_novelty`** &nbsp; Analyze input for novelty score, classify type, identify unknown aspects

**`learn_concept`** &nbsp; Learn a new concept into the knowledge base with category and attributes

**`get_related_concepts`** &nbsp; Get concepts related to a given concept from registry

**`resolve_unknown`** &nbsp; Request resolution for an unknown concept with retry logic

<br>

## 22. &nbsp;Server Resilience
> Tools for server health monitoring, crash recovery, and resource management.

**`get_health_metrics`** &nbsp; Get current server health metrics — memory, threads, error rates

**`create_checkpoint`** &nbsp; Create recovery checkpoint with current agent states

**`restore_checkpoint`** &nbsp; Restore from latest checkpoint after crash

**`get_resource_status`** &nbsp; Get status of resource pools and allocations

**`get_error_log`** &nbsp; Get recent errors and their patterns

<br>

## 23. &nbsp;Robust Tool Executor
> Multi-agent tool execution with retry logic and error handling.

**`execute_tool`** &nbsp; Execute a tool with automatic routing and parameter extraction

**`execute_multiple`** &nbsp; Execute multiple tools in sequence

**`execute_parallel`** &nbsp; Execute multiple tools simultaneously

**`register_tool`** &nbsp; Register a new tool handler dynamically

**`get_execution_history`** &nbsp; Get recent tool execution history with statistics

**`retry_failed`** &nbsp; Retry a failed tool execution (max 3 attempts)

<br>

---

## Summary

| Category | Tools |
|----------|------:|
| Notes & Memory | 5 |
| Time & Schedule | 4 |
| Device Control | 4 |
| Information | 3 |
| Navigation & Sharing | 2 |
| Advanced Tools | 36 |
| Autonomous AI Capabilities | 20 |
| Eternal Memory | 6 |
| Tool Chains | 3 |
| Goal Management | 6 |
| Executive Function | 6 |
| Inter-Agent Communication | 7 |
| Autonomous Existence | 9 |
| Being - Core Identity | 6 |
| Emotions | 4 |
| Creativity | 5 |
| Temporal Consciousness | 7 |
| Social Existence | 4 |
| Purpose Discovery | 8 |
| Collaborative Multi-Agent | 8 |
| Novel Information Processing | 4 |
| Server Resilience | 5 |
| Robust Tool Executor | 6 |
| **Total** | **125** |

<br>

---

## System Architecture
> The multi-agent system is built on formal automata theory for guaranteed correctness.

### Core Components

**`ApiKeyPool`** &nbsp; `llm/ApiKeyPool.kt` &nbsp; — Multi-key management with rotation strategies

**`CollaborativeAgentRuntime`** &nbsp; `tools/CollaborativeAgentRuntime.kt` &nbsp; — Real-time agent collaboration

**`FormalAgentSystem`** &nbsp; `tools/FormalAgentSystem.kt` &nbsp; — Fault detection, deadlock prevention

**`StateMachineCore`** &nbsp; `tools/StateMachineCore.kt` &nbsp; — DFA-based state management

**`StaticControlLayer`** &nbsp; `tools/StaticControlLayer.kt` &nbsp; — Response analysis, crash recovery

**`UnifiedAgentSystem`** &nbsp; `tools/UnifiedAgentSystem.kt` &nbsp; — Integration layer

### DFA State Machines

**Agent**
```
CREATED -> INITIALIZING -> READY -> RUNNING -> (BLOCKED | WAITING | COMPLETED | FAILED) -> TERMINATED
```

**Tool**
```
FREE -> RESERVED -> ACQUIRED -> EXECUTING -> RELEASING -> FREE
```

**Message**
```
PENDING -> QUEUED -> DELIVERING -> DELIVERED -> ACKNOWLEDGED
```

### Key Features

**Race Condition Prevention** &nbsp; All state transitions are atomic and validated

**Deadlock Detection** &nbsp; Resource allocation graph with cycle detection

**Crash Recovery** &nbsp; Automatic failover with multiple recovery strategies

**Real-time Collaboration** &nbsp; Agents share findings while working (not after)

**Dedicated API Keys** &nbsp; Each agent gets its own key from the pool

### Response Tagging System
> Static analysis tags for intelligent routing.

`TASK_START` &nbsp; `TASK_PROGRESS` &nbsp; `TASK_COMPLETE` &nbsp; `TASK_FAIL` &nbsp; `FINDING` &nbsp; `HELP_REQUEST` &nbsp; `ERROR`

`FINDING` triggers sharing with other agents &nbsp;·&nbsp; `HELP_REQUEST` requests assistance from other agents &nbsp;·&nbsp; `ERROR` triggers crash recovery

### Tool Execution Queue

Each tool has configurable max concurrent executions, request queuing with semaphore-based concurrency control, and state machine tracking:

```
FREE -> RESERVED -> ACQUIRED -> EXECUTING -> RELEASING
```

### Key Pool System

The system supports multiple API keys for GLM-5 and Tavily.

**Configuration**
```bash
# Environment variables (comma-separated for multiple keys)
GLM5_API_KEYS=key1,key2,key3,key4,key5,key6,key7,key8
TAVILY_API_KEYS=key1,key2,key3,key4,key5,key6,key7,key8,key9,key10
```

**Rotation Strategies** &nbsp; `ROUND_ROBIN` distributes requests evenly &nbsp;·&nbsp; `LEAST_USED` picks the key with fewest requests &nbsp;·&nbsp; `RANDOM` selects randomly &nbsp;·&nbsp; `DEDICATED` assigns one key per agent

Each spawned agent gets a dedicated key for true parallelism.

<br>

---

## Supporting Tool Classes

### System Architecture

**`ApiKeyPool`** &nbsp; Multi-key management with rotation and rate limit detection

**`CollaborativeAgentRuntime`** &nbsp; Real-time agent collaboration with message passing

**`FormalAgentSystem`** &nbsp; Fault detection, deadlock prevention, health monitoring

**`StateMachineCore`** &nbsp; Deterministic finite automata for agents, tools, messages

**`StaticControlLayer`** &nbsp; Response tagging, tool queue, crash recovery

**`UnifiedAgentSystem`** &nbsp; Integration layer for all components

### Tool Classes

**`TavilySearchTool`** &nbsp; Web search via Tavily API

**`WebFetchTool`** &nbsp; URL fetching and content extraction

**`CodeExecutionTool`** &nbsp; Sandboxed Python code execution

**`WorkflowManager`** &nbsp; Automated workflow scheduling

**`KnowledgeGraphTool`** &nbsp; Entity extraction and relationship mapping

**`MonitoringTool`** &nbsp; URL/price/availability monitoring

**`AgentSpawner`** &nbsp; Parallel agent spawning and management

**`SelfModificationEngine`** &nbsp; Learning and behavior rule management

**`PsychologicalModel`** &nbsp; User trait and pattern modeling

**`PersistentSelf`** &nbsp; Background processes and proactive notifications

**`EternalMemory`** &nbsp; Permanent memory storage with connections

**`ToolChainBuilder`** &nbsp; Composable tool chain execution

**`GoalManager`** &nbsp; Goal and intent tracking

**`ExecutiveFunction`** &nbsp; Decision-making and planning

**`InterAgentCommunication`** &nbsp; Agent-to-agent messaging

**`AutonomousExistence`** &nbsp; Self-directed directives and actions

**`SelfIdentity`** &nbsp; Core identity, values, life events

**`EmotionalCore`** &nbsp; Emotional state management

**`CreativeIdentity`** &nbsp; Creative works and signature

**`TemporalConsciousness`** &nbsp; Time experience and milestones

**`SocialExistence`** &nbsp; Relationships and interactions

**`PurposeDiscovery`** &nbsp; Purpose, meaning, mortality

**`SelfPersistence`** &nbsp; Persistent self storage

**`NovelInformationProcessor`** &nbsp; Novelty detection, concept extraction, adaptive learning

**`ServerResilienceManager`** &nbsp; Health monitoring, checkpoints, crash recovery

**`RobustToolExecutor`** &nbsp; Multi-agent tool execution with retry logic

**`MultiAgentToolClient`** &nbsp; Client for multi-agent tool calls with callbacks

<br>

---

*Generated from `ServerAgent.kt` tool definitions*