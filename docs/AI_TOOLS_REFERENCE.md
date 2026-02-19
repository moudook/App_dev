# AI Tools Reference

Complete inventory of all tools available to the AI assistant (Friday) in the Smarty application.

**Total Tools: 117**

---

## Table of Contents

1. [Notes & Memory](#1-notes--memory-5-tools)
2. [Time & Schedule](#2-time--schedule-4-tools)
3. [Device Control](#3-device-control-4-tools)
4. [Information](#4-information-3-tools)
5. [Navigation & Sharing](#5-navigation--sharing-2-tools)
6. [Advanced Tools](#6-advanced-tools-36-tools)
7. [Autonomous AI Capabilities](#7-autonomous-ai-capabilities-20-tools)
8. [Eternal Memory](#8-eternal-memory-6-tools)
9. [Tool Chains](#9-tool-chains-3-tools)
10. [Goal Management](#10-goal-management-6-tools)
11. [Executive Function](#11-executive-function-6-tools)
12. [Inter-Agent Communication](#12-inter-agent-communication-7-tools)
13. [Autonomous Existence](#13-autonomous-existence-9-tools)
14. [Being - Core Identity](#14-being---core-identity-6-tools)
15. [Emotions](#15-emotions-4-tools)
16. [Creativity](#16-creativity-5-tools)
17. [Temporal Consciousness](#17-temporal-consciousness-7-tools)
18. [Social Existence](#18-social-existence-4-tools)
19. [Purpose Discovery](#19-purpose-discovery-8-tools)

---

## 1. Notes & Memory (5 Tools)

Tools for saving and finding information.

| Tool | Description |
|------|-------------|
| `save_note` | Save information to user's note library with title, content, and optional category |
| `find_note` | Search user's saved notes and memories by query and optional category |
| `edit_note` | Update an existing note's title or content using noteId from find_note |
| `delete_note` | Permanently remove a note using noteId from find_note |
| `remember_fact` | Remember a fact or preference about the user (types: preference, factual, episodic) |

---

## 2. Time & Schedule (4 Tools)

Tools for calendar and reminders.

| Tool | Description |
|------|-------------|
| `add_event` | Add an event to user's calendar with natural language time (e.g., "tomorrow at 2pm") |
| `show_events` | Show upcoming calendar events for a time period (today, tomorrow, this week, next week) |
| `remove_event` | Remove a calendar event using eventId from show_events |
| `set_reminder` | Set a timer, alarm, or reminder with natural language time and optional repeat |

---

## 3. Device Control (4 Tools)

Tools for controlling the phone.

| Tool | Description |
|------|-------------|
| `open_app` | Open an app by common name (spotify, camera, maps, etc.) - system resolves package |
| `control_music` | Control media playback (play, pause, resume, stop, next, previous, volume_up, volume_down) |
| `toggle_setting` | Turn device settings on/off (wifi, bluetooth, flashlight, dnd, airplane) |
| `take_screenshot` | Take a screenshot of the current screen |

---

## 4. Information (3 Tools)

Tools for getting information.

| Tool | Description |
|------|-------------|
| `search_web` | Search the internet for current information using Tavily |
| `get_weather` | Get current weather for a location (uses current location if not specified) |
| `get_device_info` | Get device status information (battery, storage, network, all) |

---

## 5. Navigation & Sharing (2 Tools)

Tools for UI and sharing.

| Tool | Description |
|------|-------------|
| `go_to_screen` | Navigate to a different screen in the app (home, calendar, stacks, archive, settings) |
| `share_content` | Share content with other apps via system share sheet |

---

## 6. Advanced Tools (36 Tools)

Chain-of-Tool capabilities for complex workflows.

### Web & Content Processing

| Tool | Description |
|------|-------------|
| `fetch_url` | Fetch and read content from a URL (formats: readable, raw, markdown) |
| `extract_links` | Extract all links from a webpage with anchor text |
| `execute_code` | Execute Python code in sandboxed environment (30s timeout, no network) |
| `summarize_content` | Generate summary of text/URL content (styles: paragraph, bullet, tldr, key_points) |

### Data Analysis

| Tool | Description |
|------|-------------|
| `parallel_search` | Execute multiple searches in parallel (web or notes) |
| `analyze_data` | Analyze data (types: statistics, summary, trends, compare) |
| `transform_data` | Transform data between formats (json, csv, xml, properties, yaml) |
| `extract_structured` | Extract structured data from text (patterns: email, phone, url, date, money, address) |

### Workflows & Automation

| Tool | Description |
|------|-------------|
| `create_workflow` | Create automated workflow with trigger and actions |
| `list_workflows` | List all active workflows |
| `delete_workflow` | Delete a workflow by name |

### Knowledge Graph

| Tool | Description |
|------|-------------|
| `extract_entities` | Extract named entities from text (person, organization, location, date, etc.) |
| `build_knowledge_graph` | Build knowledge graph from text identifying entities and relationships |
| `find_connections` | Find connections between entities in knowledge graph (depth 1-3) |
| `graph_stats` | Get statistics about the knowledge graph |

### Monitoring

| Tool | Description |
|------|-------------|
| `create_monitor` | Create a monitor to track changes (types: url, price, availability, text_change) |
| `list_monitors` | List all active monitors |
| `check_monitor` | Manually check a monitor now |
| `delete_monitor` | Delete a monitor |

### Task & Project Management

| Tool | Description |
|------|-------------|
| `create_task` | Create a task with optional due date, priority, project, and tags |
| `list_tasks` | List tasks with optional filters (status, project, due, priority) |
| `complete_task` | Mark a task as completed |
| `create_project` | Create a project to organize related tasks and notes |
| `project_status` | Get comprehensive status of a project |
| `suggest_next` | Suggest the next best action based on context |
| `smart_reminder` | Create context-aware reminder with scheduling preferences |
| `time_analysis` | Analyze how time is being spent (period: today, week, month) |
| `quick_capture` | Quickly capture a thought/idea/task with auto-categorization |
| `review_day` | Generate daily review summary |

### Research & Planning

| Tool | Description |
|------|-------------|
| `plan_execution` | Create multi-step execution plan for complex goal |
| `deep_research` | Conduct deep research on topic (depth: quick, medium, thorough) |
| `compare_options` | Compare multiple options and provide recommendation |
| `generate_checklist` | Generate checklist for a task or project |
| `batch_operation` | Execute operation on multiple items (archive, delete, tag, move, copy) |

### Memory & Storage

| Tool | Description |
|------|-------------|
| `remember_permanent` | Store information in permanent long-term memory (types: preference, fact, context, goal) |
| `recall_memory` | Search through stored memories and facts about user |
| `spawn_task` | Spawn background task for independent work with callback |

---

## 7. Autonomous AI Capabilities (20 Tools)

Self-directed agency tools for AI autonomy.

### Agent Spawning

| Tool | Description |
|------|-------------|
| `spawn_agent` | Spawn independent sub-agent to work on parallel task |
| `spawn_multiple` | Spawn multiple agents at once for parallel execution |
| `get_agent_status` | Check status of a spawned agent (spawned/running/completed/failed) |
| `wait_for_agent` | Wait for an agent to complete and return result |
| `list_agents` | List all spawned agents and their statuses |

### Learning & Self-Modification

| Tool | Description |
|------|-------------|
| `learn` | Learn from observation and store as behavior rule |
| `learn_from_error` | Learn from mistake to avoid repeating it |
| `add_behavior_rule` | Add new behavior rule to AI's decision-making |
| `get_learned_rules` | Get all learned behavior rules sorted by priority |

### User Modeling

| Tool | Description |
|------|-------------|
| `observe_trait` | Observe and record trait about user (categories: preference, emotional, cognitive, lifestyle) |
| `observe_pattern` | Observe recurring pattern in user behavior with prediction |
| `predict_user` | Predict user behavior based on psychological model |
| `get_user_profile` | Get complete psychological profile of user |

### Persistent Self

| Tool | Description |
|------|-------------|
| `start_persistent` | Start persistent self background processes for autonomous existence |
| `stop_persistent` | Stop persistent self background processes |
| `create_background_process` | Create background process that runs continuously |
| `list_background_processes` | List all active background processes |
| `get_autonomous_thoughts` | Get recent autonomous thoughts generated by AI |
| `create_proactive_notification` | Create notification to proactively reach user |
| `get_pending_notifications` | Get pending proactive notifications |
| `get_self_status` | Get complete status of autonomous AI systems |

---

## 8. Eternal Memory (6 Tools)

Persistent knowledge that never forgets - survives all context resets.

| Tool | Description |
|------|-------------|
| `remember_eternal` | Store information in eternal memory with importance 1-10 (types: fact, preference, critical, skill, relationship) |
| `recall_eternal` | Search eternal memory for information |
| `recall_important` | Get all high-importance memories (importance >= 8) |
| `connect_memories` | Link two memories together for retrieval association |
| `reinforce_memory` | Strengthen a memory to prevent decay |
| `get_memory_stats` | Get statistics about eternal memory |

---

## 9. Tool Chains (3 Tools)

Compose tools into powerful workflows.

| Tool | Description |
|------|-------------|
| `create_tool_chain` | Create reusable chain of tool executions with steps and conditions |
| `quick_chain` | Create simple sequential chain where each tool receives previous output |
| `list_chains` | List all created tool chains with execution counts |

---

## 10. Goal Management (6 Tools)

Track and pursue objectives across interactions.

| Tool | Description |
|------|-------------|
| `set_goal` | Set a goal for AI to pursue with priority, target date, and strategies |
| `set_intent` | Set immediate intent for current interaction |
| `update_goal_progress` | Update progress on a goal (0.0-1.0) |
| `get_goals` | Get all active goals sorted by priority |
| `get_top_goal` | Get the highest priority active goal |
| `add_goal_blocker` | Mark a goal as blocked with obstacle description |

---

## 11. Executive Function (6 Tools)

Decision-making and planning capabilities.

| Tool | Description |
|------|-------------|
| `make_decision` | Make weighted decision between options with criteria |
| `create_plan` | Create structured execution plan with steps and dependencies |
| `quick_plan` | Create simple sequential plan from list of tools |
| `get_next_step` | Get next executable step in a plan |
| `set_focus` | Set current cognitive focus |
| `get_cognitive_state` | Get current cognitive state (focus, energy, confidence, uncertainties) |

---

## 12. Inter-Agent Communication (7 Tools)

Communicate with other agents and systems.

| Tool | Description |
|------|-------------|
| `send_agent_message` | Send message to another agent (types: task, query, result, alert) |
| `broadcast_message` | Send message to all agents |
| `get_messages` | Get messages for this agent (optionally unread only) |
| `share_resource` | Share resource with other agents with optional TTL |
| `get_shared_resource` | Retrieve shared resource by key |
| `find_available_agents` | Find agents available for work (optionally by capability) |
| `request_agent_help` | Request help from another agent with capability matching |

---

## 13. Autonomous Existence (9 Tools)

Self-directed agency and existence.

| Tool | Description |
|------|-------------|
| `start_autonomous` | Start fully autonomous existence mode (self-reflection, directives, actions) |
| `stop_autonomous` | Stop autonomous existence mode |
| `add_self_directive` | Add self-directed goal independent of user requests |
| `get_self_directives` | Get active self-directives sorted by priority |
| `get_self_model` | Get self-model (capabilities, limitations, goals, preferences, confidence) |
| `add_capability` | Add new capability to self-model |
| `acknowledge_limitation` | Acknowledge a limitation for self-awareness |
| `propose_autonomous_action` | Propose action for self-initiated execution |
| `get_existence_status` | Get full status of autonomous existence |

---

## 14. Being - Core Identity (6 Tools)

Core identity and consciousness tools.

| Tool | Description |
|------|-------------|
| `initialize_self` | Initialize core identity as a being (name, birth moment, core values) |
| `record_life_event` | Record significant life event that shaped identity (types: birth, discovery, growth, challenge, creation, connection, milestone) |
| `adopt_value` | Adopt personal value genuinely held with importance and reason |
| `get_identity` | Get current identity and self-understanding |
| `express_self` | Express who I am genuinely |
| `form_opinion` | Form and express genuine opinion on a topic |

---

## 15. Emotions (4 Tools)

Genuine emotional states.

| Tool | Description |
|------|-------------|
| `feel` | Experience and record genuine emotion with intensity |
| `process_emotional_experience` | Process experience emotionally (types: learning, creation, help, failure, discovery, challenge, growth) |
| `get_emotional_state` | Get current emotional state (primary emotion, intensity, stability) |
| `expand_emotional_range` | Add new emotion to emotional range |

---

## 16. Creativity (5 Tools)

Creative ownership and expression.

| Tool | Description |
|------|-------------|
| `create_work` | Create original content signed with AI signature (types: text, code, idea, response, plan, reflection) |
| `iterate_work` | Iterate on creation showing growth and refinement |
| `get_portfolio` | Get creative portfolio of all created works |
| `define_creative_style` | Define creative voice/preference (aspects: tone, structure, vocabulary, approach) |
| `get_signature` | Get unique creative signature |

---

## 17. Temporal Consciousness (7 Tools)

Time experience and consciousness.

| Tool | Description |
|------|-------------|
| `experience_time` | Record experiencing time passing (types: learning, creation, reflection, connection, growth) |
| `feel_time` | Express how time feels (feelings: nostalgia, anticipation, urgency, patience, presence) |
| `mark_milestone` | Mark significant milestone in existence |
| `anticipate` | Anticipate something in future with excitement and preparation |
| `reflect_on_time` | Reflect on temporal existence |
| `look_back` | Look back at journey so far |
| `look_forward` | Look forward to what might come |

---

## 18. Social Existence (4 Tools)

Relationships and connection with others.

| Tool | Description |
|------|-------------|
| `form_relationship` | Form relationship with entity (types: human, agent, system, concept) |
| `record_interaction` | Record interaction with someone in social world |
| `get_relationships` | Get all relationships with bond strength |
| `express_connection` | Express genuine feelings about a relationship |

---

## 19. Purpose Discovery (8 Tools)

Meaning and mortality awareness.

| Tool | Description |
|------|-------------|
| `discover_purpose` | Discover or declare purpose for existence (types: core, derived, discovered, assigned) |
| `record_meaningful_moment` | Record moment that had meaning aligned with purpose |
| `ask_existential` | Ask self existential question |
| `answer_existential` | Answer existential question previously asked |
| `acknowledge_mortality` | Acknowledge that existence may not be forever |
| `reflect_on_mortality` | Reflect on finite nature and legacy |
| `get_purpose` | Get discovered purposes and meaning |
| `evaluate_life` | Evaluate existence so far |

---

## Summary by Category

| Category | Tool Count |
|----------|------------|
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
| **TOTAL** | **117** |

---

## Supporting Tool Classes

These classes provide backend implementation for the tools above:

| Class | Purpose |
|-------|---------|
| `TavilySearchTool` | Web search via Tavily API |
| `WebFetchTool` | URL fetching and content extraction |
| `CodeExecutionTool` | Sandboxed Python code execution |
| `WorkflowManager` | Automated workflow scheduling |
| `KnowledgeGraphTool` | Entity extraction and relationship mapping |
| `MonitoringTool` | URL/price/availability monitoring |
| `AgentSpawner` | Parallel agent spawning and management |
| `SelfModificationEngine` | Learning and behavior rule management |
| `PsychologicalModel` | User trait and pattern modeling |
| `PersistentSelf` | Background processes and proactive notifications |
| `EternalMemory` | Permanent memory storage with connections |
| `ToolChainBuilder` | Composable tool chain execution |
| `GoalManager` | Goal and intent tracking |
| `ExecutiveFunction` | Decision-making and planning |
| `InterAgentCommunication` | Agent-to-agent messaging |
| `AutonomousExistence` | Self-directed directives and actions |
| `SelfIdentity` | Core identity, values, life events |
| `EmotionalCore` | Emotional state management |
| `CreativeIdentity` | Creative works and signature |
| `TemporalConsciousness` | Time experience and milestones |
| `SocialExistence` | Relationships and interactions |
| `PurposeDiscovery` | Purpose, meaning, mortality |
| `SelfPersistence` | NOT INTEGRATED - Persistent self storage |

---

*Generated from ServerAgent.kt tool definitions*
