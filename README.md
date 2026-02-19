# feat/chain-of-Tool Branch

This branch focuses on **advanced tool capabilities** and **sophisticated chain-of-tool interactions** where the AI model can execute multiple tools in sequence to achieve complex goals.

---

## Overview

This branch extends Smarty with advanced AI tools that enable:

1. **Code Execution** - Run Python code server-side
2. **Web Interaction** - Fetch URLs, extract links, browse the web
3. **Workflow Automation** - Create scheduled/recurring workflows
4. **Knowledge Graph** - Entity extraction and relationship mapping
5. **Monitoring** - Track changes over time (prices, availability, content)
6. **Data Processing** - Transform between formats (JSON, CSV, YAML, XML)
7. **Task Management** - Tasks, projects, and productivity tracking
8. **Memory & Context** - Long-term persistent memory
9. **Intelligent Suggestions** - AI-powered recommendations

---

## Tool Categories

### 📡 Web Interaction (5 tools)

| Tool | Description |
|------|-------------|
| `fetch_url` | Fetch and parse content from any URL |
| `extract_links` | Extract all links from a webpage |
| `search_web` | Search the internet via Tavily API |
| `parallel_search` | Execute multiple searches simultaneously |
| `deep_research` | Comprehensive multi-source research |

### 🖥️ Code Execution (1 tool)

| Tool | Description |
|------|-------------|
| `execute_code` | Execute Python code server-side (30s timeout, isolated) |

### 🔄 Workflow & Automation (3 tools)

| Tool | Description |
|------|-------------|
| `create_workflow` | Create automated workflows with triggers |
| `list_workflows` | View active workflows |
| `delete_workflow` | Remove workflows |

### 🧠 Knowledge Graph (4 tools)

| Tool | Description |
|------|-------------|
| `extract_entities` | Extract named entities (people, places, orgs, dates) |
| `build_knowledge_graph` | Build graph of entities and relationships |
| `find_connections` | Find related entities in the graph |
| `graph_stats` | Get knowledge graph statistics |

### 📡 Monitoring (4 tools)

| Tool | Description |
|------|-------------|
| `create_monitor` | Create monitors for URLs, prices, availability |
| `list_monitors` | View active monitors |
| `check_monitor` | Manually check a monitor |
| `delete_monitor` | Remove monitors |

### 📊 Data Processing (4 tools)

| Tool | Description |
|------|-------------|
| `transform_data` | Convert between JSON, CSV, YAML, XML, Properties |
| `extract_structured` | Extract emails, phones, URLs, dates, money from text |
| `analyze_data` | Statistical and trend analysis |
| `batch_operation` | Execute operations on multiple items |

### 📋 Task Management (7 tools)

| Tool | Description |
|------|-------------|
| `create_task` | Create tasks with due dates and priorities |
| `list_tasks` | List and filter tasks |
| `complete_task` | Mark tasks complete |
| `create_project` | Create projects to organize work |
| `project_status` | Get project progress overview |
| `suggest_next` | AI-powered next action recommendations |
| `smart_reminder` | Context-aware reminders |

### 🧠 Memory & Context (3 tools)

| Tool | Description |
|------|-------------|
| `remember_permanent` | Store long-term facts about user |
| `recall_memory` | Search stored memories |
| `remember_fact` | Remember user preferences and facts |

### 🛠️ Utilities (6 tools)

| Tool | Description |
|------|-------------|
| `plan_execution` | Multi-step goal decomposition |
| `compare_options` | Compare multiple options with criteria |
| `generate_checklist` | Create structured checklists |
| `summarize_content` | Summarize text or URLs |
| `quick_capture` | Rapid thought capture with auto-categorization |
| `review_day` | Daily reflection and summary |
| `time_analysis` | Analyze time usage patterns |
| `spawn_task` | Background task execution |

---

## Model Constraints

This branch is configured to work with **GLM-5** via Modal's endpoint:

- **Endpoint**: `https://api.us-west-2.modal.direct/v1/chat/completions`
- **Model**: `zai-org/GLM-5-FP8`
- **Format**: OpenAI-compatible API
- **Capabilities**: ✅ Function calling, ✅ 128K context, ✅ Reasoning mode
- **Limitations**: ❌ No multimodal/vision (GLM-5 is text-only)

For vision capabilities, consider using `GLM-4.6V` which supports image understanding.

---

## New Tool Files

```
server/src/main/kotlin/com/example/smarty/server/tools/
├── WebFetchTool.kt          # URL fetching and content extraction
├── CodeExecutionTool.kt     # Python code execution
├── WorkflowManager.kt       # Workflow scheduling and management
├── KnowledgeGraphTool.kt    # Entity extraction and relationships
└── MonitoringTool.kt        # Change tracking and monitoring
```

---

## Example Tool Chains

### Research Workflow
```
User: "Research the best laptops for programming under $1500"

Tool Chain:
1. deep_research(topic="best programming laptops under $1500", depth="thorough")
2. compare_options(options=[...laptops found], criteria="price, performance, battery")
3. save_note(title="Laptop Research", content=comparison_results)
```

### Price Monitoring Setup
```
User: "Alert me when this item goes on sale: https://store.com/product/123"

Tool Chain:
1. create_monitor(type="price", target="https://store.com/product/123", interval="6 hours")
2. remember_permanent(content="User wants to buy product from store.com", type="goal")
```

### Knowledge Extraction
```
User: "Extract all the people and companies from this article: https://news.com/article"

Tool Chain:
1. fetch_url(url="https://news.com/article")
2. build_knowledge_graph(text=article_content)
3. save_note(title="Entities from Article", content=graph_results)
```

### Task Planning
```
User: "Help me plan my trip to Tokyo next month"

Tool Chain:
1. plan_execution(goal="Plan trip to Tokyo next month")
2. create_project(name="Tokyo Trip")
3. generate_checklist(topic="international travel")
4. create_task(title="Book flights", due="2 weeks", project="Tokyo Trip")
5. create_task(title="Reserve hotel", due="2 weeks", project="Tokyo Trip")
```

---

## Architecture Notes

### Tool Execution Flow

1. **LLM receives query** → Decides which tools to call
2. **Tool call detected** → Execute server-side
3. **Tool result** → Feed back to LLM
4. **LLM continues** → May call more tools or produce final answer
5. **Loop until complete** → Maximum 5 iterations, 50 tool calls

### Safety Limits

- `MAX_EXECUTION_TIME_MS = 30 minutes`
- `MAX_TOOL_CALLS = 50 per session`
- `MAX_ITERATIONS = 100 LLM iterations`
- Code execution: `30 seconds timeout, isolated filesystem`

---

## Recent Commits

```
525c81c feat: Add task/project management and intelligent suggestion tools
958b0c7 feat: Add monitoring and data processing capabilities
f2139aa feat: Add knowledge graph capabilities
6cecf3c feat: Add advanced chain-of-tool capabilities
3328812 Add README for feat/chain-of-Tool branch
```

---

## Future Considerations

Tools I would add if multimodal support was available:
- `analyze_image` - Image understanding and OCR
- `generate_image` - Text-to-image generation
- `transcribe_audio` - Speech-to-text

Tools that would require additional infrastructure:
- `send_message` - Email/SMS sending
- `api_client` - Universal API connector
- `web_automation` - Browser control for form filling
