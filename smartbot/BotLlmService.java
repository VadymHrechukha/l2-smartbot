package net.sf.l2j.gameserver.smartbot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.l2j.Config;
import net.sf.l2j.gameserver.ThreadPoolManager;
import net.sf.l2j.gameserver.ai.CtrlIntention;
import net.sf.l2j.gameserver.network.serverpackets.AskJoinParty;
import net.sf.l2j.gameserver.network.serverpackets.SystemMessage;
import net.sf.l2j.gameserver.smartbot.model.BotMemory;
import net.sf.l2j.gameserver.model.L2Party;
import net.sf.l2j.gameserver.model.L2World;
import net.sf.l2j.gameserver.model.actor.instance.L2MonsterInstance;
import net.sf.l2j.gameserver.model.actor.instance.L2PcInstance;
import net.sf.l2j.gameserver.network.clientpackets.Say2;
import net.sf.l2j.gameserver.network.serverpackets.CreatureSay;
import net.sf.l2j.gameserver.network.serverpackets.JoinParty;
import net.sf.l2j.gameserver.smartbot.model.SmartBotKnowledge;
import net.sf.l2j.gameserver.smartbot.model.SmartBotRole;
import net.sf.l2j.util.Rnd;

public class BotLlmService
{
    public static final BotLlmService INSTANCE = new BotLlmService();

