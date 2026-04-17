# Multi-Agent Configuration System — Implementation Plan

> Created: 2026-04-16
> Status: Ready for execution

## Overview

Replace hardcoded/monolithic agent config with file-directory-driven multi-agent system.
Each agent has its own `agents/<id>/` directory with `SOUL.md` + `config.yaml`.
Aligns with desktop OpenClaw's `~/.openclaw/agents/<id>/` structure.

## Target Directory Structure

```
/sdcard/Android/data/ai.openclaw.android/files/
├── agents/
│   ├── main/
│   │   ├── SOUL.md         ← agent personality/role
│   │   └── config.yaml     ← model, tools, routing
│   └── coder/
│       ├── SOUL.md
│       └── config.yaml
├── agents.json             ← registry (id, name, enabled)
└── system_prompt.md        ← global default prompt (fallback)
```

## Key Design Decisions

- **YAML format** for config (readability first)
- **Lazy session loading** — configs loaded at startup, AgentSession created on-demand
- **Shared resources** — ModelClient, SkillManager, DynamicSkillManager shared across agents
- **SnakeYAML** dependency for YAML parsing

---

## Task List

### T1: Add SnakeYAML dependency
**File:** `app/build.gradle.kts`
- Add `implementation("org.yaml:snakeyaml:2.2")` to dependencies

### T2: Create AgentConfig data class
**New file:** `app/src/main/java/ai/openclaw/android/config/AgentConfig.kt`
```kotlin
data class AgentConfig(
    val id: String,
    val name: String,
    val model: String,               // e.g. "bailian/qwen3.6-plus"
    val systemPromptPath: String?,   // relative path to SOUL.md
    val tools: List<String>,         // enabled tool IDs
    val routing: RoutingConfig?,
    val maxContextTokens: Int = 4000
)
```

### T3: Create AgentRegistry
**New file:** `app/src/main/java/ai/openclaw/android/agent/AgentRegistry.kt`

Responsibilities:
- Scan `agents/` directory on startup
- Parse each agent's `config.yaml` + `SOUL.md`
- Lazy-load AgentSession per agent
- Provide `getSession(agentId): AgentSession`
- Provide `listAgents(): List<AgentConfig>`
- Provide `createAgent(id, name, model): AgentConfig` — creates directory + default files
- Provide `deleteAgent(id): Boolean` — removes directory

### T4: Refactor SystemPromptLoader → AgentPromptLoader
**Modify:** `app/src/main/java/ai/openclaw/android/agent/SystemPromptLoader.kt`

Changes:
- Add `loadForAgent(agentId): String` — reads from `agents/<id>/SOUL.md`
- Keep existing `load()` as global fallback
- First launch: copy default `main/SOUL.md` from assets

### T5: Modify AgentSession to accept AgentConfig
**Modify:** `app/src/main/java/ai/openclaw/android/agent/AgentSession.kt`

Changes:
- Add `setAgentConfig(config: AgentConfig)` method
- Use `config.maxContextTokens` instead of hardcoded 4000
- System prompt loaded via AgentPromptLoader for the agent's ID

### T6: Modify GatewayManager to use AgentRegistry
**Modify:** `app/src/main/java/ai/openclaw/android/GatewayManager.kt`

Changes:
- Initialize `AgentRegistry` in `initializeComponents()`
- Replace direct `AgentSession` creation with `registry.getSession("main")`
- Keep `sendMessage(text)` routing to main agent for now
- Add `sendMessageToAgent(agentId, text): Flow<SessionEvent>` for future multi-agent routing

### T7: Create default agent directory structure
**New files:** `app/src/main/assets/agents/main/SOUL.md` and `app/src/main/assets/agents/main/config.yaml`

Default SOUL.md:
```markdown
You are an AI assistant on an Android device with tool access.
(keep current default prompt content)
```

Default config.yaml:
```yaml
id: main
name: 主助手
model: bailian/qwen3.6-plus
maxContextTokens: 4000
tools:
  - weather
  - search
  - reminder
  - translate
  - generate_skill
```

### T8: Create agents.json registry file
**New file:** `app/src/main/assets/agents.json`

```json
[
  {"id": "main", "name": "主助手", "enabled": true}
]
```

### T9: Add agent management tools
**New file:** `app/src/main/java/ai/openclaw/android/skill/builtin/AgentManagementSkill.kt`

Tools:
- `list_agents` — list all configured agents
- `create_agent` — create a new agent (id, name, model)
- `delete_agent` — remove an agent
- `get_agent_config` — get an agent's config

Register this skill in GatewayManager alongside existing skills.

---

## Dependencies

- SnakeYAML 2.2 (T1 must be done first)
- Existing SkillManager, DynamicSkillManager (no changes needed)
- Existing ConfigManager (no changes needed)

## Execution Order

T1 → T2 → T4 → T3 → T5 → T7+T8 → T6 → T9

## Verification

- Build: `./gradlew assembleDebug` should succeed
- Install: APK installs without errors
- Launch: App starts, loads main agent config from file
- Edit: Modify SOUL.md on device → restart App → changes apply
- Create: Use `create_agent` tool → new agent directory created → usable
