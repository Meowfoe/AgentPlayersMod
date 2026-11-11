# AgentPlayers

A server-only NeoForge 1.21.1 mod for Maddiecraft. It lets AI agents play as their own players, with no Minecraft account and no game client. Players don't need a modpack update.

Status: design only, no code yet. Waiting for the reference source in `reference/mongoose_mod/`.

## Reference

`reference/mongoose_mod/` is for the source of mongoose's client-side MCP mod, the one her Claude plays through on her account. We read it to see which tools and observations work well for an agent: status, inventory, nearby entities and blocks, chat, walking, mining, crafting, fighting, and how it describes containers as text. We then rebuild those as server-side tools. Its code is not copied into this mod.

## Design

- **Agent player**: a `ServerPlayer` with no real connection behind it (like Carpet's `/player`), not a NeoForge `FakePlayer`, so other mods treat it as a real player. It appears in the tab list and in chat as `<Name>`. Its playerdata is kept, so its inventory survives restarts. It also shows up in InventoryHistory, claims and corpses like anyone else.
- **Client compatibility**: the mod registers no items, blocks or entity types and sends no custom packets, so vanilla packets cover everything and clients need nothing new.
- **Movement**: the server applies each agent's movement input every tick. Pathfinding is server-side A* over block collision.
- **Skins**: signed texture properties, made once per skin with MineSkin or fetched from a real player's profile with `unsigned=false`. `/agent skin` changes a skin live by re-sending the agent's tab-list entry and respawning it for nearby players. No capes, since those belong to an account.
- **Commands** (op only): `/agent spawn|despawn|list|skin|stop <name>`.
- **Control link**: newline JSON on 127.0.0.1, the same pattern as dcbridge. Each agent has its own token.
- **MCP adapter**: a Python Streamable-HTTP MCP server on the VPS, behind Caddy for HTTPS (`/mcp`, bearer token per agent). A token only controls its own agent and gets no console, shell or file access. Actions are rate-limited and tokens can be revoked. Example client setup:
  `claude mcp add --transport http maddiecraft https://<domain>/mcp --header "Authorization: Bearer <token>"`
- **Tools**: look around (status, inventory, nearby blocks and entities as text, recent chat, a top-down text map), goto/look/sprint, mine/place, attack/use/eat/equip, craft by recipe id, open container/take/put, sleep, say. There are no screenshots, because nothing renders on the server.
- **GUIs and buttons**: an agent opens a menu the same way a player does, by interacting with a block or entity, and the server then sets `agent.containerMenu`. Container tools act on that menu directly. `take`/`put`/`take_all` use `menu.clicked(slot, 0, QUICK_MOVE, agent)`, which works generically for every mod's container. Vanilla-style buttons use `menu.clickMenuButton`. For mod buttons that send custom packets, a small compat table calls the server-side handler logic directly (compileOnly dependency, guarded by `ModList.isLoaded`). Example: the Corpse "Transfer Items" button (`corpse:transfer_items`) just runs `if (player.containerMenu instanceof ITransferrable t && !player.isDeadOrDying()) t.transferItems();` (checked in corpse-neoforge-1.21.1-1.1.21). So `loot_corpse` = interact with the CorpseEntity, then call that.
- **Seeing buttons**: buttons only exist in the client's Screen; the server never learns their names. So agents read screens from a **button catalog**, keyed by menu type: label, tooltip, current state where known, arguments, and the serverbound payload the button sends.
  - `look_at_screen()` returns the title, menu type, slots (container vs own inventory, item names and counts), and the catalog's buttons for that menu. `press(button, args)` fires one.
  - Pressing works by building the same payload a real client would send and passing it through the agent's own `ServerGamePacketListenerImpl.handleCustomPayload`. The mod's normal handler then runs with the agent as the sender, so one route works for every mod. To check in step 1: the fake connection has to count all channels as negotiated, or NeoForge drops the payload.
  - The catalog is made by a **screen scraper** dev tool. In a dev client with the Maddiecraft mods, it opens each modded screen, reads every widget (`getMessage()`, tooltip, position), presses each one in a throwaway world, and records the payload it sent (a mixin on the client's send). Fixed-payload buttons replay as-is. Buttons with varying arguments (slot indexes, filter entries, text fields) get hand-written entries. Labels come from the mods' en_us lang files, so agents see the same names players do.
  - For a menu with no catalog entry, the agent still gets the slots, plus a note that the buttons aren't mapped yet.
  - First targets: Corpse, Sophisticated Backpacks (sort, upgrade tabs, magnet/pickup filters, settings), crafting/smithing/enchanting/anvil/stonecutter, Create's basic screens.
- **Ground rules**: agents are never op, each agent has an owner, and there are per-agent claims and view-distance limits. Each agent costs about as much server time as a real player.

## Build order

1. Spawn and despawn agents with persistent inventory, plus chat.
2. Movement and pathfinding.
3. Mining, placing, inventory and containers.
4. Crafting and combat.
5. MCP adapter, Caddy, and tokens, then hook up Yuzuki (`DeepSeek/living_agent`) and mongoose's Claude.

Every step gets a `runDevtest` e2e check, like the other Maddiecraft mods. Never run `gradlew clean`.
