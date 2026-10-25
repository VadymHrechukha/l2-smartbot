package org.l2jmobius.gameserver.handler;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.smartbot.SmartBotManager;
import org.l2jmobius.gameserver.smartbot.model.SmartBotPreset;

public class AdminSmartbot implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_bot_create",
		"admin_bot_follow",
		"admin_bot_despawn",
		"admin_bot_delete",
		"admin_bot_unfollow"
	};
	
	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (command.startsWith("admin_bot_create"))
		{
			String[] parts = command.trim().split("\\s+");
			if (parts.length < 3)
			{
				activeChar.sendMessage("Usage: //bot_create <name> <preset>");
				activeChar.sendMessage("Presets: MAGE, ARCHER, DAGGER, TANK, HEALER, BUFFER");
				return false;
			}
			
			String name = parts[1];
			
			SmartBotPreset preset;
			try
			{
				preset = SmartBotPreset.fromName(parts[2]);
			}
			catch (Exception e)
			{
				activeChar.sendMessage("Invalid preset: " + parts[2]);
				activeChar.sendMessage("Available presets: MAGE, ARCHER, DAGGER, TANK, HEALER, BUFFER");
				return false;
			}
			
			Player bot = SmartBotManager.getInstance().createBotFromPreset(name, preset, activeChar.getX() + 50, activeChar.getY() + 50, activeChar.getZ());
			
			if (bot == null)
			{
				activeChar.sendMessage("Bot creation failed.");
				return false;
			}
			
			activeChar.sendMessage("Created bot: " + bot.getName() + " (" + bot.getObjectId() + ") as " + preset.name());
			return true;
		}
		
		if (command.startsWith("admin_bot_follow"))
		{
			// target the bot first, then: //bot_follow
			WorldObject target = activeChar.getTarget();
			if (!(target instanceof Player))
			{
				activeChar.sendMessage("Target a bot player first.");
				return false;
			}
			
			Player bot = (Player) target;
			if (!SmartBotManager.getInstance().isBot(bot.getObjectId()))
			{
				activeChar.sendMessage("Target is not a managed bot.");
				return false;
			}
			
			SmartBotManager.getInstance().setFollowTarget(bot.getObjectId(), activeChar.getObjectId());
			activeChar.sendMessage("Bot now follows you.");
			return true;
		}
		
		if (command.startsWith("admin_bot_despawn"))
		{
			WorldObject target = activeChar.getTarget();
			if (!(target instanceof Player))
			{
				activeChar.sendMessage("Target a bot player first.");
				return false;
			}
			
			Player bot = (Player) target;
			if (!SmartBotManager.getInstance().isBot(bot.getObjectId()))
			{
				activeChar.sendMessage("Target is not a managed bot.");
				return false;
			}
			
			SmartBotManager.getInstance().despawnBot(bot.getObjectId());
			activeChar.sendMessage("Bot despawned.");
			return true;
		}
		
		if (command.startsWith("admin_bot_delete"))
		{
			WorldObject target = activeChar.getTarget();
			if (!(target instanceof Player))
			{
				activeChar.sendMessage("Target a bot first.");
				return false;
			}
			
			Player bot = (Player) target;
			if (!SmartBotManager.getInstance().isBot(bot.getObjectId()))
			{
				activeChar.sendMessage("Target is not a bot.");
				return false;
			}
			
			SmartBotManager.getInstance().deleteBot(bot.getObjectId());
			activeChar.sendMessage("Bot deleted.");
			return true;
		}
		
		if (command.startsWith("admin_bot_unfollow"))
		{
			WorldObject target = activeChar.getTarget();
			if (!(target instanceof Player))
			{
				activeChar.sendMessage("Target a bot first.");
				return false;
			}
			
			Player bot = (Player) target;
			if (!SmartBotManager.getInstance().isBot(bot.getObjectId()))
			{
				activeChar.sendMessage("Target is not a bot.");
				return false;
			}
			
			SmartBotManager.getInstance().clearFollowTarget(bot.getObjectId());
			activeChar.sendMessage("Bot follow target cleared.");
			return true;
		}
		
		return false;
	}
	
	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
