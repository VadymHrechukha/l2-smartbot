# SmartBot for L2J Mobius Interlude

SmartBot adds managed fake-player bots to the **L2J Mobius CT 0 Interlude** server. Bots can be created by a Game Master, restored after a restart, follow or assist players, fight monsters, provide role-specific support, and optionally respond to local chat through a local Ollama model.

> **Port status:** This README describes the Mobius Interlude integration. The integration still needs to be compiled and tested on a running server. Treat the feature list as intended functionality until each item has been verified in-game.

## Features

- Persistent, GM-created bots
- Bot restoration during server startup and saving during shutdown
- Follow, assist, attack, stop, and party-invitation behavior
- Role presets: `ARCHER`, `MAGE`, `HEALER`, `BUFFER`, `DAGGER`, and `TANK`
- Healer and buffer support behavior
- Optional local chat replies using Ollama
- File-based knowledge facts in `config/smartbot/knowledge/`
- Short-term conversation memory per bot

Some combat skills, class IDs, item IDs, and behavior are implementation-specific and may need adjustment for your datapack.

## Requirements

- A working L2J Mobius **CT 0 Interlude** source tree
- Java and build tools supported by the Mobius revision you use
- A database configured for the game server
- Ollama and a locally available model only if you want AI-generated chat replies

SmartBot is a source integration, not a standalone drop-in JAR. Do not assume that code written for another L2J fork will compile without adapting its APIs.

## Mobius integration

The paths below are relative to the `L2J_Mobius_CT_0_Interlude` directory unless noted.

### 1. Add the SmartBot Java classes

Copy the SmartBot manager, controller, AI service, DAO, and model classes into the Mobius source tree, keeping package declarations consistent with their directory structure and the Mobius APIs.

The port should use Mobius classes and imports rather than the original `net.sf.l2j.*` packages. Review the imports and method signatures against the exact Mobius revision you build.

### 2. Add the configuration file

The integration loads SmartBot settings from:

```text
config/Custom/SmartBot.ini
```

Make sure the file exists in the game-server configuration directory and contains the settings expected by `SmartBotConfig`. Keep the key names in the INI file synchronized with the keys read by that class.

The configuration loader must call `SmartBotConfig.load()` during startup.

### 3. Register the GM command handler

The Mobius Interlude branch registers handlers through the script master handler:

```text
dist/game/data/scripts/handlers/MasterHandler.java
```

Add `AdminSmartbot` to the admin-handler registration list and add the corresponding import. Keep the class in the same package and directory convention used by the other admin command implementations in your checked-out branch. In the current Mobius layout, existing admin command classes are registered from `handlers.chat.commands.admin`.

Make sure `AdminSmartbot` implements the appropriate admin-command interface, exposes its command list, and has a public no-argument constructor so the master handler can instantiate it.

### 4. Add admin-command access

Add the SmartBot command names to the admin-command access configuration used by your Mobius datapack. The exact file and alias syntax can vary by revision, so follow the format already used by neighboring admin commands.

Commands implemented by SmartBot may include:

- `admin_bot_create`
- `admin_bot_follow`
- `admin_bot_unfollow`
- `admin_bot_despawn`
- `admin_bot_delete`

Use the aliases defined by the actual `AdminSmartbot` implementation. Do not add aliases that the handler does not implement.

### 5. Create the database table

