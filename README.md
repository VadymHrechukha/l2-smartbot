# SmartBot for L2J_Mobius

**AI-assisted fake players for Lineage 2 — adapted for Mobius servers.**

SmartBot adds controllable fake-player characters to your Lineage 2 server. Game Masters can create bots with different roles, players can give them commands through local chat, and an optional local AI model can let them respond to conversations.

This fork adapts the original [PlayerExplains/l2-smartbot](https://github.com/PlayerExplains/l2-smartbot) project to the `org.l2jmobius.*` APIs used by [MobiusDevelopment/L2J_Mobius](https://github.com/MobiusDevelopment/L2J_Mobius).

The original project targeted L2J Lisvus. This fork contains changes needed to integrate SmartBot with the Mobius server source.

> **Development status:** The port has been adapted to the Mobius APIs and has reportedly compiled successfully. Some integration and in-game verification tasks remain. See [Current Status](#current-status) before installing it on a server.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [How SmartBot Works](#how-smartbot-works)
- [Installation](#installation)
  1. [Copy the Java Files](#1-copy-the-java-files)
  2. [Register the Admin Command Handler](#2-register-the-admin-command-handler)
  3. [Configure Admin Commands](#3-configure-admin-commands)
  4. [Create the Database Table](#4-create-the-database-table)
  5. [Configure SmartBot](#5-configure-smartbot)
  6. [Restore Bots at Startup](#6-restore-bots-at-startup)
  7. [Save Bots at Shutdown](#7-save-bots-at-shutdown)
  8. [Connect Local Chat](#8-connect-local-chat)
  9. [Connect Party Invites](#9-connect-party-invites)
  10. [Install Knowledge Files](#10-install-knowledge-files)
- [GM Commands](#gm-commands)
- [Player Chat Commands](#player-chat-commands)
- [Bot Presets](#bot-presets)
- [AI Chat with Ollama](#ai-chat-with-ollama)
- [Knowledge System](#knowledge-system)
- [Troubleshooting](#troubleshooting)
- [Current Status](#current-status)

## Features

### Six bot presets

SmartBot provides six predefined roles:

| Preset | Role | Behavior |
|---|---|---|
| `ARCHER` | Fighter | Uses a bow, soulshots, and Stun Shot. |
| `MAGE` | Mage | Attacks using magic skills. |
| `HEALER` | Healer | Heals party members and supports resurrection. |
| `BUFFER` | Support | Applies buffs to party members. |
| `DAGGER` | Fighter | Uses dagger skills. |
| `TANK` | Fighter | Uses a sword and shield with aggro skills. |

The exact skills, equipment, and behavior depend on the implementation and the server's datapack.

### Bot management

- Create bots using GM commands.
- Save bot information in a database.
- Restore bots when the game server starts.
- Follow a player.
- Assist a player.
- Attack nearby monsters.
- Stop bot actions.
- Invite bots to parties and handle player invitations.

### AI conversations

SmartBot can optionally use a local Ollama model to generate short chat replies.

It also supports a simple, file-based knowledge system for Lineage 2 information and keeps short-term chat memory for individual bots.

Ollama is optional. The bot implementation includes fallback replies for supported interactions when AI generation is unavailable.

## Requirements

Before installing SmartBot, prepare the following:

- A working L2J_Mobius server source tree.
- A compatible JDK and the build environment required by your server revision.
- Access to the server database.
- Access to the server's Java source files and configuration directory.
- Ollama and a compatible local model, if you want AI-generated conversations.

**Important:** This project is not a drop-in plugin for every L2J server. The original code was written for L2J Lisvus, and this fork adapts it to Mobius APIs.

The instructions below refer to the Mobius source layout used during this port. Your server revision may have a different layout.

## How SmartBot Works

SmartBot consists of several cooperating components.

| Component | Responsibility |
|---|---|
| `SmartBotManager` | Creates, manages, spawns, and despawns bots. |
| `SmartBotController` | Controls bot behavior and actions. |
| `BotLlmService` | Processes chat commands, manages AI replies, and handles bot party invitations. |
| `SmartBotDao` | Reads and writes bot data in the database. |
| `SmartBotConfig` | Loads SmartBot configuration values. |
| `SmartBotKnowledge` | Loads knowledge files and retrieves relevant facts. |
| `BotMemory` | Maintains short-term chat history. |
| `SmartBotPreset` | Defines available bot presets. |
| `SmartBotRole` | Represents bot roles. |
| `SmartBotData` | Stores bot-related data. |
| `AdminSmartbot` | Implements GM commands for bot management. |

The server connects these components through its initialization, shutdown, chat, party, and admin-command handling systems.

# Installation

The following steps describe the integration with the Mobius server source.

If you are maintaining an existing installation, compare each step with your current code before applying changes again.

## 1. Copy the Java Files

The SmartBot classes belong in the Mobius Java source tree.

The main files should be located here:

```text
java/
└── org/
    └── l2jmobius/
        └── gameserver/
            ├── handler/
            │   └── AdminSmartbot.java
            ├── config/
            │   └── custom/
            │       └── SmartBotConfig.java
            └── smartbot/
                ├── SmartBotManager.java
                ├── SmartBotController.java
                ├── BotLlmService.java
                ├── dao/
                │   └── SmartBotDao.java
                └── model/
                    ├── BotMemory.java
                    ├── SmartBotData.java
                    ├── SmartBotKnowledge.java
                    ├── SmartBotPreset.java
                    └── SmartBotRole.java
```

Make sure that the package declaration at the beginning of each Java file matches its directory.

For example, `SmartBotManager.java` should begin with:

```java
package org.l2jmobius.gameserver.smartbot;
```

The admin handler should use:

```java
package org.l2jmobius.gameserver.handler;
```

The configuration class should use:

```java
package org.l2jmobius.gameserver.config.custom;
```

The Mobius implementation also requires adapting imports and method calls to the APIs available in your particular server revision.

For example:

| Original L2J Lisvus API | Mobius API used by this port |
|---|---|
| `L2PcInstance` | `Player` |
| `L2World` | `World` |
| `L2Party` | `Party` |
| `SkillTable` | `SkillData` |
| `CharTemplateTable` | `PlayerTemplateData` |
| `L2DatabaseFactory` | `DatabaseFactory` |
| `ThreadPoolManager` | `ThreadPool` |
| `IAdminCommandHandler` | `IAdminCommandHandler` with Mobius method signatures |

These are API mappings, not necessarily drop-in replacements. Some constructors, method signatures, and behaviors also differ.

## 2. Register the Admin Command Handler

The `AdminSmartbot` class implements the Mobius admin-command interface.

It provides the following command identifiers:

```java
private static final String[] ADMIN_COMMANDS =
{
    "admin_bot_create",
    "admin_bot_follow",
    "admin_bot_despawn",
    "admin_bot_delete",
    "admin_bot_unfollow"
};
```

The server must register this handler before GM commands can reach it.

First, find where the other admin handlers are registered in your Mobius source.

Look for existing registrations such as:

```java
AdminCommandHandler.getInstance().registerHandler(...);
```

Add the SmartBot handler alongside the existing handlers, using the same initialization mechanism:

```java
import org.l2jmobius.gameserver.handler.AdminSmartbot;
import org.l2jmobius.gameserver.handler.AdminCommandHandler;
```

Then register it:

```java
AdminCommandHandler.getInstance()
    .registerHandler(new AdminSmartbot());
```

Place the registration in the appropriate initialization location for your server revision.

**Status:** The handler class exists, but its registration in the server's admin-command initialization must be verified. Do not assume that creating the Java file registers it automatically.

## 3. Configure Admin Commands

Open:

```text
dist/game/config/AdminCommands.xml
```

Add the SmartBot commands:

```xml
<!-- SMARTBOT -->
<admin command="admin_bot_create"
       description="Creates a new bot."
       accessLevel="30" />

<admin command="admin_bot_follow"
       description="Start follow."
       accessLevel="30" />

<admin command="admin_bot_despawn"
       description="Despawn bot."
       accessLevel="30" />

<admin command="admin_bot_delete"
       description="Delete bot."
       accessLevel="30" />

<admin command="admin_bot_unfollow"
       description="Stop follow."
       accessLevel="30" />
```

The commands must match those returned by `AdminSmartbot.getCommandList()`.

The examples below use the friendly `//bot_*` aliases. Confirm that your server's admin-command configuration supports those aliases.

## 4. Create the Database Table

SmartBot uses a database table to persist bot information.

Create the `smart_bots` table in the database used by your game server:

```sql
CREATE TABLE IF NOT EXISTS smart_bots (
    char_obj_id INT NOT NULL PRIMARY KEY,
    bot_name VARCHAR(35) NOT NULL,
    template_class_id INT NOT NULL,
    spawn_x INT NOT NULL,
    spawn_y INT NOT NULL,
    spawn_z INT NOT NULL,
    heading INT NOT NULL DEFAULT 0,
    follow_target_obj_id INT NOT NULL DEFAULT 0,
    melee_attack_range INT NOT NULL DEFAULT 40,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
```

The table stores the bot's object ID, name, class template, position, heading, follow target, and persistence information.

**Status:** The table has been created in the development database. If you are installing SmartBot on another server, make sure the table exists in that server's database too.

## 5. Configure SmartBot

SmartBot uses its own configuration class instead of adding all its settings to a single monolithic configuration class.

The configuration class is located at:

```text
java/org/l2jmobius/gameserver/config/custom/SmartBotConfig.java
```

It loads settings from:

```text
dist/game/config/Custom/SmartBot.ini
```

The configuration class is registered through `ConfigLoader.init()`.

### Configuration example

Create or edit `SmartBot.ini`:

```ini
# SmartBot chat
BotChatRadius = 1250
BotMaxReplyWords = 18
BotMaxReplyChars = 120
BotReplyCooldownMs = 2000

# Combat
BotAttackSearchRadius = 1200
BotFollowDistance = 80

# AI
BotOllamaUrl = http://127.0.0.1:11434/api/generate
BotOllamaModel = gemma3

# Short-term memory and knowledge
BotMemoryLines = 20
BotKnowledgeFacts = 4

# Healer
BotHealRange = 900
BotHealHpPercent = 85.0

# Mana potions
BotManaPotionItemId = 728
BotManaPotionCount = 100
BotManaPotionMpPercent = 30.0
```

These values are defaults for the current port, not universal recommendations for every server.

### How configuration loading works

The Mobius configuration class reads the values using `ConfigReader`.

For example:

```java
final ConfigReader config =
    new ConfigReader("./config/Custom/SmartBot.ini");

BOT_CHAT_RADIUS = config.getInt("BotChatRadius", 1250);

BOT_REPLY_COOLDOWN_MS =
    config.getLong("BotReplyCooldownMs", 2000L);

BOT_OLLAMA_URL = config.getString(
    "BotOllamaUrl",
    "http://127.0.0.1:11434/api/generate"
);

BOT_OLLAMA_MODEL =
    config.getString("BotOllamaModel", "gemma3");
```

The second argument provides the default value when a setting is absent.

`SmartBotConfig.load()` must run during configuration initialization, before SmartBot uses these settings.

**Status:** `SmartBotConfig` has been added, registered with `ConfigLoader`, and a `SmartBot.ini` file has been created. Verify that the deployed file is in the correct location and that the running server loads it successfully.

## 6. Restore Bots at Startup

Bots should be restored after the game server has initialized the world and the required game systems.

Open:

```text
java/org/l2jmobius/gameserver/GameServer.java
```

Add the relevant imports:

```java
import org.l2jmobius.gameserver.smartbot.SmartBotManager;
import org.l2jmobius.gameserver.smartbot.model.SmartBotKnowledge;
```

During server initialization, load the knowledge files and restore the saved bots:

```java
SmartBotKnowledge.getInstance().load();

SmartBotManager.getInstance().restoreBots();
```

These calls should run only after the configuration and world initialization required by SmartBot are ready.

The knowledge system loads its data before bots begin responding to players.

**Status:** The startup calls have been added to `GameServer.java`.

## 7. Save Bots at Shutdown

Bot information should be saved before the game server finishes shutting down.

Open:

```text
java/org/l2jmobius/gameserver/Shutdown.java
```

Add:

```java
import org.l2jmobius.gameserver.smartbot.SmartBotManager;
```

During shutdown, call:

```java
SmartBotManager.getInstance().saveAll();
```

Place this alongside the other server persistence operations.

This allows SmartBot to preserve bot information for subsequent server starts.

**Status:** The shutdown hook has been added.

## 8. Connect Local Chat

SmartBot processes nearby player chat through `BotLlmService`.

Open:

```text
java/org/l2jmobius/gameserver/network/clientpackets/Say2.java
```

The chat integration uses the Mobius `ChatType` enum and the server's thread pool.

The relevant logic is:

```java
if ((chatType == ChatType.GENERAL)
    && !SmartBotManager.getInstance().isBot(player))
{
    final String text = _text;

    ThreadPool.execute(
        () -> BotLlmService.INSTANCE.handlePlayerAllChat(player, text)
    );
}
```

Add the corresponding imports:

```java
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.smartbot.BotLlmService;
import org.l2jmobius.gameserver.smartbot.SmartBotManager;
```

The call is asynchronous because Ollama requests can block while waiting for an HTTP response.

Running the request on the thread pool avoids blocking the player's chat packet handler during AI generation.

The integration also avoids processing a bot's own chat as a new player message.

**Status:** The local-chat hook has been added to `Say2.java`.

## 9. Connect Party Invites

Bots do not have normal game clients, so they cannot respond to party invitations through the usual client packet flow.

SmartBot handles invitations to managed bots separately.

Open:

```text
java/org/l2jmobius/gameserver/network/clientpackets/RequestJoinParty.java
```

### Resolve the invited player

If the normal player lookup fails, try looking up a managed bot:

```java
Player target = World.getPlayer(_name);

if (target == null)
{
    target = SmartBotManager.getInstance()
        .getSpawnedBotByName(_name);
}
```

The normal missing-target handling should remain after this lookup.

### Allow managed bots without game clients

Bots do not have a normal client connection, so the offline-player check must not reject a managed bot merely because it has no client.

Determine whether the target is a SmartBot:

```java
final boolean isSmartBot =
    SmartBotManager.getInstance().isBot(target);
```

Apply the normal offline-client check only to non-bot players.

### Handle the invitation

Before the regular invitation packet flow, route managed bots to `BotLlmService`:

```java
if (isSmartBot)
{
    BotLlmService.INSTANCE.handlePartyRequest(
        requestor,
        target,
        _partyDistributionTypeId
    );

    return;
}
```

Keep the existing server checks for party restrictions, eligibility, and other conditions before this branch.

The early return is important: a managed bot cannot receive the normal invitation response through a game client.

**Status:** The bot lookup, client-check exception, and party-invite handling have been added. Verify the complete invitation flow in-game, including party membership and rejection conditions.

## 10. Install Knowledge Files

SmartBot includes a simple text-based knowledge system.

Copy the knowledge directory from this repository into your server configuration directory:

```text
config/
└── smartbot/
    └── knowledge/
        ├── ...
        └── ...
```

The directory should be available under the server's runtime configuration root:

```text
<server-root>/config/smartbot/knowledge/
```

The knowledge system reads `.txt` files from this directory.

### Knowledge file format

Each non-empty, non-comment line represents a fact.

Use tags in square brackets followed by the fact text:

```text
[level, farm, c4] High-level farming locations depend on character level, equipment, and party composition.

[buff, fighter] Fighter classes may benefit from Might, Haste, Focus, and other suitable physical buffs.

[buff, mage] Mage classes may benefit from Acumen, Empower, and other suitable magical buffs.
```

The tags help SmartBot find facts relevant to a player's message.

The number of facts supplied to the AI prompt is controlled by:

```ini
BotKnowledgeFacts = 4
```

### Load the knowledge

The server startup should include:

```java
SmartBotKnowledge.getInstance().load();
```

This call belongs in `GameServer.java`, after the required configuration has been initialized.

**Status:** The knowledge directory has been copied, and the startup loading call has been added.

# GM Commands

Once the admin handler is registered and the server has loaded the configuration, a GM can manage bots through the available commands.

## Create a bot

Use:

```text
//bot_create <name> <preset>
```

Examples:

```text
//bot_create Mark ARCHER
//bot_create Alice HEALER
//bot_create Eva BUFFER
//bot_create Doom TANK
//bot_create Shade DAGGER
//bot_create Nova MAGE
```

Available presets:

```text
ARCHER
MAGE
HEALER
BUFFER
DAGGER
TANK
```

The bot is created near the GM's current position.

The name must be valid for your server's character-name rules.

## Make a bot follow you

Target a managed bot, then use:

```text
//bot_follow
```

## Stop following

Target a managed bot, then use:

```text
//bot_unfollow
```

## Despawn a bot

Target a managed bot, then use:

```text
//bot_despawn
```

Despawning removes the bot from the world while retaining its database record.

## Delete a bot

Target a managed bot, then use:

```text
//bot_delete
```

Deleting a bot removes it and its saved database record.

**Note:** The commands above are implemented by the SmartBot admin handler. They will not work until the handler is registered and the server recognizes the corresponding command aliases.

# Player Chat Commands

SmartBot can interpret commands sent through nearby local chat.

The following examples describe the intended command interface. Verify the behavior against your current implementation.

## Follow

```text
follow
```

Or address a specific bot:

```text
Mark follow
```

The selected bot follows the player.

## Follow all nearby bots

```text
follow all
```

Or:

```text
all follow
```

All eligible nearby bots enter follow mode.

## Assist

```text
assist
```

The selected bot assists the player.

The exact behavior depends on the bot's role:

- Fighters can attack the player's target.
- Healers can follow and heal.
- Buffers can follow and support party members.

## Assist all nearby bots

```text
assist all
```

Or:

```text
all assist
```

## Attack

```text
attack
```

The selected bot attempts to attack the nearest eligible monster within its search radius.

## Attack all nearby bots

```text
attack all
```

Or:

```text
all attack
```

## Stop

```text
stop
```

The following alternatives are also supported by the command parser:

```text
wait
hold
```

The selected bot stops its current actions.

## Stop all nearby bots

```text
stop all
```

Or:

```text
all stop
```

## Buff

```text
buff
```

Or:

```text
buffs
```

The nearest suitable buffer can attempt to buff the player.

You can also address a specific buffer:

```text
Eva buff me
```

## Party

```text
party
```

Or:

```text
invite
```

Party behavior depends on the direction of the invitation and the implementation handling it.

The current Mobius integration includes a dedicated path for accepting invitations sent by players to managed bots.

# Bot Presets

SmartBot uses predefined presets to select a bot's role, equipment, and combat behavior.

### Archer

The archer uses a bow, soulshots, and Stun Shot when appropriate.

The current implementation uses skill ID `101` as a Stun Shot candidate.

### Mage

The mage uses magic attacks.

The original implementation contains candidate skill IDs including:

```text
1239 - Hurricane
1230 - Prominence
1235 - Hydro Blast
1220 - Blaze
1177 - Wind Strike
```

These IDs are inherited from the original implementation. Verify them against the server's datapack before relying on them.

### Healer

The healer is intended to:

- Heal injured party members.
- Follow the configured target.
- Support the party during combat.
- Resurrect dead party members when the relevant conditions allow it.

### Buffer

The buffer is intended to:

- Detect missing buffs.
- Choose suitable fighter or mage buff sets.
- Buff party members.
- Follow the configured target when no buffing action is required.

### Tank

The tank uses sword-and-shield equipment and attempts to use aggro skills.

The original implementation includes these candidate skill IDs:

```text
28 - Aggression
18 - Aura of Hate
```

### Dagger

The dagger preset uses dagger equipment and attempts to use dagger skills.

The original implementation includes these candidates:

```text
263 - Deadly Blow
344 - Lethal Blow
```

The exact skills available to a bot depend on the server's skill data and character class configuration.

# AI Chat with Ollama

Ollama is an optional component that allows SmartBot to generate natural-language replies using a local language model.

Without Ollama, supported interactions can use fallback replies instead of generated conversations.

## 1. Install Ollama

Install Ollama on the machine running the AI service.

Follow the installation instructions provided by the official Ollama project.

## 2. Download a model

The default configuration uses the `gemma3` model.

Run:

```bash
ollama run gemma3
```

The command downloads the model if necessary and starts an interactive session.

You can use a different compatible model by changing the SmartBot configuration.

## 3. Configure the API endpoint

The default endpoint is:

```ini
BotOllamaUrl = http://127.0.0.1:11434/api/generate
BotOllamaModel = gemma3
```

The server must be able to reach the configured endpoint.

If Ollama runs on a different machine, change the address to the appropriate reachable endpoint.

## 4. Test AI replies

After starting the game server with Ollama available, approach a bot and send a short local-chat message.

For example:

```text
hello
```

A bot may respond with a short conversational reply.

SmartBot also uses its knowledge files and recent chat history to help generate relevant answers.

The AI service uses HTTP requests, so replies can be delayed by model generation, network errors, or model availability.

# Knowledge System

The knowledge system provides game-related information to the AI without requiring the model to rely entirely on its training data.

Knowledge files are stored in:

```text
config/smartbot/knowledge/
```

Only `.txt` files are loaded.

Each line contains one fact, optionally preceded by comma-separated tags in square brackets.

For example:

```text
[level, farm, c4] Choose a farming location based on character level, equipment, and party composition.

[buff, fighter] Fighter characters generally benefit from appropriate physical attack and support buffs.

[buff, mage] Mage characters generally benefit from appropriate magical attack and support buffs.
```

The system searches for facts relevant to a player's message and supplies a limited number to the AI prompt.

The maximum number of selected facts is controlled by:

```ini
BotKnowledgeFacts = 4
```

This is a lightweight text-based retrieval system, not a vector database or an embedding-based search engine.

The quality of its answers depends on the quality and relevance of the knowledge files.

# Troubleshooting

## GM commands are not recognized

Check the following:

1. `AdminSmartbot` exists in the Mobius source tree.
2. The handler is registered through `AdminCommandHandler`.
3. The commands are present in `AdminCommands.xml`.
4. Your GM account has the required access level.
5. The command aliases match your server's admin-command configuration.

## Bots do not appear after restarting the server

Check:

- The `smart_bots` table exists in the correct database.
- The database contains the expected bot records.
- `SmartBotManager.getInstance().restoreBots()` runs during server initialization.
- The server logs do not contain exceptions during bot restoration.

## Bots do not move

Check whether the bot can move normally in the Mobius engine.

Potential causes include:

- Inventory weight restrictions.
- Incorrect character templates.
- Missing or unsuitable equipment.
- AI or movement initialization issues.
- Differences between the original Lisvus implementation and the Mobius movement APIs.

Check the server logs for exceptions before changing the bot's behavior.

## Bots do not respond to local chat

Check:

- The message uses local chat (`ChatType.GENERAL`).
- The `Say2` integration is present.
- The chat-processing call is executed.
- The bot is spawned and within the configured chat radius.
- The message is not being ignored by the command parser or reply cooldown.

If the bot is expected to generate an AI response, check the Ollama connection separately.

## AI replies do not work

Check that Ollama is running and that the configured model is available.

The default settings are:

```ini
BotOllamaUrl = http://127.0.0.1:11434/api/generate
BotOllamaModel = gemma3
```

Also check:

- Whether the server can reach the configured endpoint.
- Whether the selected model is available.
- Whether the server logs show HTTP or connection errors.
- Whether the bot is within the configured chat radius.

AI generation is optional, so first verify that non-AI bot commands work correctly.

## Knowledge-based answers are incorrect

Check that:

1. The knowledge files are in the runtime configuration directory.
2. The files have the `.txt` extension.
3. Each fact is on its own line.
4. The knowledge-loading method runs during startup.
5. The relevant facts contain appropriate tags and information.

The knowledge system can only provide information that exists in its loaded files and that its retrieval logic can identify as relevant.

## Bots cannot join a party

Check that:

- The bot is managed by `SmartBotManager`.
- The party-invite hook is present in `RequestJoinParty`.
- The handler resolves bot names when normal player lookup fails.
- The bot-specific path is reached before the normal offline-client rejection.
- The normal party eligibility checks still work correctly.

Test both inviting a bot into a new party and inviting a bot into an existing party.

# Current Status

This section documents the integration work completed so far. It distinguishes source changes from behavior that still needs verification.

| Area | Status |
|---|---|
| SmartBot Java classes adapted to Mobius APIs | Implemented |
| `SmartBotConfig` class | Implemented |
| Configuration loader integration | Implemented |
| `SmartBot.ini` | Created |
| `smart_bots` database table | Created in the development database |
| Admin command handler class | Implemented |
| Admin command definitions | Added to `AdminCommands.xml` |
| Admin handler registration | Needs verification/completion |
| Bot restoration at startup | Implemented |
| Bot saving at shutdown | Implemented |
| Local chat integration | Implemented |
| Party-invite integration | Implemented |
| Knowledge files | Copied |
| Knowledge loading at startup | Implemented |
| Compilation | Reported successful |
| Complete in-game testing | Still required |

The most important remaining integration task is to verify that `AdminSmartbot` is registered in the Mobius admin-command initialization.

After that, test the complete lifecycle:

1. Start the game server.
2. Create a bot using a GM command.
3. Confirm that the bot appears in the world.
4. Test follow, assist, attack, and stop commands.
5. Invite the bot to a party.
6. Test local chat and AI replies.
7. Restart the server and confirm that saved bots are restored.
8. Test despawning and deleting bots.

A successful compilation is an important milestone, but it does not guarantee that spawning, combat, persistence, party handling, and AI integration all work correctly at runtime.

# Development Notes

This fork adapts SmartBot to the Mobius server APIs rather than maintaining compatibility with the original Lisvus package structure.

When modifying the code, pay particular attention to:

- Differences in player and world APIs.
- Skill activation and AI intention methods.
- Database access and persistence.
- Admin command registration.
- Party-invitation and transaction handling.
- Threading around blocking HTTP requests.
- Class IDs, skill IDs, and item IDs in the server's datapack.

When porting additional behavior, prefer the APIs and conventions already used by the target Mobius revision.

# License

Refer to the repository's `LICENSE` file for the applicable license terms.