package org.l2jmobius.gameserver.smartbot.model;

import org.l2jmobius.gameserver.config.custom.SmartBotConfig;

public enum SmartBotPreset {
    ARCHER(
            92,
            78,
            SmartBotRole.FIGHTER,
            new int[][]
                    {
                            {7577, 1},
                            {6382, 1},
                            {6379, 1},
                            {6380, 1},
                            {6381, 1},
                            {6387, 1},
                            {6388, 2},
                            {6389, 2},
                            {1467, 5000},
                            {1345, 5000},
                            {SmartBotConfig.BOT_MANA_POTION_ITEM_ID, SmartBotConfig.BOT_MANA_POTION_COUNT}
                    },
            Buffs.FIGHTER_FULL_BUFFS
    ),

    MAGE(
            94,
            78,
            SmartBotRole.MAGE,
            new int[][]
                    {
                            {6608, 1},
                            {6386, 1},
                            {6383, 1},
                            {6384, 1},
                            {6385, 1},
                            {6387, 1},
                            {6388, 2},
                            {6389, 2},
                            {3952, 5000},
                            {SmartBotConfig.BOT_MANA_POTION_ITEM_ID, SmartBotConfig.BOT_MANA_POTION_COUNT}
                    },
            Buffs.MAGE_FULL_BUFFS
    ),

    HEALER(
            97,
            78,
            SmartBotRole.HEALER,
            new int[][]
                    {
                            {6608, 1},
                            {6386, 1},
                            {6383, 1},
                            {6384, 1},
                            {6385, 1},
                            {6387, 1},
                            {6388, 2},
                            {6389, 2},
                            {3952, 5000},
                            {SmartBotConfig.BOT_MANA_POTION_ITEM_ID, SmartBotConfig.BOT_MANA_POTION_COUNT}
                    },
            Buffs.MAGE_FULL_BUFFS
    ),

    BUFFER(
            98,
            78,
            SmartBotRole.BUFFER,
            new int[][]
                    {
                            {6608, 1},
                            {6386, 1},
                            {6383, 1},
                            {6384, 1},
                            {6385, 1},
                            {6387, 1},
                            {6388, 2},
                            {6389, 2},
                            {3952, 5000},
                            {SmartBotConfig.BOT_MANA_POTION_ITEM_ID, SmartBotConfig.BOT_MANA_POTION_COUNT}
                    },
            Buffs.MAGE_FULL_BUFFS
    ),

    DAGGER(
            108,
            78,
            SmartBotRole.FIGHTER,
            new int[][]
                    {
                            {6367, 1},
                            {6382, 1},
                            {6379, 1},
                            {6380, 1},
                            {6381, 1},
                            {6387, 1},
                            {6388, 2},
                            {6389, 2},
                            {1467, 5000},
                            {1345, 5000},
                            {SmartBotConfig.BOT_MANA_POTION_ITEM_ID, SmartBotConfig.BOT_MANA_POTION_COUNT}
                    },
            Buffs.FIGHTER_FULL_BUFFS
    ),

    TANK(
            99,
            78,
            SmartBotRole.FIGHTER,
            new int[][]
                    {
                            {6364, 1}, // Forgotten Blade - one-handed sword
                            {6377, 1}, // Imperial Crusader Shield
                            {6378, 1},
                            {6373, 1},
                            {6374, 1},
                            {6375, 1},
                            {6376, 1},
                            {6387, 1},
                            {6388, 2},
                            {6389, 2},
                            {1467, 5000},
                            {1345, 5000},
                            {SmartBotConfig.BOT_MANA_POTION_ITEM_ID, SmartBotConfig.BOT_MANA_POTION_COUNT}
                    },
            Buffs.FIGHTER_FULL_BUFFS
    );

    private final int classId;
    private final int level;
    private final SmartBotRole role;
    private final int[][] items;
    private final int[][] buffSkillIds;

    SmartBotPreset(int classId, int level, SmartBotRole role, int[][] items, int[][] buffSkillIds)
    {
        this.classId = classId;
        this.level = level;
        this.role = role;
        this.items = items;
        this.buffSkillIds = buffSkillIds;
    }

    public int getClassId()
    {
        return classId;
    }

    public int getLevel()
    {
        return level;
    }

    public SmartBotRole getRole()
    {
        return role;
    }

    public int[][] getItems()
    {
        return items;
    }

    public int[][] getBuffSkillIds()
    {
        return buffSkillIds;
    }

    public static SmartBotPreset fromName(String name)
    {
        return valueOf(name.toUpperCase());
    }

    public static SmartBotPreset fromClassId(int classId)
    {
        for (SmartBotPreset preset : values())
        {
            if (preset.classId == classId)
            {
                return preset;
            }
        }
        return null;
    }

    private static final class Buffs {
        private static final int[][] FIGHTER_FULL_BUFFS =
                {
                        {1204, 2}, // Wind Walk
                        {1040, 3}, // Shield
                        {1035, 4}, // Mental Shield
                        {1068, 3}, // Might
                        {1086, 2}, // Haste
                        {1077, 3}, // Focus
                        {1240, 3}, // Guidance
                        {1242, 3}, // Death Whisper
                        {1268, 4}, // Vampiric Rage
                        {1388, 3}, // Greater Might
                        {1389, 3}, // Greater Shield

                        // Songs
                        {264, 1}, // Song of Earth
                        {267, 1}, // Song of Warding
                        {268, 1}, // Song of Wind
                        {269, 1}, // Song of Hunter
                        {270, 1}, // Song of Invocation
                        {304, 1}, // Song of Vitality
                        {305, 1}, // Song of Vengeance

                        // Dances
                        {271, 1}, // Dance of Warrior
                        {272, 1}, // Dance of Inspiration
                        {274, 1}, // Dance of Fire
                        {275, 1}, // Dance of Fury
                        {310, 1}  // Dance of Vampire
                };

        private static final int[][] MAGE_FULL_BUFFS =
                {
                        {1204, 2}, // Wind Walk
                        {1040, 3}, // Shield
                        {1035, 4}, // Mental Shield
                        {1085, 3}, // Acumen
                        {1059, 3}, // Empower
                        {1045, 6}, // Blessed Body
                        {1048, 6}, // Blessed Soul
                        {1389, 3}, // Greater Shield

                        // Songs
                        {264, 1}, // Song of Earth
                        {267, 1}, // Song of Warding
                        {268, 1}, // Song of Wind
                        {270, 1}, // Song of Invocation
                        {304, 1}, // Song of Vitality
                        {306, 1}, // Song of Flame Guard
                        {308, 1}, // Song of Storm Guard

                        // Dances
                        {273, 1}, // Dance of Mystic
                        {276, 1}, // Dance of Concentration
                        {277, 1}, // Dance of Light
                        {307, 1}  // Dance of Aqua Guard
                };
    }
}