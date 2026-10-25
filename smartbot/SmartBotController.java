package org.l2jmobius.gameserver.smartbot;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.SmartBotConfig;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.item.EtcItem;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.smartbot.model.SmartBotData;
import org.l2jmobius.gameserver.smartbot.model.SmartBotPreset;
import org.l2jmobius.gameserver.smartbot.model.SmartBotRole;

public class SmartBotController implements Runnable {

    /*
     * C4-compatible common skill IDs.
     * The bot only uses skills it actually knows, so these are safe fallback candidates.
     */
    private static final int[] HEAL_SKILLS =
            {
                    1217, // Greater Heal
                    1218, // Greater Battle Heal
                    1015, // Battle Heal
                    1011  // Heal
            };

    private static final int[] RESURRECT_SKILLS =
            {
                    1016, // Resurrection
                    1254  // Mass Resurrection, if known/available
            };

    private static final int[] MAGE_ATTACK_SKILLS =
            {
                    1239, // Hurricane
                    1230, // Prominence
                    1235, // Hydro Blast
                    1220, // Blaze
                    1177  // Wind Strike
            };

    private static final int[] TANK_AGGRO_SKILLS =
            {
                    28, // Aggression
                    18  // Aura of Hate
            };

    private static final int[] DAGGER_ATTACK_SKILLS =
            {
                    263, // Deadly Blow
                    344  // Lethal Blow
            };

    private static final int[] ARCHER_STUN_SKILLS =
            {
                    101 // Stun Shot
            };

