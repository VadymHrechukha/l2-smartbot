![SmartBot for L2J](banner.png)

# SmartBot - L2J Fake Player system powered by AI

SmartBot is an AI-assisted fake player system for Lineage 2 Scions of Destiny (C4) server projects based on L2J.

It adds managed fake-player bots that can be spawned by GMs, follow players, assist in combat, heal, resurrect, buff party members, use role-specific skills, and optionally talk through a local *Ollama model*.

The project is designed for private server development, testing, PvE helpers, AI NPC experiments, and local gameplay automation.

## Features

### Bot presets

SmartBot includes several ready-made presets:

| Preset | Role | Description |
|---|---|---|
| `ARCHER` | Fighter | Bow user with soulshots and periodic Stun Shot |
| `MAGE` | Mage | Nuker using common C4 mage attack skills |
| `HEALER` | Healer | Cardinal-style healer with heals and resurrection |
| `BUFFER` | Buffer | Prophet-style support bot that buffs party members |
| `DAGGER` | Fighter | Dagger user with blow skills |
| `TANK` | Fighter | Sword + shield tank with aggro skills |

### Main capabilities

- GM-created persistent bots
- Bots restored after server restart
- Follow mode
- Assist mode
- Attack mode
- Stop / idle command
- Party invite support
- Healer support:
  - heals low-HP party members
  - follows assist/follow target
  - resurrects dead party members
- Buffer support:
  - detects missing buffs
  - applies fighter or mage buff sets depending on target class
  - supports direct `buff` chat command
- Combat support:
  - mage nukes
  - archer stun
  - tank aggro
  - dagger blow skills
  - soulshot / blessed spiritshot activation
- Local chat AI responses through Ollama
- Lightweight file-based knowledge system for Lineage 2 facts
- Short-term chat memory per bot
- Group commands using `all`, for example `follow all`

## Requirements

This code is intended for an L2J server source.

Expected packages/classes include:

- `net.sf.l2j.gameserver.model.actor.instance.L2PcInstance`
- `net.sf.l2j.gameserver.model.L2World`
- `net.sf.l2j.gameserver.model.L2Party`
- `net.sf.l2j.gameserver.datatables.SkillTable`
- `net.sf.l2j.gameserver.datatables.CharTemplateTable`
- `net.sf.l2j.gameserver.handler.IAdminCommandHandler`
- `net.sf.l2j.gameserver.network.serverpackets.CreatureSay`
- `net.sf.l2j.gameserver.network.serverpackets.AskJoinParty`

You may need small adjustments if your source uses different method names or package names.

## AI replies: Ollama

SmartBot can generate local chat replies using a local Ollama model.

Ollama is optional. If Ollama is offline, the bot falls back to simple short replies for commands and social chat.

### Install Ollama

Download and install Ollama from:

```text
https://ollama.com/download
```

On Linux, you can usually install it with:

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

After installation, start the model used by SmartBot:

```bash
ollama run gemma3
```

This will download the model if it is not already installed.

Ollama should expose its local API at:

```text
http://localhost:11434/api/generate
```

## Installation

### 1. Copy the Java files

Copy the SmartBot source files into your server source tree:

```text
java/net/sf/l2j/gameserver/smartbot/
java/net/sf/l2j/gameserver/smartbot/dao/
java/net/sf/l2j/gameserver/smartbot/model/
```

Required files:

```text
SmartBotManager.java
SmartBotController.java
BotLlmService.java

dao/SmartBotDao.java

model/BotMemory.java
model/SmartBotData.java
model/SmartBotKnowledge.java
model/SmartBotPreset.java
model/SmartBotRole.java
```

Copy the admin command handler:

```text
java/net/sf/l2j/gameserver/handler/admincommandhandlers/AdminSmartbot.java
```

### 2. Register the admin command handler

Find where your admin command handlers are registered.

Add:

```java
import net.sf.l2j.gameserver.handler.admincommandhandlers.AdminSmartbot;
```

Then register it:

```java
AdminCommandHandler.getInstance().registerAdminCommandHandler(new AdminSmartbot());
```

