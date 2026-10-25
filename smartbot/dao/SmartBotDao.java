package org.l2jmobius.gameserver.smartbot.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.smartbot.model.SmartBotData;

public class SmartBotDao {
    private static final String SELECT_ALL =
            "SELECT char_obj_id, bot_name, template_class_id, spawn_x, spawn_y, spawn_z, heading, " +
                    "follow_target_obj_id, melee_attack_range, active FROM smart_bots";

    private static final String SELECT_ONE =
            "SELECT char_obj_id, bot_name, template_class_id, spawn_x, spawn_y, spawn_z, heading, " +
                    "follow_target_obj_id, melee_attack_range, active FROM smart_bots WHERE char_obj_id=?";

    private static final String INSERT =
            "INSERT INTO smart_bots (char_obj_id, bot_name, template_class_id, spawn_x, spawn_y, spawn_z, heading, " +
                    "follow_target_obj_id, melee_attack_range, active, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE =
            "UPDATE smart_bots SET spawn_x=?, spawn_y=?, spawn_z=?, heading=?, follow_target_obj_id=?, " +
                    "melee_attack_range=?, active=?, updated_at=? WHERE char_obj_id=?";

    private static final String DELETE_BOT =
            "DELETE FROM smart_bots WHERE char_obj_id=?";

    public List<SmartBotData> loadAll() {
        final List<SmartBotData> result = new ArrayList<>();

        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return result;
    }

    public SmartBotData findByCharObjId(int charObjId) {
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_ONE)) {
            ps.setInt(1, charObjId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public void insert(SmartBotData data) {
        final long now = System.currentTimeMillis();

        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(INSERT)) {
            ps.setInt(1, data.getCharObjId());
            ps.setString(2, data.getBotName());
            ps.setInt(3, data.getTemplateClassId());
            ps.setInt(4, data.getSpawnX());
            ps.setInt(5, data.getSpawnY());
            ps.setInt(6, data.getSpawnZ());
            ps.setInt(7, data.getHeading());
            ps.setInt(8, data.getFollowTargetObjId());
            ps.setInt(9, data.getMeleeAttackRange());
            ps.setInt(10, data.isActive() ? 1 : 0);
            ps.setLong(11, now);
            ps.setLong(12, now);
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void update(SmartBotData data) {
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(UPDATE)) {
            ps.setInt(1, data.getSpawnX());
            ps.setInt(2, data.getSpawnY());
            ps.setInt(3, data.getSpawnZ());
            ps.setInt(4, data.getHeading());
            ps.setInt(5, data.getFollowTargetObjId());
            ps.setInt(6, data.getMeleeAttackRange());
            ps.setInt(7, data.isActive() ? 1 : 0);
            ps.setLong(8, System.currentTimeMillis());
            ps.setInt(9, data.getCharObjId());
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private SmartBotData map(ResultSet rs) throws SQLException
    {
        return new SmartBotData(
            rs.getInt("char_obj_id"),
            rs.getString("bot_name"),
            rs.getInt("template_class_id"),
            rs.getInt("spawn_x"),
            rs.getInt("spawn_y"),
            rs.getInt("spawn_z"),
            rs.getInt("heading"),
            rs.getInt("follow_target_obj_id"),
            rs.getInt("melee_attack_range"),
            rs.getInt("active") == 1
        );
    }

    public void delete(int charObjId) {
        try (Connection con = DatabaseFactory.getConnection();
             PreparedStatement ps = con.prepareStatement(DELETE_BOT)) {
            ps.setInt(1, charObjId);
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}