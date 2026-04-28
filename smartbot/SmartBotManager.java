package net.sf.l2j.gameserver.smartbot;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.sf.l2j.gameserver.ai.CtrlIntention;
import net.sf.l2j.gameserver.smartbot.dao.SmartBotDao;
import net.sf.l2j.gameserver.smartbot.model.SmartBotData;
import net.sf.l2j.gameserver.smartbot.model.SmartBotPreset;
import net.sf.l2j.gameserver.datatables.CharTemplateTable;
import net.sf.l2j.gameserver.datatables.SkillTable;
import net.sf.l2j.gameserver.idfactory.IdFactory;
import net.sf.l2j.gameserver.model.L2Skill;
import net.sf.l2j.gameserver.model.L2World;
import net.sf.l2j.gameserver.model.actor.instance.L2PcInstance;
import net.sf.l2j.gameserver.model.L2ItemInstance;
import net.sf.l2j.gameserver.network.L2GameClient;
import net.sf.l2j.gameserver.smartbot.model.SmartBotRole;
import net.sf.l2j.gameserver.templates.L2PcTemplate;

public class SmartBotManager
{
    public static final SmartBotManager INSTANCE = new SmartBotManager();

    private final SmartBotDao dao = new SmartBotDao();
    private final Map<Integer, L2PcInstance> spawnedBots = new ConcurrentHashMap<>();
    private final Map<Integer, SmartBotController> controllers = new ConcurrentHashMap<>();
    private final Map<Integer, SmartBotData> botData = new ConcurrentHashMap<>();

