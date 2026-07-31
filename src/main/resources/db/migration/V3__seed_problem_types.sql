-- AI Repair Assistant - Problem Types V1
-- Flyway migration: V3__seed_problem_types.sql

SET @taxonomy_id = (
    SELECT id
    FROM taxonomy_version
    WHERE version_code = 'MAINTENANCE_TAXONOMY_V1'
);

SET @domain_cooling = (
    SELECT id FROM problem_domain
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'COOLING_CAPACITY'
);
SET @domain_temperature = (
    SELECT id FROM problem_domain
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'TEMPERATURE_CONTROL'
);
SET @domain_defrost = (
    SELECT id FROM problem_domain
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'DEFROST_DRAINAGE'
);
SET @domain_compressor = (
    SELECT id FROM problem_domain
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'COMPRESSOR_POWER'
);
SET @domain_high_pressure = (
    SELECT id FROM problem_domain
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'CONDENSATION_HIGH_PRESSURE'
);
SET @domain_noise = (
    SELECT id FROM problem_domain
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'MECHANICAL_NOISE'
);

SET @strategy_cooling = (
    SELECT id FROM retrieval_strategy
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'COOLING_CAPACITY_V1'
);
SET @strategy_temperature = (
    SELECT id FROM retrieval_strategy
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'TEMPERATURE_CONTROL_V1'
);
SET @strategy_defrost = (
    SELECT id FROM retrieval_strategy
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'DEFROST_DRAINAGE_V1'
);
SET @strategy_compressor = (
    SELECT id FROM retrieval_strategy
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'COMPRESSOR_POWER_V1'
);
SET @strategy_high_pressure = (
    SELECT id FROM retrieval_strategy
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'CONDENSATION_HIGH_PRESSURE_V1'
);
SET @strategy_noise = (
    SELECT id FROM retrieval_strategy
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'MECHANICAL_NOISE_V1'
);

SET @models_refrigeration = JSON_ARRAY(
    'FH1-SSB',
    'FH1-AAC',
    'RIR1-SSB'
);
SET @models_hnc = JSON_ARRAY('HNC-120AA');