The exact location depends on your core. It is usually near other admin handlers such as `AdminAdmin`, `AdminTeleport`, `AdminSpawn`, etc.

### 3. Add admin command access

Add the admin command aliases to your admin command access file.

Depending on your core, this may be something like:

```text
config/adminCommands.xml
config/admin_access.xml
data/xml/adminCommands.xml
```

Add permissions for:

```text
admin_bot_create
admin_bot_follow
admin_bot_despawn
admin_bot_delete
admin_bot_unfollow
```

If your server supports aliases, map them to friendly commands:

```text
//bot_create
//bot_follow
//bot_despawn
//bot_delete
//bot_unfollow
```

Some cores require using the full command:

```text
//admin_bot_create
```

### 4. Add the database table

Create the `smart_bots` table.

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

### 5. Add config values

Add these to your config file, for example:

```properties
# SmartBot
BotChatRadius = 1250
BotAttackSearchRadius = 1200
BotMaxReplyWords = 6
BotMaxReplyChars = 70
BotReplyCooldownMs = 2000

BotOllamaUrl = http://127.0.0.1:11434/api/generate
BotOllamaModel = gemma3

BotMemoryLines = 20
BotKnowledgeFacts = 4
BotFollowDistance = 80

BotHealRange = 900
BotHealHpPercent = 85.0

BotManaPotionItemId = 728
BotManaPotionCount = 100
BotManaPotionMpPercent = 30.0
```

Then declare matching fields in `Config.java`:

```java
public static int BOT_CHAT_RADIUS;
public static long BOT_REPLY_COOLDOWN_MS;
public static int BOT_MAX_REPLY_WORDS;
public static int BOT_MAX_REPLY_CHARS;
public static int BOT_MEMORY_LINES;

public static int BOT_FOLLOW_DISTANCE;
public static int BOT_HEAL_RANGE;
public static double BOT_HEAL_HP_PERCENT;
public static int BOT_ATTACK_SEARCH_RADIUS;

public static String BOT_OLLAMA_URL;
public static String BOT_OLLAMA_MODEL;

public static int BOT_KNOWLEDGE_FACTS;

public static int BOT_MANA_POTION_ITEM_ID;
public static int BOT_MANA_POTION_COUNT;
public static double BOT_MANA_POTION_MP_PERCENT;
```

Load them the same way your server loads other config values.

Example:

```java
BOT_CHAT_RADIUS = Integer.parseInt(smartbotSettings.getProperty("BotChatRadius", "1250"));
BOT_REPLY_COOLDOWN_MS = Long.parseLong(smartbotSettings.getProperty("BotReplyCooldownMs", "5000"));
BOT_MAX_REPLY_WORDS = Integer.parseInt(smartbotSettings.getProperty("BotMaxReplyWords", "18"));
BOT_MAX_REPLY_CHARS = Integer.parseInt(smartbotSettings.getProperty("BotMaxReplyChars", "120"));
BOT_MEMORY_LINES = Integer.parseInt(smartbotSettings.getProperty("BotMemoryLines", "8"));

BOT_FOLLOW_DISTANCE = Integer.parseInt(smartbotSettings.getProperty("BotFollowDistance", "80"));
BOT_HEAL_RANGE = Integer.parseInt(smartbotSettings.getProperty("BotHealRange", "900"));
BOT_HEAL_HP_PERCENT = Double.parseDouble(smartbotSettings.getProperty("BotHealHpPercent", "85.0"));
BOT_ATTACK_SEARCH_RADIUS = Integer.parseInt(smartbotSettings.getProperty("BotAttackSearchRadius", "900"));

BOT_OLLAMA_URL = smartbotSettings.getProperty("BotOllamaUrl", "http://localhost:11434/api/generate");
BOT_OLLAMA_MODEL = smartbotSettings.getProperty("BotOllamaModel", "gemma3");

BOT_KNOWLEDGE_FACTS = Integer.parseInt(smartbotSettings.getProperty("BotKnowledgeFacts", "4"));

BOT_MANA_POTION_ITEM_ID = Integer.parseInt(smartbotSettings.getProperty("BotManaPotionItemId", "728"));
BOT_MANA_POTION_COUNT = Integer.parseInt(smartbotSettings.getProperty("BotManaPotionCount", "200"));
BOT_MANA_POTION_MP_PERCENT = Double.parseDouble(smartbotSettings.getProperty("BotManaPotionMpPercent", "45.0"));
```