    private static final Pattern RESPONSE_PATTERN =
            Pattern.compile("\"response\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final HttpClient httpClient;
    private final Map<Integer, Long> lastReplyAt = new ConcurrentHashMap<>();
    private final Map<Integer, BotMemory> memories = new ConcurrentHashMap<>();

    private BotLlmService()
    {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public void handlePlayerAllChat(L2PcInstance speaker, String rawMessage)
    {
        if (speaker == null || rawMessage == null)
        {
            return;
        }

        final String message = rawMessage.trim();
        if (message.isEmpty())
        {
            return;
        }

        final ChatCommand command = parseCommand(message);
        final boolean allCommand = isAllCommand(message);

        if (allCommand && isGroupCommand(command))
        {
            handleAllBotsCommand(speaker, message, command);
            return;
        }

        final L2PcInstance bot = command == ChatCommand.BUFF
                ? selectBufferForMessage(speaker, message)
                : selectBotForMessage(speaker, message);
        if (bot == null)
        {
            return;
        }

        final BotMemory memory = getMemory(bot);
        memory.add(speaker.getName() + ": " + message);

        switch (command)
        {
            case FOLLOW:
            {
                SmartBotManager.INSTANCE.setAttackMode(bot.getObjectId(), false);
                SmartBotManager.INSTANCE.clearAssistTarget(bot.getObjectId());
                SmartBotManager.INSTANCE.setFollowTarget(bot.getObjectId(), speaker.getObjectId());

                final String reply = generateCommandReply(bot, speaker, "follow", "success");
                if (!reply.isBlank())
                {
                    memory.add(bot.getName() + ": " + reply);
                    sayWithHumanDelay(bot, reply, true);
                }
                return;
            }

            case PARTY:
            {
                final String reply;

                if (sendPartyInviteToPlayer(bot, speaker))
                {
                    reply = generateCommandReply(bot, speaker, "party", "success");
                }
                else
                {
                    reply = generateCommandReply(bot, speaker, "party", "failed");
                }

                if (!reply.isBlank())
                {
                    memory.add(bot.getName() + ": " + reply);
                    sayWithHumanDelay(bot, reply, true);
                }
                return;
            }

            case ASSIST:
            {
                SmartBotManager.INSTANCE.clearFollowTarget(bot.getObjectId());
                SmartBotManager.INSTANCE.setAttackMode(bot.getObjectId(), false);
                SmartBotManager.INSTANCE.setAssistTarget(bot.getObjectId(), speaker.getObjectId());

                final String reply = generateCommandReply(bot, speaker, "assist", "success");
                if (!reply.isBlank())
                {
                    memory.add(bot.getName() + ": " + reply);
                    sayWithHumanDelay(bot, reply, true);
                }
                return;
            }

            case ATTACK:
            {
                SmartBotManager.INSTANCE.clearFollowTarget(bot.getObjectId());
                SmartBotManager.INSTANCE.setAttackMode(bot.getObjectId(), true);

                final String reply;

                if (commandAttackNearestMob(bot))
                {
                    reply = generateCommandReply(bot, speaker, "attack", "success");
                }
                else
                {
                    reply = generateCommandReply(bot, speaker, "attack", "nomob");
                }

                if (!reply.isBlank())
                {
                    memory.add(bot.getName() + ": " + reply);
                    sayWithHumanDelay(bot, reply, true);
                }
                return;
            }

            case BUFF:
            {
                final String reply;

                if (SmartBotManager.INSTANCE.requestBuff(bot.getObjectId(), speaker))
                {
                    reply = generateCommandReply(bot, speaker, "buff", "success");
                }
                else
                {
                    reply = generateCommandReply(bot, speaker, "buff", "failed");
                }

                if (!reply.isBlank())
                {
                    memory.add(bot.getName() + ": " + reply);
                    sayWithHumanDelay(bot, reply, true);
                }
                return;
            }

            case STOP:
            {
                SmartBotManager.INSTANCE.stopBotActions(bot.getObjectId());

                final String reply = generateCommandReply(bot, speaker, "stop", "success");
                if (!reply.isBlank())
                {
                    memory.add(bot.getName() + ": " + reply);
                    sayWithHumanDelay(bot, reply, true);
                }
                return;
            }

            case NONE:
            default:
            {
                if (!canReply(bot))
                {
                    return;
                }

                String reply = generateNearbyReply(bot, speaker, message);
                if (reply == null || reply.isBlank())
                {
                    reply = fallbackSocialReply(message);
                }

                if (reply.isBlank())
                {
                    return;
                }

                memory.add(bot.getName() + ": " + reply);
                sayWithHumanDelay(bot, reply, false);
                lastReplyAt.put(bot.getObjectId(), System.currentTimeMillis());
            }
        }
    }

    public void handlePartyRequest(L2PcInstance requestor, L2PcInstance target, int itemDistribution)
    {
        if (requestor == null || target == null)
        {
            return;
        }

        if (!SmartBotManager.INSTANCE.isBot(target))
        {
            return;
        }

        if (SmartBotManager.INSTANCE.isBot(target))
        {
            target.setLootInvitation(-1);
            target.setActiveRequester(null);
            target.onTransactionResponse();
        }

        final BotMemory memory = getMemory(target);
        memory.add(requestor.getName() + ": party invite");

        if (target.isInParty())
        {
            requestor.sendMessage(target.getName() + " is already in party.");
            return;
        }

        if (requestor == target)
        {
            requestor.sendMessage("Incorrect target.");
            return;
        }

        if (target.isProcessingRequest() || requestor.isProcessingRequest())
        {
            requestor.sendMessage("Busy, try later.");
            return;
        }

        /*
         * Make the player feel like a real invite was sent.
         * We don't send AskJoinParty to the bot because it has no client to answer it.
         */
        requestor.sendMessage("You invited " + target.getName() + " to party.");

        final int loot = Math.max(itemDistribution, 0);

        ThreadPoolManager.getInstance().scheduleGeneral(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {

                    if (!SmartBotManager.INSTANCE.isBot(target))
                    {
                        return;
                    }

                    if (target.isDead() || target.isTeleporting())
                    {
                        return;
                    }

                    if (target.isInParty())
                    {
                        return;
                    }

                    if (!requestor.isOnline() || requestor.isDead() || requestor.isTeleporting())
                    {
                        return;
                    }

                    if (!requestor.isInParty())
                    {
                        requestor.setLootInvitation(loot);
                        requestor.setParty(new L2Party(requestor));
                    }
                    else
                    {
                        if (!requestor.getParty().isLeader(requestor))
                        {
                            requestor.sendPacket(new SystemMessage(SystemMessage.ONLY_LEADER_CAN_INVITE));
                            return;
                        }

                        if (requestor.getParty().getMemberCount() >= 9)
                        {
                            requestor.sendPacket(new SystemMessage(SystemMessage.PARTY_FULL));
                            return;
                        }
                    }

                    target.joinParty(requestor.getParty());

                    requestor.setLootInvitation(-1);
                    target.setLootInvitation(-1);

                    requestor.onTransactionResponse();
                    target.onTransactionResponse();

                    requestor.setActiveRequester(null);
                    target.setActiveRequester(null);

                    requestor.sendPacket(new JoinParty(1));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }, Rnd.get(900, 2200));

    }

    private L2PcInstance selectBotForMessage(L2PcInstance speaker, String rawMessage)
    {
        final String normalized = normalize(rawMessage);

        final L2PcInstance namedBot = findBotByNameMention(speaker, normalized);
        if (namedBot != null)
        {
            return namedBot;
        }

        return findNearestNearbyBot(speaker);
    }

    private L2PcInstance findBotByNameMention(L2PcInstance speaker, String normalizedMessage)
    {
        L2PcInstance best = null;
        double bestDistance = Double.MAX_VALUE;
        final double maxDistSq = Config.BOT_CHAT_RADIUS * Config.BOT_CHAT_RADIUS;

        for (L2PcInstance bot : SmartBotManager.INSTANCE.getSpawnedBots())
        {
            if (bot == null || bot.isDead())
            {
                continue;
            }

            final double distance = speaker.getDistanceSq(bot);
            if (distance > maxDistSq)
            {
                continue;
            }

            final String botName = normalize(bot.getName());
            if (!containsWord(normalizedMessage, botName))
            {
                continue;
            }

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = bot;
            }
        }

        return best;
    }

    private L2PcInstance findNearestNearbyBot(L2PcInstance speaker)
    {
        L2PcInstance best = null;
        double bestDistance = Double.MAX_VALUE;
        final double maxDistSq = Config.BOT_CHAT_RADIUS * Config.BOT_CHAT_RADIUS;

        for (L2PcInstance bot : SmartBotManager.INSTANCE.getSpawnedBots())
        {
            if (bot == null || bot.isDead())
            {
                continue;
            }

            final double distance = speaker.getDistanceSq(bot);
            if (distance > maxDistSq)
            {
                continue;
            }

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = bot;
            }
        }

        return best;
    }

