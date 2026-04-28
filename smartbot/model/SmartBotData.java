package net.sf.l2j.gameserver.smartbot.model;

public class SmartBotData
{
    private final int charObjId;
    private final String botName;
    private final int templateClassId;

    private int spawnX;
    private int spawnY;
    private int spawnZ;
    private int heading;
    private int followTargetObjId;
    private int meleeAttackRange;
    private boolean active;
    private boolean attackMode;
    private int assistTargetObjId;

    public SmartBotData(
        int charObjId,
        String botName,
        int templateClassId,
        int spawnX,
        int spawnY,
        int spawnZ,
        int heading,
        int followTargetObjId,
        int meleeAttackRange,
        boolean active)
    {
        this.charObjId = charObjId;
        this.botName = botName;
        this.templateClassId = templateClassId;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.heading = heading;
        this.followTargetObjId = followTargetObjId;
        this.meleeAttackRange = meleeAttackRange;
        this.active = active;
    }

    public int getCharObjId() { return charObjId; }
    public String getBotName() { return botName; }
    public int getTemplateClassId() { return templateClassId; }

    public int getSpawnX() { return spawnX; }
    public void setSpawnX(int spawnX) { this.spawnX = spawnX; }

    public int getSpawnY() { return spawnY; }
    public void setSpawnY(int spawnY) { this.spawnY = spawnY; }

    public int getSpawnZ() { return spawnZ; }
    public void setSpawnZ(int spawnZ) { this.spawnZ = spawnZ; }

    public int getHeading() { return heading; }
    public void setHeading(int heading) { this.heading = heading; }

    public int getFollowTargetObjId() { return followTargetObjId; }
    public void setFollowTargetObjId(int followTargetObjId) { this.followTargetObjId = followTargetObjId; }

    public int getMeleeAttackRange() { return meleeAttackRange; }
    public void setMeleeAttackRange(int meleeAttackRange) { this.meleeAttackRange = meleeAttackRange; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public boolean isAttackMode() {return attackMode;}
    public void setAttackMode(boolean attackMode) { this.attackMode = attackMode; }

    public int getAssistTargetObjId() { return assistTargetObjId; }
    public void setAssistTargetObjId(int assistTargetObjId) { this.assistTargetObjId = assistTargetObjId; }
}