    private static final int[][] BUFFER_FIGHTER_BUFF_SKILLS =
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
                    {1389, 3}  // Greater Shield
            };

    private static final int[][] BUFFER_MAGE_BUFF_SKILLS =
            {
                    {1204, 2}, // Wind Walk
                    {1040, 3}, // Shield
                    {1035, 4}, // Mental Shield
                    {1085, 3}, // Acumen
                    {1059, 3}, // Empower
                    {1045, 6}, // Blessed Body
                    {1048, 6}, // Blessed Soul
                    {1389, 3}  // Greater Shield
            };

    private final Player bot;
    private final SmartBotData data;

    private ScheduledFuture<?> task;
    private long lastOverloadedSayAt = 0L;
    private long lastTankAggroAt = 0L;
    private long lastDaggerSkillAt = 0L;
    private long lastBuffCheckAt = 0L;
    private long lastArcherStunAt = 0L;
    private int requestedBuffTargetObjId = 0;

    private static final long BUFFER_RECHECK_INTERVAL_MS = 3000L;
    private static final long DAGGER_SKILL_COOLDOWN_MS = 2500L;
    private static final long TANK_AGGRO_COOLDOWN_MS = 3500L;


    public SmartBotController(Player bot, SmartBotData data) {
        this.bot = bot;
        this.data = data;
    }

    public void start() {
        if (task == null) {
            task = ThreadPool.scheduleAtFixedRate(this, 1000L, 1000L);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    @Override
    public void run()
    {
        if (bot == null || bot.isDead() || bot.isTeleporting())
        {
            return;
        }

        try
        {
            useManaPotionIfNeeded();

            final SmartBotRole role = getRole();

            if (role == SmartBotRole.BUFFER)
            {
                final Player buffTarget = getRequestedBuffTarget();
                if (buffTarget != null)
                {
                    handleBufferBehavior(buffTarget);
                    return;
                }
            }

            if (role == SmartBotRole.BUFFER)
            {
                final Player buffTarget = findBuffTarget();
                if (buffTarget != null)
                {
                    handleBufferBehavior(buffTarget);
                    return;
                }
            }

            /*
             * FOLLOW mode has priority.
             * This must stay before assist/attack/resurrection logic,
             * otherwise old combat/support state can block movement.
             */
            final Player followTarget = getFollowTarget();
            if (followTarget != null)
            {
                if (role == SmartBotRole.HEALER)
                {
                    final Player healTarget = findHealTarget();
                    if (healTarget != null)
                    {
                        handleHealerBehavior(healTarget);
                        return;
                    }
                }

                handleFollow(followTarget);
                return;
            }

            /*
             * If the follow target is dead, getFollowTarget() returns null.
             * Only healer should try resurrection here.
             */
            if (role == SmartBotRole.HEALER)
            {
                final Player deadFollowTarget = getFollowTargetAllowDead();
                if (deadFollowTarget != null && deadFollowTarget.isDead())
                {
                    handleResurrectionBehavior(deadFollowTarget);

                    if (bot.isCastingNow())
                    {
                        return;
                    }
                }
            }

            /*
             * ASSIST mode.
             */
            final Player assistTarget = getAssistTarget();
            if (assistTarget != null)
            {
                if (role == SmartBotRole.HEALER)
                {
                    final Player healTarget = findHealTarget();
                    if (healTarget != null)
                    {
                        handleHealerBehavior(healTarget);
                        return;
                    }

                    handleFollow(assistTarget);
                    return;
                }

                if (role == SmartBotRole.BUFFER)
                {
                    final Player buffTarget = findBuffTarget();
                    if (buffTarget != null)
                    {
                        handleBufferBehavior(buffTarget);
                        return;
                    }

                    handleFollow(assistTarget);
                    return;
                }

                final Monster assistedMob = getAssistMob(assistTarget);
                if (assistedMob != null)
                {
                    handleCombat(assistedMob);
                    return;
                }

                handleFollow(assistTarget);
                return;
            }

            /*
             * If assist target is dead, healer may resurrect it.
             */
            if (role == SmartBotRole.HEALER)
            {
                final Player deadAssistTarget = getAssistTargetAllowDead();
                if (deadAssistTarget != null && deadAssistTarget.isDead())
                {
                    handleResurrectionBehavior(deadAssistTarget);

                    if (bot.isCastingNow())
                    {
                        return;
                    }
                }
            }

            /*
             * Free attack mode.
             */
            if (data.isAttackMode())
            {
                final Monster mob = getCombatTargetOrFindNew(SmartBotConfig.BOT_ATTACK_SEARCH_RADIUS);
                if (mob != null)
                {
                    handleCombat(mob);
                    return;
                }
            }

            /*
             * Passive healer party support only when not following/assisting/attacking.
             */
            if (role == SmartBotRole.HEALER)
            {
                final Player healTarget = findHealTarget();
                if (healTarget != null)
                {
                    handleHealerBehavior(healTarget);
                    return;
                }

                final Player resurrectionTarget = findResurrectionTarget();
                if (resurrectionTarget != null)
                {
                    handleResurrectionBehavior(resurrectionTarget);

                    if (bot.isCastingNow())
                    {
                        return;
                    }
                }
            }

            idleBot();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void handleCombat(Monster mob) {
        final SmartBotRole role = getRole();
        final SmartBotPreset preset = getPreset();

        if (role == SmartBotRole.BUFFER)
        {
            final Player buffTarget = findBuffTarget();
            if (buffTarget != null)
            {
                handleBufferBehavior(buffTarget);
                return;
            }

            idleBot();
            return;
        }

        if (role == SmartBotRole.MAGE) {
            handleMageBehavior(mob);
            return;
        }

        if (role == SmartBotRole.HEALER) {
            final Player healTarget = findHealTarget();
            if (healTarget != null)
            {
                handleHealerBehavior(healTarget);
                return;
            }

            idleBot();
            return;
        }

        if (preset == SmartBotPreset.TANK)
        {
            handleTankBehavior(mob);
            return;
        }

        if (preset == SmartBotPreset.DAGGER)
        {
            handleDaggerBehavior(mob);
            return;
        }

        handleFighterBehavior(mob);
    }

    private void handleFighterBehavior(Monster mob) {
        if (mob == null || mob.isDead()) {
            bot.setTarget(null);
            idleBot();
            return;
        }

        bot.setTarget(mob);

        if (tryArcherStun(mob))
        {
            return;
        }

        if (!bot.isInsideRadius2D(mob, data.getMeleeAttackRange() + 20)) {
            bot.getAI().setIntentionAttack(mob);
            return;
        }

        if (!bot.isAttackingNow() && !bot.isCastingNow()) {
            bot.getAI().setIntentionAttack(mob);
        }
    }

    private void handleMageBehavior(Monster mob) {
        if (mob == null || mob.isDead()) {
            bot.setTarget(null);
            idleBot();
            return;
        }

        bot.setTarget(mob);

        if (bot.isCastingNow() || bot.isAttackingNow()) {
            return;
        }

        final Skill nuke = getFirstKnownSkill(MAGE_ATTACK_SKILLS);
        if (nuke != null) {
            bot.useMagic(nuke, false, false);
            return;
        }

        handleFighterBehavior(mob);
    }

    private void handleHealerBehavior(Player healTarget)
    {
        if (healTarget == null || healTarget.isDead())
        {
            idleBot();
            return;
        }

        bot.setTarget(healTarget);

        if (!bot.isInsideRadius2D(healTarget, SmartBotConfig.BOT_HEAL_RANGE))
        {
            bot.getAI().setIntentionFollow(healTarget);
            return;
        }

        if (bot.isCastingNow())
        {
            return;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        final Skill heal = getFirstKnownSkill(HEAL_SKILLS);
        if (heal != null)
        {
            bot.useMagic(heal, false, false);
            return;
        }

        handleFollow(healTarget);
    }

    private Player findHealTarget()
    {
        Player best = null;
        double bestHpPercent = 100.0;

        final Player assistTarget = getAssistTarget();
        if (isValidHealCandidate(assistTarget))
        {
            best = assistTarget;
            bestHpPercent = getHpPercent(assistTarget);
        }

        final Player followTarget = getFollowTarget();
        if (isValidHealCandidate(followTarget))
        {
            final double hpPercent = getHpPercent(followTarget);
            if (hpPercent < bestHpPercent)
            {
                best = followTarget;
                bestHpPercent = hpPercent;
            }
        }

        if (bot.isInParty())
        {
            final Party party = bot.getParty();

            for (Player member : party.getMembers())
            {
                if (!isValidHealCandidate(member))
                {
                    continue;
                }

                final double hpPercent = getHpPercent(member);
                if (hpPercent < bestHpPercent)
                {
                    bestHpPercent = hpPercent;
                    best = member;
                }
            }
        }

        return best;
    }

    private boolean isValidHealCandidate(Player player)
    {
        if (player == null || player.isDead() || player.isTeleporting())
        {
            return false;
        }

        if (player == bot)
        {
            return getHpPercent(bot) <= SmartBotConfig.BOT_HEAL_HP_PERCENT;
        }

        if (!bot.isInParty())
        {
            final Player followTarget = getFollowTarget();
            final Player assistTarget = getAssistTarget();

            if (player != followTarget && player != assistTarget)
            {
                return false;
            }
        }
        else if (player.getParty() != bot.getParty())
        {
            return false;
        }

        return getHpPercent(player) <= SmartBotConfig.BOT_HEAL_HP_PERCENT;
    }

    private double getHpPercent(Player player) {
        if (player == null || player.getMaxHp() <= 0) {
            return 100.0;
        }

        return (player.getCurrentHp() * 100.0) / player.getMaxHp();
    }

    private Skill getFirstKnownSkill(int[] skillIds) {
        for (int skillId : skillIds) {
            final int level = bot.getSkillLevel(skillId);
            if (level <= 0) {
                continue;
            }

            final Skill skill = SkillData.getInstance().getSkill(skillId, level);
            if (skill != null) {
                return skill;
            }
        }

        return null;
    }

    private void handleTankBehavior(Monster mob)
    {
        if (mob == null || mob.isDead())
        {
            bot.setTarget(null);
            idleBot();
            return;
        }

        bot.setTarget(mob);

        if (bot.isCastingNow())
        {
            return;
        }

        /*
         * Try to take aggro before normal attacking.
         */
        if (tryUseTankAggro(mob))
        {
            return;
        }

        handleFighterBehavior(mob);
    }

    private boolean tryUseTankAggro(Monster mob)
    {
        if (mob == null || mob.isDead())
        {
            return false;
        }

        final long now = System.currentTimeMillis();
        if ((now - lastTankAggroAt) < TANK_AGGRO_COOLDOWN_MS)
        {
            return false;
        }

        final Skill aggro = getFirstKnownSkill(TANK_AGGRO_SKILLS);
        if (aggro == null)
        {
            return false;
        }

        int castRange = aggro.getCastRange();
        if (castRange <= 0)
        {
            castRange = 400;
        }

        if (!bot.isInsideRadius2D(mob, castRange + 50))
        {
            bot.getAI().setIntentionAttack(mob);
            return false;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        bot.setTarget(mob);
        bot.useMagic(aggro, false, false);
        lastTankAggroAt = now;
        return true;
    }

    private void handleDaggerBehavior(Monster mob)
    {
        if (mob == null || mob.isDead())
        {
            bot.setTarget(null);
            idleBot();
            return;
        }

        bot.setTarget(mob);

        if (bot.isCastingNow())
        {
            return;
        }

        if (tryUseDaggerSkill(mob))
        {
            return;
        }

        handleFighterBehavior(mob);
    }

    private boolean tryUseDaggerSkill(Monster mob)
    {
        if (mob == null || mob.isDead())
        {
            return false;
        }

        final long now = System.currentTimeMillis();
        if ((now - lastDaggerSkillAt) < DAGGER_SKILL_COOLDOWN_MS)
        {
            return false;
        }

        final Skill daggerSkill = getFirstKnownSkill(DAGGER_ATTACK_SKILLS);
        if (daggerSkill == null)
        {
            return false;
        }

        int castRange = daggerSkill.getCastRange();
        if (castRange <= 0)
        {
            castRange = data.getMeleeAttackRange() + 20;
        }

        if (!bot.isInsideRadius2D(mob, castRange + 30))
        {
            bot.getAI().setIntentionAttack(mob);
            return false;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        bot.setTarget(mob);
        bot.useMagic(daggerSkill, false, false);
        lastDaggerSkillAt = now;
        return true;
    }

    private void handleBufferBehavior(Player target)
    {
        if (target == null || target.isDead() || target.isTeleporting())
        {
            idleBot();
            return;
        }

        bot.setTarget(target);

        if (!bot.isInsideRadius2D(target, SmartBotConfig.BOT_HEAL_RANGE))
        {
            bot.getAI().setIntentionFollow(target);
            return;
        }

        if (bot.isCastingNow())
        {
            return;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        final Skill missingBuff = getMissingBuffFor(target);
        if (missingBuff != null)
        {
            bot.useMagic(missingBuff, false, false);
            return;
        }

        requestedBuffTargetObjId = 0;
        handleFollow(target);
    }

    private Player findBuffTarget()
    {
        final long now = System.currentTimeMillis();
        if ((now - lastBuffCheckAt) < BUFFER_RECHECK_INTERVAL_MS)
        {
            return null;
        }

        lastBuffCheckAt = now;

        if (!bot.isInParty())
        {
            final Player followTarget = getFollowTarget();
            if (needsBuffs(followTarget))
            {
                return followTarget;
            }

            final Player assistTarget = getAssistTarget();
            if (needsBuffs(assistTarget))
            {
                return assistTarget;
            }

            return null;
        }

        final Party party = bot.getParty();
        if (party == null)
        {
            return null;
        }

        for (Player member : party.getMembers())
        {
            if (needsBuffs(member))
            {
                return member;
            }
        }

        return null;
    }

    private boolean needsBuffs(Player player)
    {
        if (player == null || player.isDead() || player.isTeleporting())
        {
            return false;
        }

        if (player == bot)
        {
            return getMissingBuffFor(player) != null;
        }

        if (bot.isInParty())
        {
            if (player.getParty() != bot.getParty())
            {
                return false;
            }
        }
        else
        {
            final Player followTarget = getFollowTarget();
            final Player assistTarget = getAssistTarget();

            if (player != followTarget && player != assistTarget)
            {
                return false;
            }
        }

        return getMissingBuffFor(player) != null;
    }

    private Skill getMissingBuffFor(Player target)
    {
        if (target == null)
        {
            return null;
        }

        final int[][] buffList = isMageBuffTarget(target)
                ? BUFFER_MAGE_BUFF_SKILLS
                : BUFFER_FIGHTER_BUFF_SKILLS;

        for (int[] buff : buffList)
        {
            final int skillId = buff[0];
            final int skillLevel = buff.length > 1 ? buff[1] : 1;

            if (hasEffect(target, skillId))
            {
                continue;
            }

            final int knownLevel = bot.getSkillLevel(skillId);
            if (knownLevel <= 0)
            {
                continue;
            }

            final int castLevel = Math.min(skillLevel, knownLevel);
            final Skill skill = SkillData.getInstance().getSkill(skillId, castLevel);
            if (skill != null)
            {
                return skill;
            }
        }

        return null;
    }

    private boolean isMageBuffTarget(Player target)
    {
        if (target == null)
        {
            return false;
        }

        /*
         * If the target is one of our SmartBots, use its preset role.
         */
        final SmartBotPreset preset = SmartBotPreset.fromClassId(target.getPlayerClass().getId());
        if (preset != null)
        {
            final SmartBotRole role = preset.getRole();
            return role == SmartBotRole.MAGE
                    || role == SmartBotRole.HEALER
                    || role == SmartBotRole.BUFFER;
        }

        /*
         * Fallback for real players
         *
         * This is intentionally conservative:
         * unknown classes get fighter buffs.
         */
        final int classId = target.getPlayerClass().getId();

        return switch (classId) {
            // Human mystics / wizards / clerics / bishop / prophet / necro / warlock
            // Elven mystics / spell singers / elders / elemental summoners
            // Dark elven mystics / spellhowlers / shillien elders / phantom summoners
            // Orc mystics / overlord / warcryer / dominator / doomcryer
            case 10, 11, 12, 13, 14, 15, 16, 17, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 25, 26, 27, 28, 29, 30,
                 100, 101, 102, 38, 39, 40, 41, 42, 43, 103, 104, 105, 49, 50, 51, 52, 115, 116 -> true;
            default -> false;
        };
    }

    private boolean hasEffect(Player player, int skillId)
    {
        if (player == null)
        {
            return false;
        }

        return player.isAffectedBySkill(skillId);
    }

    private boolean tryArcherStun(Monster mob)
    {
        final SmartBotPreset preset = getPreset();
        if (preset != SmartBotPreset.ARCHER)
        {
            return false;
        }

        if (mob == null || mob.isDead())
        {
            return false;
        }

        if (bot.isCastingNow() || bot.isAttackingNow())
        {
            return false;
        }

        final long now = System.currentTimeMillis();
        if ((now - lastArcherStunAt) < 8000L)
        {
            return false;
        }

        final Skill stun = getFirstKnownSkill(ARCHER_STUN_SKILLS);
        if (stun == null)
        {
            return false;
        }

        bot.setTarget(mob);
        bot.useMagic(stun, false, false);
        lastArcherStunAt = now;
        return true;
    }

    private Player getFollowTarget() {
        if (data.getFollowTargetObjId() <= 0) {
            return null;
        }

        final Player target = World.getPlayer(data.getFollowTargetObjId());
        if (target == null || target.isDead() || target.isTeleporting()) {
            return null;
        }

        return target;
    }

    private Player getAssistTarget() {
        if (data.getAssistTargetObjId() <= 0) {
            return null;
        }

        final Player target = World.getPlayer(data.getAssistTargetObjId());
        if (target == null || target.isDead() || target.isTeleporting()) {
            return null;
        }

        return target;
    }

    private void handleFollow(Player target)
    {
        if (target == null || target.isDead() || target.isTeleporting())
        {
            idleBot();
            return;
        }

        if (bot.isOverloaded())
        {
            final long now = System.currentTimeMillis();
            if ((now - lastOverloadedSayAt) >= 10000L)
            {
                say("i'm overloaded, can't move. Reduce my weight!");
                lastOverloadedSayAt = now;
            }
            return;
        }

        bot.setTarget(target);

        if (!bot.isRunning())
        {
            bot.setRunning();
        }

        if (bot.isCastingNow())
        {
            return;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        if (!bot.isInsideRadius2D(target, SmartBotConfig.BOT_FOLLOW_DISTANCE))
        {
            final Location loc = getFollowLocationNearTarget(target, SmartBotConfig.BOT_FOLLOW_DISTANCE);
            bot.getAI().setIntentionMoveTo(loc);
            return;
        }

        if (!bot.isMoving())
        {
            bot.getAI().setIntentionIdle();
        }
    }

    private Monster getAssistMob(Player assistTarget) {
        if (assistTarget == null) {
            return null;
        }

        if (!(assistTarget.getTarget() instanceof Monster)) {
            return null;
        }

        final Monster mob = (Monster) assistTarget.getTarget();
        if (mob.isDead()) {
            return null;
        }

        if (!bot.isInsideRadius2D(mob, SmartBotConfig.BOT_ATTACK_SEARCH_RADIUS)) {
            return null;
        }

        return mob;
    }

    private Monster getCombatTargetOrFindNew(int radius) {
        final WorldObject currentTarget = bot.getTarget();
        if (currentTarget instanceof Monster) {
            final Monster currentMob = (Monster) currentTarget;
            if (!currentMob.isDead() && bot.isInsideRadius2D(currentMob, radius)) {
                return currentMob;
            }
        }

        return findNearestAttackableMob(radius);
    }

    private Monster findNearestAttackableMob(int radius) {
        Monster best = null;
        double bestDistance = Double.MAX_VALUE;

        final List<Monster> visible = World.getVisibleObjectsInRange(bot, Monster.class, radius);

        for (Monster mob : visible) {
            if (mob.isDead()) {
                continue;
            }

            final double dist = bot.calculateDistance2D(mob);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = mob;
            }
        }

        return best;
    }

    private void idleBot() {
        if (bot.isAttackingNow() || bot.isCastingNow()) {
            return;
        }

        bot.setTarget(null);
        bot.getAI().setIntentionIdle();
    }

    private SmartBotPreset getPreset() {
        return SmartBotPreset.fromClassId(data.getTemplateClassId());
    }

    private SmartBotRole getRole() {
        final SmartBotPreset preset = getPreset();
        return preset != null ? preset.getRole() : SmartBotRole.FIGHTER;
    }

    private void useManaPotionIfNeeded() {
        if (SmartBotConfig.BOT_MANA_POTION_ITEM_ID <= 0) {
            return;
        }

        if (bot.getMaxMp() <= 0) {
            return;
        }

        final double mpPercent = (bot.getCurrentMp() * 100.0) / bot.getMaxMp();
        if (mpPercent > SmartBotConfig.BOT_MANA_POTION_MP_PERCENT) {
            return;
        }

        final Item item = bot.getInventory().getItemByItemId(SmartBotConfig.BOT_MANA_POTION_ITEM_ID);
        if (item == null || item.getCount() <= 0) {
            return;
        }

        if (!(item.getTemplate() instanceof EtcItem)) {
            return;
        }

        final IItemHandler handler = ItemHandler.getInstance().getHandler((EtcItem) item.getTemplate());
        if (handler == null) {
            return;
        }

        handler.onItemUse(bot, item, false);
    }

    private void handleResurrectionBehavior(Player target)
    {
        if (target == null || !target.isDead() || target.isTeleporting())
        {
            return;
        }

        if (target.isReviveRequested())
        {
            return;
        }

        /*
         * Do not try to AI-follow dead players.
         * Some cores don't handle following dead Player targets correctly.
         * If the corpse is not already in range, let normal follow/assist logic run.
         */
        if (!bot.isInsideRadius2D(target, SmartBotConfig.BOT_HEAL_RANGE))
        {
            return;
        }

        if (bot.isCastingNow())
        {
            return;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        bot.setTarget(target);

        final Skill resurrection = getFirstKnownSkill(RESURRECT_SKILLS);
        if (resurrection != null)
        {
            bot.useMagic(resurrection, false, false);
        }
    }

    private Player findResurrectionTarget()
    {
        final Player assistTarget = getAssistTargetAllowDead();
        if (needsResurrection(assistTarget))
        {
            return assistTarget;
        }

        final Player followTarget = getFollowTargetAllowDead();
        if (needsResurrection(followTarget))
        {
            return followTarget;
        }

        if (bot.isInParty())
        {
            final Party party = bot.getParty();

            for (Player member : party.getMembers())
            {
                if (needsResurrection(member))
                {
                    return member;
                }
            }
        }

        return null;
    }

    private boolean needsResurrection(Player player)
    {
        if (player == null || player == bot)
        {
            return false;
        }

        if (!player.isDead() || player.isTeleporting())
        {
            return false;
        }

        if (player.isReviveRequested())
        {
            return false;
        }

        if (bot.isInParty())
        {
            if (player.getParty() != bot.getParty())
            {
                return false;
            }
        }
        else
        {
            if (player != getFollowTargetAllowDead() && player != getAssistTargetAllowDead())
            {
                return false;
            }
        }

        return bot.isInsideRadius2D(player, SmartBotConfig.BOT_HEAL_RANGE);
    }

    private Player getFollowTargetAllowDead()
    {
        if (data.getFollowTargetObjId() <= 0)
        {
            return null;
        }

        final Player target = World.getPlayer(data.getFollowTargetObjId());
        if (target == null || target.isTeleporting())
        {
            return null;
        }

        return target;
    }

    private Player getAssistTargetAllowDead()
    {
        if (data.getAssistTargetObjId() <= 0)
        {
            return null;
        }

        final Player target = World.getPlayer(data.getAssistTargetObjId());
        if (target == null || target.isTeleporting())
        {
            return null;
        }

        return target;
    }

    private Location getFollowLocationNearTarget(Player target, int followDistance)
    {
        final double dx = bot.getX() - target.getX();
        final double dy = bot.getY() - target.getY();

        final double distance = Math.sqrt((dx * dx) + (dy * dy));

        if (distance <= 1.0)
        {
            return new Location(target.getX(), target.getY(), target.getZ());
        }

        final double ratio = followDistance / distance;

        final int x = target.getX() + (int) (dx * ratio);
        final int y = target.getY() + (int) (dy * ratio);
        final int z = target.getZ();

        return new Location(x, y, z);
    }

    private void say(String text)
    {
        if (bot == null || text == null || text.trim().isEmpty())
        {
            return;
        }

        final CreatureSay cs = new CreatureSay(bot, ChatType.GENERAL, bot.getName(), text);

        bot.broadcastPacket(cs);
        bot.sendPacket(cs);
    }

    public void requestBuff(Player target)
    {
        if (target == null)
        {
            return;
        }

        requestedBuffTargetObjId = target.getObjectId();
    }

    private Player getRequestedBuffTarget()
    {
        if (requestedBuffTargetObjId <= 0)
        {
            return null;
        }

        final Player target = World.getPlayer(requestedBuffTargetObjId);
        if (target == null || target.isDead() || target.isTeleporting())
        {
            requestedBuffTargetObjId = 0;
            return null;
        }

        return target;
    }
}
