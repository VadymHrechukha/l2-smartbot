package net.sf.l2j.gameserver.handler.admincommandhandlers;

import net.sf.l2j.gameserver.smartbot.SmartBotManager;
import net.sf.l2j.gameserver.smartbot.model.SmartBotPreset;
import net.sf.l2j.gameserver.handler.IAdminCommandHandler;
import net.sf.l2j.gameserver.model.actor.instance.L2PcInstance;
import net.sf.l2j.gameserver.model.L2Object;

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
    public boolean useAdminCommand(String command, L2PcInstance activeChar)
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

            L2PcInstance bot = SmartBotManager.INSTANCE.createBotFromPreset(
                    name,
                    preset,
                    activeChar.getX() + 50,
                    activeChar.getY() + 50,
                    activeChar.getZ()
            );

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
            L2Object target = activeChar.getTarget();
            if (!(target instanceof L2PcInstance))
            {
                activeChar.sendMessage("Target a bot player first.");
                return false;
            }

            L2PcInstance bot = (L2PcInstance) target;
            if (!SmartBotManager.INSTANCE.isBot(bot.getObjectId()))
            {
                activeChar.sendMessage("Target is not a managed bot.");
                return false;
            }

            SmartBotManager.INSTANCE.setFollowTarget(bot.getObjectId(), activeChar.getObjectId());
            activeChar.sendMessage("Bot now follows you.");
            return true;
        }

        if (command.startsWith("admin_bot_despawn"))
        {
            L2Object target = activeChar.getTarget();
            if (!(target instanceof L2PcInstance))
            {
                activeChar.sendMessage("Target a bot player first.");
                return false;
            }

            L2PcInstance bot = (L2PcInstance) target;
            if (!SmartBotManager.INSTANCE.isBot(bot.getObjectId()))
            {
                activeChar.sendMessage("Target is not a managed bot.");
                return false;
            }

            SmartBotManager.INSTANCE.despawnBot(bot.getObjectId());
            activeChar.sendMessage("Bot despawned.");
            return true;
        }

        if (command.startsWith("admin_bot_delete"))
        {
            L2Object target = activeChar.getTarget();
            if (!(target instanceof L2PcInstance))
            {
                activeChar.sendMessage("Target a bot first.");
                return false;
            }

            L2PcInstance bot = (L2PcInstance) target;
            if (!SmartBotManager.INSTANCE.isBot(bot.getObjectId()))
            {
                activeChar.sendMessage("Target is not a bot.");
                return false;
            }

            SmartBotManager.INSTANCE.deleteBot(bot.getObjectId());
            activeChar.sendMessage("Bot deleted.");
            return true;
        }

        if (command.startsWith("admin_bot_unfollow"))
        {
            L2Object target = activeChar.getTarget();
            if (!(target instanceof L2PcInstance))
            {
                activeChar.sendMessage("Target a bot first.");
                return false;
            }

            L2PcInstance bot = (L2PcInstance) target;
            if (!SmartBotManager.INSTANCE.isBot(bot.getObjectId()))
            {
                activeChar.sendMessage("Target is not a bot.");
                return false;
            }

            SmartBotManager.INSTANCE.clearFollowTarget(bot.getObjectId());
            activeChar.sendMessage("Bot follow target cleared.");
            return true;
        }

        return false;
    }

    @Override
    public String[] getAdminCommandList()
    {
        return ADMIN_COMMANDS;
    }
}