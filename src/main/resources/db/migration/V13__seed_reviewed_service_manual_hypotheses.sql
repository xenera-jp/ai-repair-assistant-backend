-- Cause hypotheses introduced by the reviewed FH1-AAC, FH1-SSB and HNC-120AA
-- service manuals. The diagnosis engine may rank only hypotheses registered
-- here; an LLM is never allowed to invent a replacement root cause.

INSERT INTO cause_hypothesis (
    problem_type_id,
    code,
    name_zh,
    name_ja,
    name_en,
    description,
    default_rank,
    supporting_signals_json,
    conflicting_signals_json,
    clarification_questions_json,
    action_boundary_json
)
SELECT
    pt.id,
    definition.cause_code,
    definition.name_zh,
    definition.name_ja,
    definition.name_en,
    definition.description,
    definition.default_rank,
    JSON_OBJECT(
        'evidenceLevel', 'SERVICE_MANUAL_REVIEWED',
        'signals', JSON_ARRAY()),
    JSON_OBJECT('signals', JSON_ARRAY()),
    JSON_ARRAY(),
    JSON_OBJECT(
        'evidenceClass', 'SERVICE_MANUAL_GUIDANCE',
        'requiresConfirmationBeforeReplacement', JSON_ARRAY(),
        'forbidden', JSON_ARRAY('REPLACE_WITHOUT_ONSITE_CONFIRMATION'))
FROM problem_type pt
JOIN (
    SELECT
        'TEMPERATURE_DISPLAY_FAULT' AS problem_code,
        'SENSOR_WIRING_OR_CONTROL_DISPLAY_FAILURE' AS cause_code,
        '传感器、配线或显示控制回路异常' AS name_zh,
        'センサ・配線・表示制御回路異常' AS name_ja,
        'Sensor, Wiring, or Display Control Failure' AS name_en,
        '温度传感器、线束连接或显示控制模块异常导致温度显示故障。' AS description,
        1 AS default_rank
    UNION ALL SELECT
        'DRAIN_OVERFLOW',
        'HUMIDITY_OR_WARM_PRODUCT_LOAD',
        '高湿环境或温热物品负荷',
        '高湿度環境・温かい食品負荷',
        'High Humidity or Warm Product Load',
        '高湿热源或大量温热未覆盖物品造成冷凝水量超过排水能力。',
        1
    UNION ALL SELECT
        'DRAIN_OVERFLOW',
        'DOOR_OR_EVAPORATOR_SEAL_LEAK',
        '门封或蒸发器周边密封不良',
        'ドア・蒸発器周辺シール不良',
        'Door or Evaporator Seal Leak',
        '门封或蒸发器周边漏气导致湿空气持续进入并产生过量冷凝水。',
        2
    UNION ALL SELECT
        'ABNORMAL_NOISE',
        'LOOSE_FASTENER_OR_MOUNT',
        '紧固件或安装支座松动',
        '締結部・取付部の緩み',
        'Loose Fastener or Mount',
        '紧固件、压缩机支座或减振件松动造成运行振动和异音。',
        1
    UNION ALL SELECT
        'ABNORMAL_NOISE',
        'FAN_MOTOR_OR_BLADE',
        '风扇电机或叶片异常',
        'ファンモータ・羽根異常',
        'Fan Motor or Blade Fault',
        '风扇叶片松动、干涉或风扇电机异常造成周期性噪音。',
        2
    UNION ALL SELECT
        'ABNORMAL_NOISE',
        'COMPRESSOR_OR_RELAY_NOISE',
        '压缩机安装、液击或继电器抖动',
        '圧縮機取付・液戻り・リレーチャタリング',
        'Compressor, Floodback, or Relay Noise',
        '压缩机安装不良、液击或继电器抖动造成机械或电气异音。',
        3
    UNION ALL SELECT
        'EQUIPMENT_NO_START_HNC',
        'POWER_SUPPLY_OR_GFCI',
        '供电、插座或漏电保护器异常',
        '電源・コンセント・漏電遮断器異常',
        'Power Supply, Outlet, or GFCI Fault',
        '漏电保护器关闭、插头脱落、插座无电或供电电压过低导致整机无法启动。',
        1
    UNION ALL SELECT
        'EQUIPMENT_NO_START_HNC',
        'OPEN_CIRCUIT_OR_BAD_CONTACT',
        '内部回路断路或接点不良',
        '内部回路断線・接点不良',
        'Open Circuit or Bad Contact',
        '内部配线开路或接点接触不良导致设备无法通电运行。',
        2
    UNION ALL SELECT
        'EQUIPMENT_NO_START_HNC',
        'FAN_MOTOR_PROTECTOR_TRIP',
        '风机电机保护器动作',
        'ファンモータ保護器作動',
        'Fan Motor Protector Trip',
        '通风不良或风机异常导致电机保护器动作并阻止设备启动。',
        3
    UNION ALL SELECT
        'COOLING_INSUFFICIENT_HNC',
        'CONDENSER_OR_FILTER_BLOCKAGE',
        '冷凝器、过滤网或进风口堵塞',
        '凝縮器・フィルタ・吸気口の目詰まり',
        'Condenser, Filter, or Inlet Blockage',
        '冷凝器、过滤网积尘或进风受阻导致散热能力下降。',
        1
    UNION ALL SELECT
        'COOLING_INSUFFICIENT_HNC',
        'FAN_OR_AIRFLOW_FAILURE',
        '风机或风路异常',
        'ファン・風路異常',
        'Fan or Airflow Failure',
        '风机停止、风量不足或内部风路受阻导致冷量无法有效循环。',
        2
    UNION ALL SELECT
        'COOLING_INSUFFICIENT_HNC',
        'REFRIGERANT_OR_ENVIRONMENT_CAPACITY_LOSS',
        '冷媒回路或环境负荷导致能力下降',
        '冷媒回路・環境負荷による能力低下',
        'Refrigerant or Environmental Capacity Loss',
        '冷媒泄漏、高环境温度、热源、频繁开门或过量装载造成制冷能力不足。',
        3
    UNION ALL SELECT
        'CABINET_DRYNESS_HNC',
        'EXCESSIVE_STORAGE_DURATION',
        '食品连续存放时间过长',
        '食品の連続保管時間超過',
        'Excessive Storage Duration',
        '食品从前一天持续存放或暴露时间过长导致表面水分流失。',
        1
    UNION ALL SELECT
        'ENVIRONMENTAL_FROST_HNC',
        'HIGH_AMBIENT_HUMIDITY',
        '环境相对湿度过高',
        '周囲相対湿度が高い',
        'High Ambient Humidity',
        '相对湿度超过手册建议范围时容易在展示柜外部形成结霜。',
        1
    UNION ALL SELECT
        'ENVIRONMENTAL_FROST_HNC',
        'FREQUENT_OR_LONG_DOOR_OPENING',
        '开门过于频繁或时间过长',
        '扉の頻繁・長時間開放',
        'Frequent or Long Door Opening',
        '频繁开门或长时间开门使湿空气进入并在柜内形成结霜。',
        2
) definition ON definition.problem_code = pt.code;