    private ChatCommand parseCommand(String rawMessage)
    {
        final String msg = normalize(rawMessage);

        if (containsWord(msg, "follow"))
        {
            return ChatCommand.FOLLOW;
        }

        if (containsWord(msg, "party") || containsWord(msg, "invite"))
        {
            return ChatCommand.PARTY;
        }

        if (containsWord(msg, "assist"))
        {
            return ChatCommand.ASSIST;
        }

        if (containsWord(msg, "attack") || containsWord(msg, "hit"))
        {
            return ChatCommand.ATTACK;
        }

        if (containsWord(msg, "buff") || containsWord(msg, "buffs"))
        {
            return ChatCommand.BUFF;
        }

        if (containsWord(msg, "stop") || containsWord(msg, "wait") || containsWord(msg, "hold"))
        {
            return ChatCommand.STOP;
        }

        return ChatCommand.NONE;
    }

    private boolean commandAttackNearestMob(L2PcInstance bot)
    {
        final L2MonsterInstance mob = findNearestAttackableMob(bot, Config.BOT_ATTACK_SEARCH_RADIUS);
        if (mob == null)
        {
            return false;
        }

        bot.setTarget(mob);
        bot.getAI().setIntention(CtrlIntention.AI_INTENTION_ATTACK, mob);
        return true;
    }

    private L2MonsterInstance findNearestAttackableMob(L2PcInstance bot, int radius)
    {
        L2MonsterInstance best = null;
        double bestDistance = Double.MAX_VALUE;
        final double maxDistSq = radius * radius;

        final Collection<?> visible = L2World.getInstance().getVisibleObjects(bot, radius);

        for (Object obj : visible)
        {
            if (!(obj instanceof L2MonsterInstance))
            {
                continue;
            }

            final L2MonsterInstance mob = (L2MonsterInstance) obj;
            if (mob.isDead())
            {
                continue;
            }

            final double dist = bot.getDistanceSq(mob);
            if (dist > maxDistSq)
            {
                continue;
            }

            if (dist < bestDistance)
            {
                bestDistance = dist;
                best = mob;
            }
        }

        return best;
    }