INSERT INTO problem_type (
    taxonomy_version_id,
    problem_domain_id,
    retrieval_strategy_id,
    code,
    name_zh,
    name_ja,
    name_en,
    description,
    source_labels_json,
    aliases_json,
    model_scopes_json,
    error_codes_json,
    clarification_schema_json,
    implementation_priority
) VALUES
    (
        @taxonomy_id,
        @domain_cooling,
        @strategy_cooling,
        'CABINET_HIGH_TEMP',
        '库内高温 / 冷却不足',
        '庫内高温・冷却不足',
        'Cabinet High Temperature',
        '库内温度高于设定值或制冷速度显著下降。',
        JSON_ARRAY('冷却不良(庫内高温)'),
        JSON_ARRAY('不冷', '冷却慢', '庫内が冷えない', '庫内温度が高い'),
        @models_refrigeration,
        JSON_ARRAY(
            JSON_OBJECT(
                'code', 'E1',
                'models', JSON_ARRAY('FH1-SSB', 'RIR1-SSB'),
                'strength', 'STRONG'
            )
        ),
        JSON_ARRAY(
            JSON_OBJECT('field', 'setTemperature', 'level', 'B', 'questionZh', '设定温度是多少？'),
            JSON_OBJECT('field', 'measuredTemperature', 'level', 'B', 'questionZh', '当前实测库温是多少？'),
            JSON_OBJECT('field', 'fanState', 'level', 'B', 'questionZh', '库内风机是否正常运转？'),
            JSON_OBJECT('field', 'doorSealState', 'level', 'C', 'questionZh', '门封是否存在破损或漏气？')
        ),
        'P0'
    ),
    (
        @taxonomy_id,
        @domain_cooling,
        @strategy_cooling,
        'COOLING_INSUFFICIENT_HNC',
        'HNC 冷却不足',
        'HNC 冷却不良',
        'HNC Insufficient Cooling',
        'HNC-120AA 制冷能力下降或无法达到目标温度。',
        JSON_ARRAY('冷却不良'),
        JSON_ARRAY('HNC 不冷', '冷えが悪い', '冷却能力低下'),
        @models_hnc,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'ambientTemperature', 'level', 'B', 'questionZh', '设备周围环境温度是多少？'),
            JSON_OBJECT('field', 'filterState', 'level', 'B', 'questionZh', '过滤网和冷凝器是否清洁？'),
            JSON_OBJECT('field', 'fanState', 'level', 'B', 'questionZh', '风机是否正常运转？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_cooling,
        @strategy_cooling,
        'REFRIGERANT_LEAK',
        '冷媒泄漏',
        '冷媒漏れ',
        'Refrigerant Leak',
        '制冷回路存在冷媒泄漏迹象。',
        JSON_ARRAY('冷媒漏れ'),
        JSON_ARRAY('冷媒不足', '油迹', 'ガス漏れ', '冷媒が抜ける'),
        @models_refrigeration,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'pressure', 'level', 'B', 'questionZh', '高低压侧压力分别是多少？'),
            JSON_OBJECT('field', 'oilTrace', 'level', 'B', 'questionZh', '配管或接头附近是否有油迹？'),
            JSON_OBJECT('field', 'recentRepair', 'level', 'C', 'questionZh', '近期是否维修或补充过冷媒？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_temperature,
        @strategy_temperature,
        'CABINET_TOO_COLD',
        '库内温度过低',
        '庫内温度が低すぎる',
        'Cabinet Too Cold',
        '实际库温持续低于设定值。',
        JSON_ARRAY('庫内温度が低すぎる'),
        JSON_ARRAY('过冷', '温度太低', '冷えすぎる', '庫内が凍る'),
        @models_refrigeration,
        JSON_ARRAY(
            JSON_OBJECT(
                'code', 'E2',
                'models', JSON_ARRAY('RIR1-SSB'),
                'strength', 'STRONG'
            )
        ),
        JSON_ARRAY(
            JSON_OBJECT('field', 'setTemperature', 'level', 'B', 'questionZh', '设定温度是多少？'),
            JSON_OBJECT('field', 'displayTemperature', 'level', 'B', 'questionZh', '面板显示温度是多少？'),
            JSON_OBJECT('field', 'measuredTemperature', 'level', 'B', 'questionZh', '独立温度计实测是多少？'),
            JSON_OBJECT('field', 'compressorContinuousRun', 'level', 'B', 'questionZh', '压缩机是否持续不停机？')
        ),
        'P0'
    ),
    (
        @taxonomy_id,
        @domain_temperature,
        @strategy_temperature,
        'TEMPERATURE_DISPLAY_FAULT',
        '温度显示异常',
        '温度表示不良',
        'Temperature Display Fault',
        '显示值跳动、异常或与实测温度明显不一致。',
        JSON_ARRAY('温度表示不良'),
        JSON_ARRAY('显示跳动', '温度不准', '表示がちらつく', '実測と表示が違う'),
        @models_refrigeration,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'displayTemperature', 'level', 'B', 'questionZh', '面板显示值如何变化？'),
            JSON_OBJECT('field', 'measuredTemperature', 'level', 'B', 'questionZh', '实测温度是多少？'),
            JSON_OBJECT('field', 'intermittent', 'level', 'C', 'questionZh', '异常是持续还是间歇发生？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_temperature,
        @strategy_temperature,
        'CABINET_DRYNESS_HNC',
        'HNC 库内干燥',
        'HNC 庫内乾燥',
        'HNC Cabinet Dryness',
        'HNC-120AA 内部湿度过低或存放物明显干燥。',
        JSON_ARRAY('庫内が乾く'),
        JSON_ARRAY('太干', '湿度低', '乾燥する', '庫内湿度が低い'),
        @models_hnc,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'usageDuration', 'level', 'B', 'questionZh', '物品连续存放了多长时间？'),
            JSON_OBJECT('field', 'humidity', 'level', 'B', 'questionZh', '库内或环境湿度是多少？'),
            JSON_OBJECT('field', 'doorOpenFrequency', 'level', 'C', 'questionZh', '门的开闭频率如何？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_defrost,
        @strategy_defrost,
        'DEFROST_FAILURE_FROST',
        '除霜不良 / 着霜',
        'デフロスト不良・着霜',
        'Defrost Failure and Frost',
        '除霜系统未能清除蒸发器或库内异常结霜。',
        JSON_ARRAY('デフロスト不良/着霜'),
        JSON_ARRAY('除霜失败', '蒸发器结霜', '霜が取れない', 'エバポレータ着霜'),
        @models_refrigeration,
        JSON_ARRAY(
            JSON_OBJECT(
                'code', 'E3',
                'models', JSON_ARRAY('FH1-SSB', 'RIR1-SSB'),
                'strength', 'STRONG'
            )
        ),
        JSON_ARRAY(
            JSON_OBJECT('field', 'frostLocation', 'level', 'B', 'questionZh', '结霜主要集中在哪个位置？'),
            JSON_OBJECT('field', 'frostPattern', 'level', 'B', 'questionZh', '结霜是均匀覆盖还是局部堆积？'),
            JSON_OBJECT('field', 'defrostCycle', 'level', 'B', 'questionZh', '最近一次除霜是否正常完成？'),
            JSON_OBJECT('field', 'heaterResistance', 'level', 'C', 'questionZh', '是否测量过除霜加热器阻值？')
        ),
        'P0'
    ),
    (
        @taxonomy_id,
        @domain_defrost,
        @strategy_defrost,
        'ENVIRONMENTAL_FROST_HNC',
        'HNC 环境性着霜',
        'HNC 環境性着霜',
        'HNC Environmental Frost',
        '由湿度、频繁开门或安装环境导致的 HNC 着霜。',
        JSON_ARRAY('着霜'),
        JSON_ARRAY('表面结霜', '环境结霜', '霜が付く', '湿気で着霜'),
        @models_hnc,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'humidity', 'level', 'B', 'questionZh', '现场湿度是多少？'),
            JSON_OBJECT('field', 'doorOpenFrequency', 'level', 'B', 'questionZh', '门是否频繁开启？'),
            JSON_OBJECT('field', 'installationLocation', 'level', 'B', 'questionZh', '设备是否靠近热源、风口或高湿区域？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_defrost,
        @strategy_defrost,
        'DRAIN_OVERFLOW',
        '排水溢出',
        'ドレン水あふれ',
        'Drain Overflow',
        '排水盘、排水口或设备下方出现积水和溢水。',
        JSON_ARRAY('ドレン水あふれ'),
        JSON_ARRAY('漏水', '排水不畅', '水があふれる', 'ドレン詰まり'),
        @models_refrigeration,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'leakLocation', 'level', 'B', 'questionZh', '积水或漏水发生在哪个位置？'),
            JSON_OBJECT('field', 'drainFlow', 'level', 'B', 'questionZh', '排水通道是否畅通？'),
            JSON_OBJECT('field', 'equipmentLevel', 'level', 'C', 'questionZh', '设备是否保持水平？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_compressor,
        @strategy_compressor,
        'COMPRESSOR_START_FAILURE',
        '压缩机启动不良',
        '圧縮機起動不良',
        'Compressor Start Failure',
        '压缩机没有启动或反复尝试启动但无法进入运行。',
        JSON_ARRAY('圧縮機起動不良'),
        JSON_ARRAY('压缩机不启动', '只有继电器声', 'コンプレッサーが起動しない', '起動を繰り返す'),
        @models_refrigeration,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'inputVoltage', 'level', 'B', 'questionZh', '压缩机端输入电压是多少？'),
            JSON_OBJECT('field', 'relaySound', 'level', 'B', 'questionZh', '启动时是否能听到继电器动作声？'),
            JSON_OBJECT('field', 'startCurrent', 'level', 'B', 'questionZh', '压缩机启动电流是多少？'),
            JSON_OBJECT('field', 'fanState', 'level', 'C', 'questionZh', '同期风机是否运行？')
        ),
        'P0'
    ),
    (
        @taxonomy_id,
        @domain_compressor,
        @strategy_compressor,
        'COMPRESSOR_OVERLOAD_TRIP',
        '压缩机过载跳闸',
        '圧縮機過負荷トリップ',
        'Compressor Overload Trip',
        '压缩机启动后停止或过载保护器反复动作。',
        JSON_ARRAY('圧縮機過負荷トリップ'),
        JSON_ARRAY('过载保护', '启动后停机', 'オーバーロード', '保護器が作動する'),
        @models_refrigeration,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'startCurrent', 'level', 'B', 'questionZh', '启动和运行电流分别是多少？'),
            JSON_OBJECT('field', 'restartInterval', 'level', 'B', 'questionZh', '停机后多久再次尝试启动？'),
            JSON_OBJECT('field', 'overheatState', 'level', 'B', 'questionZh', '压缩机外壳是否异常过热？'),
            JSON_OBJECT('field', 'condenserState', 'level', 'C', 'questionZh', '冷凝器和风路是否清洁通畅？')
        ),
        'P0'
    ),
    (
        @taxonomy_id,
        @domain_compressor,
        @strategy_compressor,
        'EQUIPMENT_NO_START_HNC',
        'HNC 整机无法启动',
        'HNC 起動しない',
        'HNC Equipment Does Not Start',
        'HNC-120AA 整机没有启动或没有任何运行反应。',
        JSON_ARRAY('起動しない'),
        JSON_ARRAY('整机没电', '没有反应', '電源が入らない', '全く動かない'),
        @models_hnc,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'inputVoltage', 'level', 'B', 'questionZh', '插座和设备输入端是否有正常电压？'),
            JSON_OBJECT('field', 'gfiState', 'level', 'B', 'questionZh', '漏电保护器是否动作？'),
            JSON_OBJECT('field', 'protectorState', 'level', 'B', 'questionZh', '设备保护器状态如何？')
        ),
        'P1'
    ),
    (
        @taxonomy_id,
        @domain_high_pressure,
        @strategy_high_pressure,
        'HIGH_PRESSURE_CONDENSATION',
        '高压异常 / 冷凝不良',
        '高圧異常・凝縮不良',
        'High Pressure and Condensation Fault',
        '冷凝散热不良、高压保护或冷媒充注异常。',
        JSON_ARRAY('高圧異常/凝縮不良'),
        JSON_ARRAY('高压报警', '背面过热', 'E4 点滅', '凝縮器が熱い'),
        @models_refrigeration,
        JSON_ARRAY(
            JSON_OBJECT(
                'code', 'E4',
                'models', JSON_ARRAY('FH1-SSB', 'RIR1-SSB'),
                'strength', 'STRONG'
            )
        ),
        JSON_ARRAY(
            JSON_OBJECT('field', 'ambientTemperature', 'level', 'B', 'questionZh', '现场环境温度是多少？'),
            JSON_OBJECT('field', 'condenserCleanliness', 'level', 'B', 'questionZh', '冷凝器和过滤网是否堵塞？'),
            JSON_OBJECT('field', 'airflow', 'level', 'B', 'questionZh', '设备背部和侧面的风路是否受阻？'),
            JSON_OBJECT('field', 'highSidePressure', 'level', 'C', 'questionZh', '高压侧压力是多少？')
        ),
        'P0'
    ),
    (
        @taxonomy_id,
        @domain_noise,
        @strategy_noise,
        'ABNORMAL_NOISE',
        '异音与异常振动',
        '異音・異常振動',
        'Abnormal Noise and Vibration',
        '运行中出现嗡鸣、敲击、摩擦或异常振动。',
        JSON_ARRAY('異音'),
        JSON_ARRAY('噪音大', '振动', 'カタカタ音', 'うなり音', '擦れる音'),
        @models_refrigeration,
        JSON_ARRAY(),
        JSON_ARRAY(
            JSON_OBJECT('field', 'soundLocation', 'level', 'B', 'questionZh', '声音主要来自哪个位置？'),
            JSON_OBJECT('field', 'soundTiming', 'level', 'B', 'questionZh', '声音在启动、运行还是停机时出现？'),
            JSON_OBJECT('field', 'soundCharacter', 'level', 'B', 'questionZh', '声音更接近嗡鸣、敲击还是摩擦？'),
            JSON_OBJECT('field', 'vibrationPoint', 'level', 'C', 'questionZh', '是否能确认明显振动点？')
        ),
        'P1'
    );
