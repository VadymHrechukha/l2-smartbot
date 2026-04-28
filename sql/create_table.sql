CREATE TABLE IF NOT EXISTS smart_bots (
    char_obj_id INT NOT NULL PRIMARY KEY,
    bot_name VARCHAR(35) NOT NULL,
    template_class_id INT NOT NULL,
    spawn_x INT NOT NULL,
    spawn_y INT NOT NULL,
    spawn_z INT NOT NULL,
    heading INT NOT NULL DEFAULT 0,
    follow_target_obj_id INT NOT NULL DEFAULT 0,
    melee_attack_range INT NOT NULL DEFAULT 40,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);