### 6. Restore bots on server startup

Call this after the world/server is ready and characters can be spawned:

```java
SmartBotManager.INSTANCE.restoreBots();
```

A good place is usually near the end of game server initialization, after tables and world systems have loaded.

Add import:

```java
import net.sf.l2j.gameserver.smartbot.SmartBotManager;
```

### 7. Save bots on shutdown

Call this during server shutdown:

```java
SmartBotManager.INSTANCE.saveAll();
```

This stores bot location, heading, and active state.

### 8. Hook local chat into the LLM service

In your chat packet handler, usually `Say2`, call:

```java
BotLlmService.INSTANCE.handlePlayerAllChat(activeChar, text);
```

Only call it for local/all chat if you want nearby bot replies.

Example:

```java
if (_type == Say2.ALL)
{
    BotLlmService.INSTANCE.handlePlayerAllChat(activeChar, _text);
}
```

Add import:

```java
import net.sf.l2j.gameserver.smartbot.BotLlmService;
```

### 9. Hook party invite handling

In `RequestJoinParty`, after resolving the target player, add support for managed bots:

```java
if (target == null)
{
    target = SmartBotManager.INSTANCE.getSpawnedBotByName(_name);
}
```

Then before normal party invite handling:

```java
BotLlmService.INSTANCE.handlePartyRequest(requestor, target, _itemDistribution);
if (SmartBotManager.INSTANCE.isBot(target))
{
    return;
}
```

Depending on your implementation, `handlePartyRequest` may return `void` or `boolean`. Adjust the early return to match your code.

### 10. Copy SmartBot knowledge files

This repository includes a starter knowledge folder:

```text
config/smartbot/knowledge/
```

Copy that folder into your server config directory, so your server has:

```text
<your-server-root>/config/smartbot/knowledge/
```

The knowledge files are plain `.txt` files. Each non-empty, non-comment line becomes a knowledge fact.

Format:

```text
[tag1, tag2, tag3] Fact text goes here.
```

Example:

```text
[level, farm, c4] Level 78+ players should look at Imperial Tomb, Forge of the Gods, Wall of Argos, Ketra, or Varka depending on gear and party.
[buff, fighter] Fighter classes usually want Might, Haste, Focus, Death Whisper, Guidance, Vampiric Rage, Greater Might, and Greater Shield.
[buff, mage] Mage classes usually want Acumen, Empower, Blessed Body, Blessed Soul, Mental Shield, Shield, and Greater Shield.
```

Then call this during startup:

```java
SmartBotKnowledge.INSTANCE.load();
```

Import:

```java
import net.sf.l2j.gameserver.smartbot.model.SmartBotKnowledge;
```

The number of facts injected into the AI prompt is controlled by:

```properties
BotKnowledgeFacts = 4
```

## GM commands

### Create a bot

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

### Make targeted bot follow you

Target a managed bot, then use:

```text
//bot_follow
```

### Clear targeted bot follow target

Target a managed bot, then use:

```text
//bot_unfollow
```

### Despawn bot

Target a managed bot, then use:

```text
//bot_despawn
```

This removes the bot from the world but keeps its database record.

### Delete bot

Target a managed bot, then use:

```text
//bot_delete
```

This despawns the bot and removes it from the `smart_bots` table.

## Player chat commands

SmartBots listen to nearby local chat.

### Follow

```text
follow
```

or with a bot name:

```text
Mark follow
```

The selected bot follows the player.

### Follow all nearby bots

```text
follow all
all follow
```

All nearby bots enter follow mode.

### Assist

```text
assist
```

The selected bot assists the player.

For fighters, this means attacking the player's target.

For healers, this means following and healing.

For buffers, this means following and buffing instead of attacking.

### Assist all nearby bots

```text
assist all
all assist
```

All nearby bots assist the player.

### Attack