    private boolean sendPartyInviteToPlayer(L2PcInstance bot, L2PcInstance player)
    {
        if (bot == null || player == null)
        {
            return false;
        }

        if (bot.isInParty())
        {
            if (player.isInParty())
            {
                return bot.getParty() == player.getParty();
            }

            if (!bot.getParty().isLeader(bot))
            {
                return false;
            }

            if (bot.getParty().getMemberCount() >= 9)
            {
                return false;
            }

            if (player.isProcessingRequest())
            {
                return false;
            }

            bot.setLootInvitation(bot.getParty().getLootDistribution());
            bot.onTransactionRequest(player);
            player.sendPacket(new AskJoinParty(bot));
            return true;
        }

        if (player.isInParty())
        {
            return false;
        }

        if (player.isProcessingRequest())
        {
            return false;
        }

        bot.setLootInvitation(0);
        bot.onTransactionRequest(player);
        player.sendPacket(new AskJoinParty(bot));
        return true;
    }

    private boolean canReply(L2PcInstance bot)
    {
        final long now = System.currentTimeMillis();
        final long last = lastReplyAt.getOrDefault(bot.getObjectId(), 0L);
        return (now - last) >= Config.BOT_REPLY_COOLDOWN_MS;
    }

    private String generateNearbyReply(L2PcInstance bot, L2PcInstance speaker, String playerMessage)
    {
        final String memoryText = getMemory(bot).render();

        final String knowledgeText = SmartBotKnowledge.INSTANCE.findRelevant(
                playerMessage,
                Config.BOT_KNOWLEDGE_FACTS
        );

        final boolean hasKnowledge = knowledgeText != null && !knowledgeText.trim().isEmpty();

        final String prompt =
            "You are a Lineage 2 C4 Scions of Destiny player named " + safePrompt(bot.getName()) + ". " +
            "Reply like a real nearby MMO player in local chat. " +
            "Always respond to greetings, short talk, direct questions, and when your name is mentioned. " +
            "Keep it natural, brief, human, and in-character for a fantasy MMO player. " +
            "Maximum " + Config.BOT_MAX_REPLY_WORDS + " words. " +
            "No emojis. No markdown. No lists. No explanations. No assistant tone. " +
            "Do not sound like an AI. " +
            "Prefer casual lowercase chat style. " +
            "Avoid repeating the same question or reply if it was said recently. " +
            "Use the recent chat only as short-term memory. " +

            "Use game knowledge only if it is relevant to the player message. " +
            "If the player asks about Lineage 2 facts, zones, levels, classes, skills, quests, drops, or mechanics, only answer from relevant game knowledge. " +
            "If relevant game knowledge is empty or does not contain the answer, say you are not sure in a short natural way. " +
            "Do not invent zones, levels, NPCs, quests, drops, skills, or mechanics. " +
            "Never recommend newbie zones for high-level players. " +
            "Cedric's Training Hall is a newbie/tutorial area and must never be recommended for level 78 or 79. " +
            "Do not mention the knowledge text or recent chat directly.\n" +

            "Recent chat:\n" + safePrompt(memoryText) + "\n" +
            "Relevant game knowledge available: " + (hasKnowledge ? "yes" : "no") + "\n" +
            "Relevant game knowledge:\n" + safePrompt(knowledgeText) + "\n" +
            "Player says: " + safePrompt(playerMessage);

        final String requestJson =
                "{"
                        + "\"model\":\"" + jsonEscape(Config.BOT_OLLAMA_MODEL) + "\","
                        + "\"prompt\":\"" + jsonEscape(prompt) + "\","
                        + "\"stream\":false"
                        + "}";

        try
        {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.BOT_OLLAMA_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
            {
                return null;
            }

            final String parsed = extractResponseField(response.body());
            return sanitizeReply(parsed);
        }
        catch (IOException | InterruptedException e)
        {
            return null;
        }
    }

    private String generateCommandReply(L2PcInstance bot, L2PcInstance speaker, String action, String result)
    {
        return generateCommandReply(bot, speaker, action, result, false);
    }