    public void restoreBots()
    {
        for (SmartBotData data : dao.loadAll())
        {
            if (!data.isActive())
            {
                continue;
            }

            try
            {
                spawnExistingBot(data);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public L2PcInstance createBotFromPreset(String botName, SmartBotPreset preset, int x, int y, int z)
    {
        final int objectId = IdFactory.getInstance().getNextId();
        final L2PcTemplate template = CharTemplateTable.getInstance().getTemplate(preset.getClassId());
        if (template == null)
        {
            return null;
        }

        final L2PcInstance bot = L2PcInstance.create(
            objectId,
            template,
            "autobot_" + objectId,
            botName,
            (byte) 0,
            (byte) 0,
            (byte) 0,
            false
        );

        if (bot == null)
        {
            return null;
        }

        bot.setOnlineStatus(true);
        bot.setHeading(0);

        applyPreset(bot, preset);

        final SmartBotData data = new SmartBotData(
            bot.getObjectId(),
            botName,
            preset.getClassId(),
            x,
            y,
            z,
            0,
            0,
            40,
            true
        );

        dao.insert(data);
        internalSpawn(bot, data);
        return bot;
    }

    public boolean spawnExistingBot(SmartBotData data)
    {
        final L2PcInstance bot = L2PcInstance.load(data.getCharObjId());
        if (bot == null)
        {
            return false;
        }

        bot.setOnlineStatus(true);
        internalSpawn(bot, data);
        return true;
    }

    public void despawnBot(int objectId)
    {
        final L2PcInstance bot = spawnedBots.remove(objectId);
        final SmartBotController controller = controllers.remove(objectId);
        botData.remove(objectId);

        if (controller != null)
        {
            controller.stop();
        }

        if (bot != null)
        {
            saveBotState(bot);

            try
            {
                L2World.getInstance().removeFromAllPlayers(bot);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            bot.deleteMe();
            L2GameClient.saveCharToDisk(bot, false);
        }
    }

    public void deleteBot(int objectId)
    {
        despawnBot(objectId);
        dao.delete(objectId);
    }

    public void saveAll()
    {
        for (L2PcInstance bot : spawnedBots.values())
        {
            saveBotState(bot);
            L2GameClient.saveCharToDisk(bot, false);
        }
    }

    public void setFollowTarget(int botObjectId, int targetObjectId)
    {
        final SmartBotData data = botData.get(botObjectId);
        final L2PcInstance bot = spawnedBots.get(botObjectId);

        if (data == null)
        {
            return;
        }

        data.setFollowTargetObjId(targetObjectId);
        data.setAssistTargetObjId(0);
        data.setAttackMode(false);
        dao.update(data);

        if (bot != null)
        {
            bot.abortAttack();
            bot.abortCast();
            bot.setTarget(null);
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_IDLE);
        }
    }

    public void clearFollowTarget(int botObjectId)
    {
        final SmartBotData data = botData.get(botObjectId);
        if (data == null)
        {
            return;
        }

        data.setFollowTargetObjId(0);
        dao.update(data);
    }

    public boolean isBot(int objectId)
    {
        return spawnedBots.containsKey(objectId);
    }

    public boolean isBot(L2PcInstance player)
    {
        return player != null && spawnedBots.containsKey(player.getObjectId());
    }

    public L2PcInstance getSpawnedBot(int objectId)
    {
        return spawnedBots.get(objectId);
    }

    private void internalSpawn(L2PcInstance bot, SmartBotData data)
    {
        bot.setHeading(data.getHeading());
        bot.spawnMe(data.getSpawnX(), data.getSpawnY(), data.getSpawnZ());

        try
        {
            L2World.getInstance().addToAllPlayers(bot);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        bot.broadcastUserInfo();
        bot.updateAndBroadcastStatus(2);

        spawnedBots.put(bot.getObjectId(), bot);
        botData.put(bot.getObjectId(), data);

        final SmartBotController controller = new SmartBotController(bot, data);
        controllers.put(bot.getObjectId(), controller);
        controller.start();
    }

    private void saveBotState(L2PcInstance bot)
    {
        final SmartBotData data = botData.get(bot.getObjectId());
        if (data == null)
        {
            return;
        }

        data.setSpawnX(bot.getX());
        data.setSpawnY(bot.getY());
        data.setSpawnZ(bot.getZ());
        data.setHeading(bot.getHeading());
        data.setActive(true);

        dao.update(data);
    }

    private void applyPreset(L2PcInstance bot, SmartBotPreset preset)
    {
        final int level = preset.getLevel();

        bot.getStat().setLevel((byte) level);
        bot.getStat().setExp(bot.getStat().getExpForLevel(level));
        bot.getStat().setSp(0);

        bot.rewardSkills();

        for (int[] entry : preset.getItems())
        {
            final int itemId = entry[0];
            final long count = entry[1];

            bot.addItem("SmartBotPreset", itemId, (int) count, null, false);
        }

        for (L2ItemInstance item : bot.getInventory().getItems())
        {
            if (item != null && item.isEquipable() && !item.isEquipped())
            {
                bot.useEquippableItem(item, false);
            }
        }

        enablePresetShots(bot, preset);

        applyPresetBuffs(bot, preset);

        bot.setCurrentHp(bot.getMaxHp());
        bot.setCurrentMp(bot.getMaxMp());
        bot.setCurrentCp(bot.getMaxCp());
        bot.setRunning();

        bot.broadcastUserInfo();
        bot.updateAndBroadcastStatus(2);

        L2GameClient.saveCharToDisk(bot, false);
    }

    private void applyPresetBuffs(L2PcInstance bot, SmartBotPreset preset)
    {
        for (int[] buff : preset.getBuffSkillIds())
        {
            try
            {
                final int skillId = buff[0];
                final int skillLevel = buff.length > 1 ? buff[1] : 1;

                L2Skill skill = SkillTable.getInstance().getInfo(skillId, skillLevel);
                if (skill == null)
                {
                    continue;
                }

                skill.getEffects(bot, bot);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public Collection<L2PcInstance> getSpawnedBots()
    {
        return spawnedBots.values();
    }

    public void setAttackMode(int botObjectId, boolean enabled)
    {
        final SmartBotData data = botData.get(botObjectId);
        if (data == null)
        {
            return;
        }

        data.setAttackMode(enabled);
        dao.update(data);
    }

    public void stopBotActions(int botObjectId)
    {
        final SmartBotData data = botData.get(botObjectId);
        final L2PcInstance bot = spawnedBots.get(botObjectId);

        if (data != null)
        {
            data.setFollowTargetObjId(0);
            data.setAssistTargetObjId(0);
            data.setAttackMode(false);
            dao.update(data);
        }

        if (bot != null)
        {
            bot.setTarget(null);
            bot.abortAttack();
            bot.abortCast();
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_IDLE);
        }
    }

    public void setAssistTarget(int botObjectId, int targetObjectId)
    {
        final SmartBotData data = botData.get(botObjectId);
        if (data == null)
        {
            return;
        }

        data.setAssistTargetObjId(targetObjectId);
    }

    public void clearAssistTarget(int botObjectId)
    {
        final SmartBotData data = botData.get(botObjectId);
        if (data == null)
        {
            return;
        }

        data.setAssistTargetObjId(0);
    }

    public void restoreBotBuffs(int botObjectId)
    {
        final L2PcInstance bot = spawnedBots.get(botObjectId);
        final SmartBotData data = botData.get(botObjectId);

        if (bot == null || data == null || bot.isDead())
        {
            return;
        }

        final SmartBotPreset preset = SmartBotPreset.fromClassId(data.getTemplateClassId());
        if (preset == null)
        {
            return;
        }

        applyPresetBuffs(bot, preset);

        bot.setCurrentHp(bot.getMaxHp());
        bot.setCurrentMp(bot.getMaxMp());
        bot.setCurrentCp(bot.getMaxCp());

        bot.broadcastUserInfo();
        bot.updateAndBroadcastStatus(2);
    }

    private void enablePresetShots(L2PcInstance bot, SmartBotPreset preset)
    {
        for (int[] entry : preset.getItems())
        {
            final int itemId = entry[0];

            // Soulshot: S-Grade
            if (itemId == 1467)
            {
                bot.addAutoSoulShot(itemId);
                bot.rechargeShots(true, false);
            }

            // Blessed Spiritshot: S-Grade
            if (itemId == 3952)
            {
                bot.addAutoSoulShot(itemId);
                bot.rechargeShots(false, true);
            }
        }
    }

    public L2PcInstance getSpawnedBotByName(String name)
    {
        if (name == null)
        {
            return null;
        }

        for (L2PcInstance bot : spawnedBots.values())
        {
            if (bot == null)
            {
                continue;
            }

            if (bot.getName().equalsIgnoreCase(name))
            {
                return bot;
            }
        }

        return null;
    }

    public boolean requestBuff(int botObjectId, L2PcInstance target)
    {
        final L2PcInstance bot = spawnedBots.get(botObjectId);
        final SmartBotController controller = controllers.get(botObjectId);

        if (bot == null || controller == null || target == null)
        {
            return false;
        }

        final SmartBotData data = botData.get(botObjectId);
        if (data == null)
        {
            return false;
        }

        final SmartBotPreset preset = SmartBotPreset.fromClassId(data.getTemplateClassId());
        if (preset == null || preset.getRole() != SmartBotRole.BUFFER)
        {
            return false;
        }

        controller.requestBuff(target);
        return true;
    }

    public boolean isRole(L2PcInstance bot, SmartBotRole role)
    {
        if (bot == null || role == null)
        {
            return false;
        }

        final SmartBotData data = botData.get(bot.getObjectId());
        if (data == null)
        {
            return false;
        }

        final SmartBotPreset preset = SmartBotPreset.fromClassId(data.getTemplateClassId());
        return preset != null && preset.getRole() == role;
    }
}