```text
attack
```

The selected bot attacks the nearest monster.

### Attack all nearby bots

```text
attack all
all attack
```

All nearby bots enter attack mode.

### Stop

```text
stop
wait
hold
```

Stops the selected bot.

### Stop all nearby bots

```text
stop all
all stop
```

Stops all nearby bots.

### Buff

```text
buff
buffs
```

The nearest buffer bot will try to buff the player.

You can also address a specific buffer:

```text
Eva buff me
```

### Party

```text
party
invite
```

The selected bot sends a party invite or accepts a player invite, depending on the flow implemented in your core.

## Bot behavior details

### Healer

The healer:

- checks party members
- heals targets under `BotHealHpPercent`
- follows the assist/follow target
- resurrects dead party members if in range
- does not attack while assisting

### Buffer

The buffer:

- detects missing buffs
- decides fighter/mage buff set based on class
- buffs party members
- supports direct `buff` command
- does not attack in assist mode
- follows the assist/follow target when no buffs are needed

### Tank

The tank:

- uses sword + shield preset equipment
- attempts aggro skills before normal attacks
- then fights normally

Current aggro skill candidates:

```text
28 - Aggression
18 - Aura of Hate
```

### Dagger

The dagger:

- uses dagger equipment
- attempts dagger skills before normal attacks

Current dagger skill candidates:

```text
263 - Deadly Blow
344 - Lethal Blow
```

### Archer

The archer:

- uses bow equipment
- uses soulshots
- attempts Stun Shot when cooldown allows

Current stun skill:

```text
101 - Stun Shot
```

### Mage

The mage attempts known nukes from this list:

```text
1239 - Hurricane
1230 - Prominence
1235 - Hydro Blast
1220 - Blaze
1177 - Wind Strike
```

## Knowledge system

SmartBot uses a simple retrieval system.

Knowledge files live in:

```text
config/smartbot/knowledge/
```

Only `.txt` files are loaded.

Each non-empty, non-comment line becomes a knowledge fact.

Format:

```text
[tag1, tag2, tag3] Fact text goes here.
```

Example:

```text
[level, farm, c4] Level 78+ players should look at Imperial Tomb, Forge of the Gods, Wall of Argos, Ketra, or Varka depending on gear and party.
[buff, fighter] Fighter classes usually want Might, Haste, Focus, Death Whisper, Guidance, Vampiric Rage, Greater Might, and Greater Shield.
[buff, mage] Mage classes usually want Acumen, Empower, Blessed Body, Blessed Soul, Mental Shield, Shield, and Greater Shield.
```

The number of facts injected into the prompt is controlled by:

```properties
BotKnowledgeFacts = 4
```

## Notes and limitations

- This is not a drop-in plugin for every L2J fork.
- Method names may differ between cores.
- Class IDs, item IDs, and skill IDs are tuned for a C4-style datapack and may need adjustment.
- Existing bots created before preset changes may need to be deleted and recreated.
- The local LLM is optional but recommended for natural chat.
- The knowledge system is intentionally simple and file-based.
- Bots are fake player instances and may behave differently depending on your core's AI/movement implementation.

## Troubleshooting

### Bot does not move

Check if the bot is overloaded.

In many L2J cores, overloaded characters cannot move. Reduce inventory weight or item count.

Common cause:

```text
too many soulshots, spiritshots, or potions
```

### LLM replies do not work

Start Ollama:

```bash
ollama run gemma3
```

Check config:

```properties
BotOllamaUrl = http://localhost:11434/api/generate
BotOllamaModel = gemma3
```

## Development notes

This project was built around a C4 open source server pack and tested with locally spawned fake-player bots.

Some parts of the implementation are intentionally simple:

- the knowledge system is plain text, not embeddings
- the LLM uses direct HTTP calls to Ollama
- each bot has a short in-memory chat history
- combat skills are chosen from fixed candidate skill IDs
- bot presets use fixed class IDs, item IDs, and buff IDs

This makes the project easy to understand, modify, and port to similar L2J cores.

## License

This project is licensed under the GNU General Public License v3.0.

See the `LICENSE` file for details.