package net.sf.l2j.gameserver.smartbot;

import java.util.Collection;
import java.util.concurrent.ScheduledFuture;

import net.sf.l2j.Config;
import net.sf.l2j.gameserver.ThreadPoolManager;
import net.sf.l2j.gameserver.ai.CtrlIntention;
import net.sf.l2j.gameserver.datatables.SkillTable;
import net.sf.l2j.gameserver.handler.IItemHandler;
import net.sf.l2j.gameserver.handler.ItemHandler;
import net.sf.l2j.gameserver.model.*;
import net.sf.l2j.gameserver.model.actor.instance.L2MonsterInstance;
import net.sf.l2j.gameserver.model.actor.instance.L2PcInstance;
import net.sf.l2j.gameserver.network.clientpackets.Say2;
import net.sf.l2j.gameserver.network.serverpackets.CreatureSay;
import net.sf.l2j.gameserver.smartbot.model.SmartBotData;
import net.sf.l2j.gameserver.smartbot.model.SmartBotPreset;
import net.sf.l2j.gameserver.smartbot.model.SmartBotRole;
import net.sf.l2j.gameserver.templates.L2EtcItem;

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

    private final L2PcInstance bot;
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


    public SmartBotController(L2PcInstance bot, SmartBotData data) {
        this.bot = bot;
        this.data = data;
    }

    public void start() {
        if (task == null) {
            task = ThreadPoolManager.getInstance().scheduleGeneralAtFixedRate(this, 1000L, 1000L);
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
                final L2PcInstance buffTarget = getRequestedBuffTarget();
                if (buffTarget != null)
                {
                    handleBufferBehavior(buffTarget);
                    return;
                }
            }

            if (role == SmartBotRole.BUFFER)
            {
                final L2PcInstance buffTarget = findBuffTarget();
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
            final L2PcInstance followTarget = getFollowTarget();
            if (followTarget != null)
            {
                if (role == SmartBotRole.HEALER)
                {
                    final L2PcInstance healTarget = findHealTarget();
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
                final L2PcInstance deadFollowTarget = getFollowTargetAllowDead();
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
            final L2PcInstance assistTarget = getAssistTarget();
            if (assistTarget != null)
            {
                if (role == SmartBotRole.HEALER)
                {
                    final L2PcInstance healTarget = findHealTarget();
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
                    final L2PcInstance buffTarget = findBuffTarget();
                    if (buffTarget != null)
                    {
                        handleBufferBehavior(buffTarget);
                        return;
                    }

                    handleFollow(assistTarget);
                    return;
                }

                final L2MonsterInstance assistedMob = getAssistMob(assistTarget);
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
                final L2PcInstance deadAssistTarget = getAssistTargetAllowDead();
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
                final L2MonsterInstance mob = getCombatTargetOrFindNew(Config.BOT_ATTACK_SEARCH_RADIUS);
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
                final L2PcInstance healTarget = findHealTarget();
                if (healTarget != null)
                {
                    handleHealerBehavior(healTarget);
                    return;
                }

                final L2PcInstance resurrectionTarget = findResurrectionTarget();
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

    private void handleCombat(L2MonsterInstance mob) {
        final SmartBotRole role = getRole();
        final SmartBotPreset preset = getPreset();

        if (role == SmartBotRole.BUFFER)
        {
            final L2PcInstance buffTarget = findBuffTarget();
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
            final L2PcInstance healTarget = findHealTarget();
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

    private void handleFighterBehavior(L2MonsterInstance mob) {
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

        if (!bot.isInsideRadius(mob, data.getMeleeAttackRange() + 20, true, false)) {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_ATTACK, mob);
            return;
        }

        if (!bot.isAttackingNow() && !bot.isCastingNow()) {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_ATTACK, mob);
        }
    }

    private void handleMageBehavior(L2MonsterInstance mob) {
        if (mob == null || mob.isDead()) {
            bot.setTarget(null);
            idleBot();
            return;
        }

        bot.setTarget(mob);

        if (bot.isCastingNow() || bot.isAttackingNow()) {
            return;
        }

        final L2Skill nuke = getFirstKnownSkill(MAGE_ATTACK_SKILLS);
        if (nuke != null) {
            bot.useMagic(nuke, false, false, 0);
            return;
        }

        handleFighterBehavior(mob);
    }

    private void handleHealerBehavior(L2PcInstance healTarget)
    {
        if (healTarget == null || healTarget.isDead())
        {
            idleBot();
            return;
        }

        bot.setTarget(healTarget);

        if (!bot.isInsideRadius(healTarget, Config.BOT_HEAL_RANGE, true, false))
        {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_FOLLOW, healTarget);
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

        final L2Skill heal = getFirstKnownSkill(HEAL_SKILLS);
        if (heal != null)
        {
            bot.useMagic(heal, false, false, 0);
            return;
        }

        handleFollow(healTarget);
    }

    private L2PcInstance findHealTarget()
    {
        L2PcInstance best = null;
        double bestHpPercent = 100.0;

        final L2PcInstance assistTarget = getAssistTarget();
        if (isValidHealCandidate(assistTarget))
        {
            best = assistTarget;
            bestHpPercent = getHpPercent(assistTarget);
        }

        final L2PcInstance followTarget = getFollowTarget();
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
            final L2Party party = bot.getParty();

            for (L2PcInstance member : party.getPartyMembers())
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

    private boolean isValidHealCandidate(L2PcInstance player)
    {
        if (player == null || player.isDead() || player.isTeleporting())
        {
            return false;
        }

        if (player == bot)
        {
            return getHpPercent(bot) <= Config.BOT_HEAL_HP_PERCENT;
        }

        if (!bot.isInParty())
        {
            final L2PcInstance followTarget = getFollowTarget();
            final L2PcInstance assistTarget = getAssistTarget();

            if (player != followTarget && player != assistTarget)
            {
                return false;
            }
        }
        else if (player.getParty() != bot.getParty())
        {
            return false;
        }

        return getHpPercent(player) <= Config.BOT_HEAL_HP_PERCENT;
    }

    private double getHpPercent(L2PcInstance player) {
        if (player == null || player.getMaxHp() <= 0) {
            return 100.0;
        }

        return (player.getCurrentHp() * 100.0) / player.getMaxHp();
    }

    private L2Skill getFirstKnownSkill(int[] skillIds) {
        for (int skillId : skillIds) {
            final int level = bot.getSkillLevel(skillId);
            if (level <= 0) {
                continue;
            }

            final L2Skill skill = SkillTable.getInstance().getInfo(skillId, level);
            if (skill != null) {
                return skill;
            }
        }

        return null;
    }

    private void handleTankBehavior(L2MonsterInstance mob)
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

    private boolean tryUseTankAggro(L2MonsterInstance mob)
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

        final L2Skill aggro = getFirstKnownSkill(TANK_AGGRO_SKILLS);
        if (aggro == null)
        {
            return false;
        }

        int castRange = aggro.getCastRange();
        if (castRange <= 0)
        {
            castRange = 400;
        }

        if (!bot.isInsideRadius(mob, castRange + 50, true, false))
        {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_ATTACK, mob);
            return false;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        bot.setTarget(mob);
        bot.useMagic(aggro, false, false, 0);
        lastTankAggroAt = now;
        return true;
    }

    private void handleDaggerBehavior(L2MonsterInstance mob)
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

    private boolean tryUseDaggerSkill(L2MonsterInstance mob)
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

        final L2Skill daggerSkill = getFirstKnownSkill(DAGGER_ATTACK_SKILLS);
        if (daggerSkill == null)
        {
            return false;
        }

        int castRange = daggerSkill.getCastRange();
        if (castRange <= 0)
        {
            castRange = data.getMeleeAttackRange() + 20;
        }

        if (!bot.isInsideRadius(mob, castRange + 30, true, false))
        {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_ATTACK, mob);
            return false;
        }

        if (bot.isAttackingNow())
        {
            bot.abortAttack();
        }

        bot.setTarget(mob);
        bot.useMagic(daggerSkill, false, false, 0);
        lastDaggerSkillAt = now;
        return true;
    }

    private void handleBufferBehavior(L2PcInstance target)
    {
        if (target == null || target.isDead() || target.isTeleporting())
        {
            idleBot();
            return;
        }

        bot.setTarget(target);

        if (!bot.isInsideRadius(target, Config.BOT_HEAL_RANGE, true, false))
        {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_FOLLOW, target);
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

        final L2Skill missingBuff = getMissingBuffFor(target);
        if (missingBuff != null)
        {
            bot.useMagic(missingBuff, false, false, 0);
            return;
        }

        requestedBuffTargetObjId = 0;
        handleFollow(target);
    }

    private L2PcInstance findBuffTarget()
    {
        final long now = System.currentTimeMillis();
        if ((now - lastBuffCheckAt) < BUFFER_RECHECK_INTERVAL_MS)
        {
            return null;
        }

        lastBuffCheckAt = now;

        if (!bot.isInParty())
        {
            final L2PcInstance followTarget = getFollowTarget();
            if (needsBuffs(followTarget))
            {
                return followTarget;
            }

            final L2PcInstance assistTarget = getAssistTarget();
            if (needsBuffs(assistTarget))
            {
                return assistTarget;
            }

            return null;
        }

        final L2Party party = bot.getParty();
        if (party == null)
        {
            return null;
        }

        for (L2PcInstance member : party.getPartyMembers())
        {
            if (needsBuffs(member))
            {
                return member;
            }
        }

        return null;
    }

    private boolean needsBuffs(L2PcInstance player)
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
            final L2PcInstance followTarget = getFollowTarget();
            final L2PcInstance assistTarget = getAssistTarget();

            if (player != followTarget && player != assistTarget)
            {
                return false;
            }
        }

        return getMissingBuffFor(player) != null;
    }

    private L2Skill getMissingBuffFor(L2PcInstance target)
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
            final L2Skill skill = SkillTable.getInstance().getInfo(skillId, castLevel);
            if (skill != null)
            {
                return skill;
            }
        }

        return null;
    }

    private boolean isMageBuffTarget(L2PcInstance target)
    {
        if (target == null)
        {
            return false;
        }

        /*
         * If the target is one of our SmartBots, use its preset role.
         */
        final SmartBotPreset preset = SmartBotPreset.fromClassId(target.getClassId().getId());
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
        final int classId = target.getClassId().getId();

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

    private boolean hasEffect(L2PcInstance player, int skillId)
    {
        if (player == null)
        {
            return false;
        }

        if (player.getFirstEffect(skillId) != null)
        {
            return true;
        }

        return false;
    }

    private boolean tryArcherStun(L2MonsterInstance mob)
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

        final L2Skill stun = getFirstKnownSkill(ARCHER_STUN_SKILLS);
        if (stun == null)
        {
            return false;
        }

        bot.setTarget(mob);
        bot.useMagic(stun, false, false, 0);
        lastArcherStunAt = now;
        return true;
    }

    private L2PcInstance getFollowTarget() {
        if (data.getFollowTargetObjId() <= 0) {
            return null;
        }

        final L2PcInstance target = L2World.getInstance().getPlayer(data.getFollowTargetObjId());
        if (target == null || target.isDead() || target.isTeleporting()) {
            return null;
        }

        return target;
    }

    private L2PcInstance getAssistTarget() {
        if (data.getAssistTargetObjId() <= 0) {
            return null;
        }

        final L2PcInstance target = L2World.getInstance().getPlayer(data.getAssistTargetObjId());
        if (target == null || target.isDead() || target.isTeleporting()) {
            return null;
        }

        return target;
    }

    private void handleFollow(L2PcInstance target)
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

        if (!bot.isInsideRadius(target, Config.BOT_FOLLOW_DISTANCE, true, false))
        {
            final Location loc = getFollowLocationNearTarget(target, Config.BOT_FOLLOW_DISTANCE);
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_MOVE_TO, loc);
            return;
        }

        if (!bot.isMoving())
        {
            bot.getAI().setIntention(CtrlIntention.AI_INTENTION_IDLE);
        }
    }

    private L2MonsterInstance getAssistMob(L2PcInstance assistTarget) {
        if (assistTarget == null) {
            return null;
        }

        if (!(assistTarget.getTarget() instanceof L2MonsterInstance)) {
            return null;
        }

        final L2MonsterInstance mob = (L2MonsterInstance) assistTarget.getTarget();
        if (mob.isDead()) {
            return null;
        }

        if (!bot.isInsideRadius(mob, Config.BOT_ATTACK_SEARCH_RADIUS, true, false)) {
            return null;
        }

        return mob;
    }

    private L2MonsterInstance getCombatTargetOrFindNew(int radius) {
        final L2Object currentTarget = bot.getTarget();
        if (currentTarget instanceof L2MonsterInstance) {
            final L2MonsterInstance currentMob = (L2MonsterInstance) currentTarget;
            if (!currentMob.isDead() && bot.isInsideRadius(currentMob, radius, true, false)) {
                return currentMob;
            }
        }

        return findNearestAttackableMob(radius);
    }

    private L2MonsterInstance findNearestAttackableMob(int radius) {
        L2MonsterInstance best = null;
        double bestDistance = Double.MAX_VALUE;
        final double maxDistSq = radius * radius;

        final Collection<?> visible = L2World.getInstance().getVisibleObjects(bot, radius);

        for (Object obj : visible) {
            if (!(obj instanceof L2MonsterInstance)) {
                continue;
            }

            final L2MonsterInstance mob = (L2MonsterInstance) obj;
            if (mob.isDead()) {
                continue;
            }

            final double dist = bot.getDistanceSq(mob);
            if (dist > maxDistSq) {
                continue;
            }

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
        bot.getAI().setIntention(CtrlIntention.AI_INTENTION_IDLE);
    }

    private SmartBotPreset getPreset() {
        return SmartBotPreset.fromClassId(data.getTemplateClassId());
    }

    private SmartBotRole getRole() {
        final SmartBotPreset preset = getPreset();
        return preset != null ? preset.getRole() : SmartBotRole.FIGHTER;
    }

    private void useManaPotionIfNeeded() {
        if (Config.BOT_MANA_POTION_ITEM_ID <= 0) {
            return;
        }

        if (bot.getMaxMp() <= 0) {
            return;
        }

        final double mpPercent = (bot.getCurrentMp() * 100.0) / bot.getMaxMp();
        if (mpPercent > Config.BOT_MANA_POTION_MP_PERCENT) {
            return;
        }

        final L2ItemInstance item = bot.getInventory().getItemByItemId(Config.BOT_MANA_POTION_ITEM_ID);
        if (item == null || item.getCount() <= 0) {
            return;
        }

        if (!(item.getItem() instanceof L2EtcItem)) {
            return;
        }

        final IItemHandler handler = ItemHandler.getInstance().getHandler((L2EtcItem) item.getItem());
        if (handler == null) {
            return;
        }

        handler.useItem(bot, item);
    }

    private void handleResurrectionBehavior(L2PcInstance target)
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
         * Some C4 cores don't handle following dead L2PcInstance targets correctly.
         * If the corpse is not already in range, let normal follow/assist logic run.
         */
        if (!bot.isInsideRadius(target, Config.BOT_HEAL_RANGE, true, false))
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

        final L2Skill resurrection = getFirstKnownSkill(RESURRECT_SKILLS);
        if (resurrection != null)
        {
            bot.useMagic(resurrection, false, false, 0);
        }
    }

    private L2PcInstance findResurrectionTarget()
    {
        final L2PcInstance assistTarget = getAssistTargetAllowDead();
        if (needsResurrection(assistTarget))
        {
            return assistTarget;
        }

        final L2PcInstance followTarget = getFollowTargetAllowDead();
        if (needsResurrection(followTarget))
        {
            return followTarget;
        }

        if (bot.isInParty())
        {
            final L2Party party = bot.getParty();

            for (L2PcInstance member : party.getPartyMembers())
            {
                if (needsResurrection(member))
                {
                    return member;
                }
            }
        }

        return null;
    }

    private boolean needsResurrection(L2PcInstance player)
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

        return bot.isInsideRadius(player, Config.BOT_HEAL_RANGE, true, false);
    }

    private L2PcInstance getFollowTargetAllowDead()
    {
        if (data.getFollowTargetObjId() <= 0)
        {
            return null;
        }

        final L2PcInstance target = L2World.getInstance().getPlayer(data.getFollowTargetObjId());
        if (target == null || target.isTeleporting())
        {
            return null;
        }

        return target;
    }

    private L2PcInstance getAssistTargetAllowDead()
    {
        if (data.getAssistTargetObjId() <= 0)
        {
            return null;
        }

        final L2PcInstance target = L2World.getInstance().getPlayer(data.getAssistTargetObjId());
        if (target == null || target.isTeleporting())
        {
            return null;
        }

        return target;
    }

    private Location getFollowLocationNearTarget(L2PcInstance target, int followDistance)
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

        final CreatureSay cs = new CreatureSay(bot.getObjectId(), Say2.ALL, bot.getName(), text);

        for (L2PcInstance player : bot.getKnownList().getKnownPlayers().values())
        {
            if (player != null && bot.isInsideRadius(player, 1250, false, true))
            {
                player.sendPacket(cs);
            }
        }

        bot.sendPacket(cs);
    }

    public void requestBuff(L2PcInstance target)
    {
        if (target == null)
        {
            return;
        }

        requestedBuffTargetObjId = target.getObjectId();
    }

    private L2PcInstance getRequestedBuffTarget()
    {
        if (requestedBuffTargetObjId <= 0)
        {
            return null;
        }

        final L2PcInstance target = L2World.getInstance().getPlayer(requestedBuffTargetObjId);
        if (target == null || target.isDead() || target.isTeleporting())
        {
            requestedBuffTargetObjId = 0;
            return null;
        }

        return target;
    }
}