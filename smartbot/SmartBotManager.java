package org.l2jmobius.gameserver.smartbot;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.smartbot.dao.SmartBotDao;
import org.l2jmobius.gameserver.smartbot.model.SmartBotData;
import org.l2jmobius.gameserver.smartbot.model.SmartBotPreset;
import org.l2jmobius.gameserver.smartbot.model.SmartBotRole;

public class SmartBotManager
{
	private final SmartBotDao dao = new SmartBotDao();
	private final Map<Integer, Player> spawnedBots = new ConcurrentHashMap<>();
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
	
	public Player createBotFromPreset(String botName, SmartBotPreset preset, int x, int y, int z)
	{
		final PlayerTemplate template = PlayerTemplateData.getInstance().getTemplate(preset.getClassId());
		if (template == null)
		{
			return null;
		}
		
		final PlayerAppearance appearance = new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false);
		final Player bot = Player.create(template, "autobot_" + botName.toLowerCase(), botName, appearance);
		if (bot == null)
		{
			return null;
		}
		
		bot.setOnlineStatus(true, true);
		bot.setHeading(0);
		
		applyPreset(bot, preset);
		
		final SmartBotData data = new SmartBotData(bot.getObjectId(), botName, preset.getClassId(), x, y, z, 0, 0, 40, true);
		
		dao.insert(data);
		internalSpawn(bot, data);
		return bot;
	}
	
	public boolean spawnExistingBot(SmartBotData data)
	{
		final Player bot = Player.load(data.getCharObjId());
		if (bot == null)
		{
			return false;
		}
		
		bot.setOnlineStatus(true, true);
		internalSpawn(bot, data);
		return true;
	}
	
	public void despawnBot(int objectId)
	{
		final Player bot = spawnedBots.remove(objectId);
		final SmartBotController controller = controllers.remove(objectId);
		botData.remove(objectId);
		
		if (controller != null)
		{
			controller.stop();
		}
		
		if (bot != null)
		{
			saveBotState(bot);
			bot.storeMe();
			bot.deleteMe();
		}
	}
	
	public void deleteBot(int objectId)
	{
		despawnBot(objectId);
		dao.delete(objectId);
	}
	
	public void saveAll()
	{
		for (Player bot : spawnedBots.values())
		{
			saveBotState(bot);
			bot.storeMe();
		}
	}
	
	public void setFollowTarget(int botObjectId, int targetObjectId)
	{
		final SmartBotData data = botData.get(botObjectId);
		final Player bot = spawnedBots.get(botObjectId);
		
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
			bot.getAI().setIntentionIdle();
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
	
	public boolean isBot(Player player)
	{
		return (player != null) && spawnedBots.containsKey(player.getObjectId());
	}
	
	public Player getSpawnedBot(int objectId)
	{
		return spawnedBots.get(objectId);
	}
	
	private void internalSpawn(Player bot, SmartBotData data)
	{
		bot.setHeading(data.getHeading());
		bot.spawnMe(data.getSpawnX(), data.getSpawnY(), data.getSpawnZ());
		
		bot.broadcastUserInfo();
		bot.updateAndBroadcastStatus();
		
		spawnedBots.put(bot.getObjectId(), bot);
		botData.put(bot.getObjectId(), data);
		
		final SmartBotController controller = new SmartBotController(bot, data);
		controllers.put(bot.getObjectId(), controller);
		controller.start();
	}
	
	private void saveBotState(Player bot)
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
	
	private void applyPreset(Player bot, SmartBotPreset preset)
	{
		final int level = preset.getLevel();
		
		bot.getStat().setLevel((byte) level);
		bot.getStat().setExp(bot.getStat().getExpForLevel(level));
		bot.getStat().setSp(0);
		
		bot.rewardSkills();
		
		for (int[] entry : preset.getItems())
		{
			final int itemId = entry[0];
			final int count = entry[1];
			
			bot.addItem(ItemProcessType.REWARD, itemId, count, null, false);
		}
		
		for (Item item : bot.getInventory().getItems())
		{
			if ((item != null) && item.isEquipable() && !item.isEquipped())
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
		bot.updateAndBroadcastStatus();
		
		bot.storeMe();
	}
	
	private void applyPresetBuffs(Player bot, SmartBotPreset preset)
	{
		for (int[] buff : preset.getBuffSkillIds())
		{
			try
			{
				final int skillId = buff[0];
				final int skillLevel = buff.length > 1 ? buff[1] : 1;
				
				final Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
				if (skill == null)
				{
					continue;
				}
				
				skill.activateSkill(bot, List.<WorldObject> of(bot));
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}
	}
	
	public Collection<Player> getSpawnedBots()
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
		final Player bot = spawnedBots.get(botObjectId);
		
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
			bot.getAI().setIntentionIdle();
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
		final Player bot = spawnedBots.get(botObjectId);
		final SmartBotData data = botData.get(botObjectId);
		
		if ((bot == null) || (data == null) || bot.isDead())
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
		bot.updateAndBroadcastStatus();
	}
	
	private void enablePresetShots(Player bot, SmartBotPreset preset)
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
	
	public Player getSpawnedBotByName(String name)
	{
		if (name == null)
		{
			return null;
		}
		
		for (Player bot : spawnedBots.values())
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
	
	public boolean requestBuff(int botObjectId, Player target)
	{
		final Player bot = spawnedBots.get(botObjectId);
		final SmartBotController controller = controllers.get(botObjectId);
		
		if ((bot == null) || (controller == null) || (target == null))
		{
			return false;
		}
		
		final SmartBotData data = botData.get(botObjectId);
		if (data == null)
		{
			return false;
		}
		
		final SmartBotPreset preset = SmartBotPreset.fromClassId(data.getTemplateClassId());
		if ((preset == null) || (preset.getRole() != SmartBotRole.BUFFER))
		{
			return false;
		}
		
		controller.requestBuff(target);
		return true;
	}
	
	public boolean isRole(Player bot, SmartBotRole role)
	{
		if ((bot == null) || (role == null))
		{
			return false;
		}
		
		final SmartBotData data = botData.get(bot.getObjectId());
		if (data == null)
		{
			return false;
		}
		
		final SmartBotPreset preset = SmartBotPreset.fromClassId(data.getTemplateClassId());
		return (preset != null) && (preset.getRole() == role);
	}
	
	public static SmartBotManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final SmartBotManager INSTANCE = new SmartBotManager();
	}
}