Apply the SmartBot SQL schema supplied with the integration (typically under the project's `sql/` directory) to the database used by the game server. Verify that the table and column names match the queries in `SmartBotDao` before starting the server.

Back up the database before applying schema changes to an existing server.

### 6. Check startup and shutdown hooks

Verify that the Mobius startup sequence invokes:

```java
SmartBotManager.getInstance().restoreBots();
SmartBotKnowledge.getInstance().load();
```

These calls should occur at a point where the required game-server systems are initialized. Verify that the shutdown sequence invokes:

```java
SmartBotManager.getInstance().saveAll();
```

Use the singleton accessor actually defined in your ported classes; do not mix `INSTANCE` and `getInstance()` unless both are deliberately supported.

### 7. Connect local chat

The `Say2` chat handler can forward eligible local/all-chat messages to the SmartBot LLM service. Confirm that the hook:

- Runs only for the intended chat type
- Uses the correct player and message variables for the Mobius revision
- Does not block the network packet thread with a synchronous model request
- Uses the singleton accessor defined by `BotLlmService`

AI replies require Ollama to be running and reachable if the fallback reply behavior is not sufficient.

### 8. Connect party invitations

Check `RequestJoinParty` so that invitations can resolve managed bots as targets and bot-specific party handling runs before normal player-only logic rejects the target. Verify the method signatures and invitation flow against the Mobius branch.

### 9. Install knowledge files

Place the supplied knowledge text files under:

```text
config/smartbot/knowledge/
```

Check `SmartBotKnowledge` for the expected file format and loading rules. Keep the `BotKnowledgeFacts` setting aligned with the configuration parser if that option is enabled.

## Ollama (optional)

SmartBot can use a local Ollama model for AI-generated chat replies. Install Ollama using its official documentation:

- [Ollama](https://ollama.com/)

Configure the endpoint and model in `config/Custom/SmartBot.ini` using the exact property names expected by `SmartBotConfig`. Confirm that the endpoint is reachable from the game-server host. Ollama is optional; the bot's non-LLM behavior should be tested separately.

## In-game command reference

The exact command syntax is defined by `AdminSmartbot.java`. Check that implementation before relying on these examples.

Typical commands from the upstream SmartBot implementation include:

```text
//bot_create <name> <preset>
//bot_follow
//bot_unfollow
//bot_despawn
//bot_delete
```

The upstream presets are:

```text
ARCHER
MAGE
HEALER
BUFFER
DAGGER
TANK
```

For player chat commands such as `follow`, `assist`, `attack`, `stop`, or `buff`, check the actual ported implementation and test the commands with a managed bot nearby.

## Build and test checklist

1. Build the Interlude project using the instructions for your Mobius checkout.
2. Confirm that the SmartBot configuration is loaded without errors.
3. Confirm that the admin handler is registered and its commands are accepted.
4. Create a test bot and verify that it appears in the world.
5. Test follow, assist, attack, stop, and party behavior individually.
6. Restart the server and confirm that persistent bots are restored correctly.
7. Shut down the server and verify that bot state is saved.
8. Test chat AI with Ollama enabled, then test behavior with Ollama unavailable.
9. Review the game-server logs for SQL errors, invalid skills, null references, and thread or packet-handler issues.

Do not use a production server or database for initial testing.

## Troubleshooting

### Admin commands are not recognized

- Confirm that `AdminSmartbot` is imported and included in `MasterHandler.java`.
- Confirm that its command list matches the aliases in the admin-command access configuration.
- Check startup logs for handler-instantiation or class-loading errors.

### Bots are not restored

- Confirm that the `smart_bots` table exists and matches the DAO queries.
- Confirm that `restoreBots()` runs after the necessary game-server systems are initialized.
- Check server logs for database and spawn errors.

### AI chat replies do not work

- Confirm that Ollama is running and the configured endpoint is reachable from the game-server host.
- Confirm that the model configured in `SmartBot.ini` is installed.
- Check the server logs for HTTP errors and confirm that the chat hook uses the correct message text and chat type.

### A bot does not move or use a skill

- Check the server logs for AI, movement, skill, inventory, and target errors.
- Confirm that the preset's class, skill, and item IDs are valid for your datapack.
- Test the behavior without the LLM first to separate game logic issues from AI chat issues.

## Development notes

- Keep SmartBot package names consistent with the Mobius source and script-handler layout.
- Keep configuration property names, SQL columns, and DAO queries synchronized.
- Prefer Mobius APIs over assumptions carried over from the original L2J implementation.
- Record which behaviors have actually been tested on the exact Mobius revision being used.

## License

Check the `LICENSE` file included with the SmartBot source for the applicable license and comply with its terms when redistributing or modifying the project.