    private String generateCommandReply(L2PcInstance bot, L2PcInstance speaker, String action, String result, boolean groupCommand)
    {
        final String memoryText = getMemory(bot).render();

        final String prompt =
            "You are a Lineage 2 player named " + safePrompt(bot.getName()) + ". " +
            "Reply in local chat like a real MMO player. " +
            "You are responding to a command from player " + safePrompt(speaker.getName()) + ". " +
            "Action: " + safePrompt(action) + ". " +
            "Result: " + safePrompt(result) + ". " +
            (groupCommand
                    ? "The command was for all nearby bots. Reply as one player acknowledging for the group, using words like we/us if natural. "
                    : "The command was for you only. Reply only for yourself. ") +
            "Recent chat:\n" + safePrompt(memoryText) + "\n" +
            "Write one very short natural reply. " +
            "Maximum 4 words. " +
            "Prefer casual lowercase phrasing. " +
            "Avoid repeating the exact same reply too often. " +
            "No emojis. No markdown. No assistant tone. No explanations.";

        final String requestJson =
                "{"
                    + "\"model\":\"" + jsonEscape(Config.BOT_OLLAMA_MODEL) + "\","
                    + "\"prompt\":\"" + jsonEscape(prompt) + "\","
                    + "\"stream\":false"
                    + "}";

        try
        {
            final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.BOT_OLLAMA_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
            {
                return fallbackCommandReply(action, result, groupCommand);
            }

            final String parsed = extractResponseField(response.body());
            final String sanitized = sanitizeShortReply(parsed, 4, 40);
            return sanitized != null ? sanitized : fallbackCommandReply(action, result, groupCommand);
        }
        catch (Exception e)
        {
            return fallbackCommandReply(action, result, groupCommand);
        }
    }

    private String sanitizeShortReply(String text, int maxWords, int maxChars)
    {
        if (text == null)
        {
            return null;
        }

        String s = text.trim();
        if (s.isEmpty())
        {
            return null;
        }

        s = s.replace("\r", " ").replace("\n", " ").trim();
        s = s.replaceAll("\\s+", " ");

        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
        {
            s = s.substring(1, s.length() - 1).trim();
        }

        if (s.length() > maxChars)
        {
            s = s.substring(0, maxChars).trim();
        }

        final String[] parts = s.split("\\s+");
        if (parts.length > maxWords)
        {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxWords; i++)
            {
                if (i > 0)
                {
                    sb.append(' ');
                }
                sb.append(parts[i]);
            }
            s = sb.toString().trim();
        }

