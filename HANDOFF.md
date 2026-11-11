# AgentPlayers handoff (paused 2026-11-11, usage limit)

The project has **not been compiled yet**. Expect some compile errors to fix on the first `./gradlew build`. Never run `gradlew clean`.

## Done (src/main/java/dev/maddie/agents)
- `AgentConfig`: reads and writes `config/agentplayers.json` (token hashes, owner, skin, allow_commands, allow_self_join, port 25591, view_distance, calls_per_minute, idle_minutes).
- `mcp/`:
  - Ported files: `Args`, `Tool`, `ToolResult`, `ToolException`.
  - `Tool` takes `(name, desc, schema, readOnly[, needsBody], (agent, args) -> ...)`.
  - `McpServer` serves one `/mcp` endpoint for all agents. It accepts a Bearer token or `/mcp/<token>`, applies the rate limit, and drains events.
- `agent/`:
  - `AgentConnection`: a Connection on an EmbeddedChannel. `send()` passes packets to `Agent.onPacket`. `NetworkRegistry.configureMockConnection` negotiates every mod channel.
  - `AgentPlayer`: overrides `tick()` to run `beforeTick`, `resetPosition` every 10 ticks, then `super.tick` + `doTick` + `chunkSource.move`. The connection is never ticked.
  - `Body`: holds the keys and calls `apply()` each tick. Every action is a client packet sent into its own `ServerGamePacketListenerImpl`. Chat goes through the `CommonHooks` decorator plus a system-chat broadcast, so dcbridge relays it to Discord.
  - `Agent`:
    - Per-agent state: runner, events, chat log, behaviors, screen/sign/action-bar tracking, rate limit.
    - Teleport acks are queued and processed at the next tick.
  - `Agents`: registry with `load`, `byToken`, `create`/`newToken`/`remove`, `spawn` (`placeNewPlayer` with the NEOFORGE cookie), `despawn` (`connection.onDisconnect`), `serverTick` idle check, `onRealPlayerJoin`, `describeAll`.
- `game/`:
  - Per-agent versions: `ActionRunner`, `Events`, `ChatLog`, `Behaviors`.
  - `Game`: `Game.call` runs on the server thread.
  - `Pathfinder`: copied as is.
  - Ported to Agent/Body: `Navigator`, `Digger` (client-timed START/STOP), `Combat`.
- `tools/`:
  - `Tools`: instructions plus the registry list.
  - `SessionTools`: join, leave.
  - `InfoTools`: get_view replaces screenshot. find_blocks skips chunk sections with `maybeHas`.
  - `ActionTools`: complete.

## Still to write
1. **InventoryTools**:
   - Port from `reference/mongoose_mod/.../tools/InventoryTools.java`.
   - Replace `gm.handleInventoryMouseClick` with `agent.body().click(slot, button, type)`. Clicks are synchronous on the server, so drop the `wait` ticks.
   - `close_screen`: `body().closeContainer()`, which also clears the sign via `clearSign`.
   - `get_open_container`: use `agent.screenTitle()`.
   - Recipes: `p.level().getRecipeManager()`.
   - `drop_item`: `body().drop(all)`.
2. **ChoreTools**:
   - Port the reference.
   - `Reach.tick` uses `body().useItemOn`.
   - Smelt `p.closeContainer()` becomes `body().closeContainer()`.
   - Sleep failure reason: use `agent.actionBar()`.
   - Fishing: `p.fishing`. Detect bites with deltaMovement.y < -0.15, or read FishingHook DATA_BITING by reflection. Maddiecraft's FishingMod may change fishing.
3. **ScreenTools**:
   - `trade`: `body().selectTrade(i)` followed by clicks.
   - `click_menu_button`: `body().menuButton`.
   - `rename_item`: `body().renameItem`.
   - `edit_sign`: `agent.signPos()`, then `body().writeSign(pos, front, lines)` and `clearSign()`.
   - `write_book`: `body().editBook(selected, pages, Optional title)`.
   - `dismount`: sneak.
   - Skip ride_to; mounts are simulated on the client.
4. **ButtonTools**: `list_buttons` and `press_button`.
   - Catalog: `config/agentplayers-buttons.json`. The default entry is Corpse "Transfer Items", payload `corpse:transfer_items` with no fields, shown when the open menu is a corpse menu (check the menu class/type id).
   - Build the payload: fill a `RegistryFriendlyByteBuf` from typed fields, then decode with `NetworkRegistry.getCodec(id, ConnectionProtocol.PLAY, PacketFlow.SERVERBOUND)`.
   - Send it with `body().sendPayload(payload)`.
5. **Respawn mixin**: `mixin/PlayerListMixin`, already listed in `agentplayers.mixins.json`.
   - Use `@WrapOperation` on `NEW ServerPlayer` in `PlayerList.respawn`, with `@Local(argsOnly=true) ServerPlayer old`.
   - If `old instanceof AgentPlayer ap`, create `new AgentPlayer(server, level, profile, info, ap.agent)` and call `Agents.replaced(...)`. `replaced` must be made **public**.
6. **command/AgentCommand**:
   - Subcommands: `/agent create <name> [owner] | token <name> | remove | spawn | despawn | list | stop | skin <name> <from-player|value sig> | allow <name> commands|selfjoin <bool> | reload`.
   - Requires op level 3. Show the token only to the sender, with a click-to-copy style.
7. **AgentPlayersMod** (`@Mod("agentplayers")`, `public static final Logger LOG`):
   - ServerStarted: `Agents.load()`, then start `McpServer(config.bind, config.port, Tools.all(), Tools.INSTRUCTIONS, version)`.
   - ServerStopping: `Agents.despawnAll`, then stop MCP.
   - `ServerTickEvent.Post`: `Agents.serverTick`.
   - `PlayerLoggedInEvent` (non-agent player): `Agents.onRealPlayerJoin`.
   - Register `RegisterCommandsEvent`.
8. **Devtest**: `src/devtest/.../AgentDevTest.java`, following `VanishMod/src/devtest/.../VanishDevTest.java`.
   - It should create an agent and run an HTTP `tools/call` round trip against `McpServer.port()`.
   - It should check move_to, mine, place, craft, chat (the ServerChatEvent fired), die and respawn (the new body is still an AgentPlayer), and deposit.
   - Results go to `devtest_results.txt`.
9. `./gradlew build` and `./gradlew runDevtest`. Then update README (MCP is embedded in the mod with Caddy in front; no Python adapter) and memory `agent-players-mod.md`.
10. Later: screen-scraper button catalog, MineSkin skins, and deploying to Maddiecraft (needs a restart; ask first).