        return s.isBlank() ? null : decapitalizeReply(s);
    }

    private String fallbackCommandReply(String action, String result)
    {
        return fallbackCommandReply(action, result, false);
    }


    private String fallbackCommandReply(String action, String result, boolean groupCommand)
    {
        if (groupCommand)
        {
            if ("follow".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
            {
                return "we're coming";
            }

            if ("attack".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
            {
                return "we're on it";
            }

            if ("stop".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
            {
                return "we'll hold";
            }

            if ("attack".equalsIgnoreCase(action) && "nomob".equalsIgnoreCase(result))
            {
                return "nothing near us";
            }

            return "we got it";
        }

        if ("follow".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
        {
            return "on my way";
        }

        if ("party".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
        {
            return "sent invite";
        }

        if ("attack".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
        {
            return "engaging";
        }

        if ("stop".equalsIgnoreCase(action) && "success".equalsIgnoreCase(result))
        {
            return "standing by";
        }

        if ("attack".equalsIgnoreCase(action) && "nomob".equalsIgnoreCase(result))
        {
            return "nothing nearby";
        }

        return "ok";
    }

    private String fallbackSocialReply(String message)
    {
        final String msg = normalize(message);

        if (containsWord(msg, "hi") || containsWord(msg, "hello") || containsWord(msg, "hey"))
        {
            return "hey";
        }

        if (containsWord(msg, "yo") || containsWord(msg, "sup"))
        {
            return "yo";
        }

        if (msg.endsWith("?"))
        {
            return "maybe";
        }

        return "hmm";
    }

    private String sanitizeReply(String text)
    {
        if (text == null)
        {
            return null;
        }

        String s = text.trim();
        if (s.isEmpty())
        {
            return null;
        }

        s = s.replace("\r", " ").replace("\n", " ").trim();
        s = s.replaceAll("\\s+", " ");

        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
        {
            s = s.substring(1, s.length() - 1).trim();
        }

        if (s.length() > Config.BOT_MAX_REPLY_CHARS)
        {
            s = s.substring(0, Config.BOT_MAX_REPLY_CHARS).trim();
        }

        final String[] parts = s.split("\\s+");
        if (parts.length > Config.BOT_MAX_REPLY_WORDS)
        {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Config.BOT_MAX_REPLY_WORDS; i++)
            {
                if (i > 0)
                {
                    sb.append(' ');
                }
                sb.append(parts[i]);
            }
            s = sb.toString().trim();
        }

        return s.isBlank() ? null : decapitalizeReply(s);
    }

    private String extractResponseField(String json)
    {
        if (json == null)
        {
            return null;
        }

        final Matcher matcher = RESPONSE_PATTERN.matcher(json);
        if (!matcher.find())
        {
            return null;
        }

        return jsonUnescape(matcher.group(1));
    }

    private void say(L2PcInstance bot, String text)
    {
        if (bot == null || text == null || text.isBlank())
        {
            return;
        }

        final CreatureSay cs = new CreatureSay(bot.getObjectId(), Say2.ALL, bot.getName(), text);

        for (L2PcInstance player : bot.getKnownList().getKnownPlayers().values())
        {
            if ((player != null) && bot.isInsideRadius(player, 1250, false, true))
            {
                player.sendPacket(cs);
            }
        }

        bot.sendPacket(cs);
    }

    private void sayWithHumanDelay(L2PcInstance bot, String text, boolean commandReply)
    {
        if (bot == null || text == null || text.isBlank())
        {
            return;
        }

        int base = commandReply ? 350 : 700;
        int perWord = commandReply ? 90 : 180;

        int words = text.trim().split("\\s+").length;
        int delay = base + (words * perWord) + Rnd.get(250, 900);

        ThreadPoolManager.getInstance().scheduleGeneral(new Runnable()
        {
            @Override
            public void run()
            {
                say(bot, text);
            }
        }, delay);
    }

    private String decapitalizeReply(String text)
    {
        if (text == null || text.isBlank())
        {
            return text;
        }

        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++)
        {
            if (Character.isLetter(chars[i]))
            {
                chars[i] = Character.toLowerCase(chars[i]);
                return new String(chars);
            }
        }

        return text;
    }

    private boolean containsWord(String haystack, String needle)
    {
        if (haystack == null || needle == null || haystack.isEmpty() || needle.isEmpty())
        {
            return false;
        }

        return haystack.matches(".*(?:^|\\W)" + Pattern.quote(needle) + "(?:$|\\W).*");
    }

    private String normalize(String s)
    {
        return s == null ? "" : s.toLowerCase().trim();
    }

    private String safePrompt(String s)
    {
        if (s == null)
        {
            return "";
        }

        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String jsonEscape(String s)
    {
        if (s == null)
        {
            return "";
        }

        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String jsonUnescape(String s)
    {
        if (s == null)
        {
            return null;
        }

        return s
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\/", "/");
    }

    private BotMemory getMemory(L2PcInstance bot)
    {
        return memories.computeIfAbsent(
                bot.getObjectId(),
                k -> new BotMemory(Config.BOT_MEMORY_LINES)
        );
    }

    public void clearMemory(int botObjectId)
    {
        memories.remove(botObjectId);
    }

    private enum ChatCommand
    {
        NONE,
        FOLLOW,
        PARTY,
        ATTACK,
        ASSIST,
        BUFF,
        STOP
    }

    private boolean isAllCommand(String rawMessage)
    {
        return containsWord(normalize(rawMessage), "all");
    }

    private boolean isGroupCommand(ChatCommand command)
    {
        return command == ChatCommand.FOLLOW
                || command == ChatCommand.ASSIST
                || command == ChatCommand.ATTACK
                || command == ChatCommand.STOP;
    }

    private void handleAllBotsCommand(L2PcInstance speaker, String message, ChatCommand command)
    {
        int affected = 0;
        boolean attackFoundMob = false;

        for (L2PcInstance bot : getNearbyBots(speaker))
        {
            if (bot == null || bot.isDead())
            {
                continue;
            }

            final BotMemory memory = getMemory(bot);
            memory.add(speaker.getName() + ": " + message);

            switch (command)
            {
                case FOLLOW:
                {
                    SmartBotManager.INSTANCE.setAttackMode(bot.getObjectId(), false);
                    SmartBotManager.INSTANCE.clearAssistTarget(bot.getObjectId());
                    SmartBotManager.INSTANCE.setFollowTarget(bot.getObjectId(), speaker.getObjectId());
                    affected++;
                    break;
                }

                case ASSIST:
                {
                    SmartBotManager.INSTANCE.clearFollowTarget(bot.getObjectId());
                    SmartBotManager.INSTANCE.setAttackMode(bot.getObjectId(), false);
                    SmartBotManager.INSTANCE.setAssistTarget(bot.getObjectId(), speaker.getObjectId());
                    affected++;
                    break;
                }

                case ATTACK:
                {
                    SmartBotManager.INSTANCE.clearFollowTarget(bot.getObjectId());
                    SmartBotManager.INSTANCE.clearAssistTarget(bot.getObjectId());
                    SmartBotManager.INSTANCE.setAttackMode(bot.getObjectId(), true);

                    if (commandAttackNearestMob(bot))
                    {
                        attackFoundMob = true;
                    }

                    affected++;
                    break;
                }

                case STOP:
                {
                    SmartBotManager.INSTANCE.stopBotActions(bot.getObjectId());
                    affected++;
                    break;
                }

                default:
                {
                    break;
                }
            }
        }

        if (affected <= 0)
        {
            return;
        }

        /*
         * Only one nearby bot replies, otherwise "all follow" makes every bot spam chat.
         */
        final L2PcInstance replyBot = findNearestNearbyBot(speaker);
        if (replyBot == null)
        {
            return;
        }

        final String action;
        final String result;

        switch (command)
        {
            case FOLLOW:
            {
                action = "follow";
                result = "success";
                break;
            }

            case ASSIST:
            {
                action = "assist";
                result = "success";
                break;
            }

            case ATTACK:
            {
                action = "attack";
                result = attackFoundMob ? "success" : "nomob";
                break;
            }

            case STOP:
            {
                action = "stop";
                result = "success";
                break;
            }

            default:
            {
                action = "command";
                result = "success";
                break;
            }
        }

        final String reply = generateCommandReply(replyBot, speaker, action, result, true);
        if (!reply.isBlank())
        {
            getMemory(replyBot).add(replyBot.getName() + ": " + reply);
            sayWithHumanDelay(replyBot, reply, true);
        }
    }

    private Collection<L2PcInstance> getNearbyBots(L2PcInstance speaker)
    {
        final List<L2PcInstance> result = new ArrayList<>();
        final double maxDistSq = Config.BOT_CHAT_RADIUS * Config.BOT_CHAT_RADIUS;

        for (L2PcInstance bot : SmartBotManager.INSTANCE.getSpawnedBots())
        {
            if (bot == null || bot.isDead())
            {
                continue;
            }

            if (speaker.getDistanceSq(bot) > maxDistSq)
            {
                continue;
            }

            result.add(bot);
        }

        return result;
    }

    private L2PcInstance selectBufferForMessage(L2PcInstance speaker, String rawMessage)
    {
        final String normalized = normalize(rawMessage);

        L2PcInstance best = null;
        double bestDistance = Double.MAX_VALUE;
        final double maxDistSq = Config.BOT_CHAT_RADIUS * Config.BOT_CHAT_RADIUS;

        for (L2PcInstance bot : SmartBotManager.INSTANCE.getSpawnedBots())
        {
            if (bot == null || bot.isDead())
            {
                continue;
            }

            if (!SmartBotManager.INSTANCE.isRole(bot, SmartBotRole.BUFFER))
            {
                continue;
            }

            final double distance = speaker.getDistanceSq(bot);
            if (distance > maxDistSq)
            {
                continue;
            }

            final String botName = normalize(bot.getName());

            if (containsWord(normalized, botName))
            {
                return bot;
            }

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = bot;
            }
        }

        return best;
    }
}