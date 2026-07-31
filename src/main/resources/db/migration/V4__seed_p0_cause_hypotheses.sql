-- AI Repair Assistant - P0 Cause Hypotheses V1
-- Flyway migration: V4__seed_p0_cause_hypotheses.sql
--
-- These hypotheses are constrained diagnosis directions derived from repair
-- history. They are not calibrated probabilities or authoritative manual claims.

SET @taxonomy_id = (
    SELECT id
    FROM taxonomy_version
    WHERE version_code = 'MAINTENANCE_TAXONOMY_V1'
);

SET @pt_cabinet_high_temp = (
    SELECT id FROM problem_type
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'CABINET_HIGH_TEMP'
);
SET @pt_cabinet_too_cold = (
    SELECT id FROM problem_type
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'CABINET_TOO_COLD'
);
SET @pt_defrost_failure = (
    SELECT id FROM problem_type
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'DEFROST_FAILURE_FROST'
);
SET @pt_compressor_start = (
    SELECT id FROM problem_type
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'COMPRESSOR_START_FAILURE'
);
SET @pt_compressor_overload = (
    SELECT id FROM problem_type
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'COMPRESSOR_OVERLOAD_TRIP'
);
SET @pt_high_pressure = (
    SELECT id FROM problem_type
    WHERE taxonomy_version_id = @taxonomy_id
      AND code = 'HIGH_PRESSURE_CONDENSATION'
);

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
) VALUES
(
    @pt_cabinet_high_temp,
    'SETTING_AIRFLOW_OR_DOOR_SEAL',
    '设置、风路或门封异常',
    '設定・風路・ドアシール異常',
    'Setting, Airflow, or Door Seal Issue',
    '设定、风路受阻或门封漏气导致冷量损失。',
    1,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT(
            'exactIncidents', 25,
            'finalResolved', 24,
            'noPartFinalResolved', 10,
            'doorGasketFinalUse', 5
        ),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'doorSealState', 'operator', 'IN', 'value', JSON_ARRAY('DAMAGED', 'LEAKING'), 'strength', 'HIGH'),
            JSON_OBJECT('field', 'fanState', 'operator', 'IN', 'value', JSON_ARRAY('STOPPED', 'WEAK'), 'strength', 'HIGH'),
            JSON_OBJECT('field', 'airflowState', 'operator', 'EQ', 'value', 'BLOCKED', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'setTemperature', 'operator', 'OUT_OF_EXPECTED_RANGE', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'doorSealState', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 10),
            JSON_OBJECT('field', 'fanState', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 10),
            JSON_OBJECT('field', 'airflowState', 'operator', 'EQ', 'value', 'CLEAR', 'effect', 'PENALIZE', 'penalty', 10)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'setTemperature', 'level', 'B', 'questionZh', '设定温度是多少？'),
        JSON_OBJECT('field', 'doorSealState', 'level', 'B', 'questionZh', '门封是否破损或漏气？'),
        JSON_OBJECT('field', 'fanState', 'level', 'B', 'questionZh', '库内风机和风路是否正常？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('CHECK_SETTING', 'INSPECT_DOOR_SEAL', 'INSPECT_FAN_AND_AIRFLOW'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('DOOR_GASKET', 'EVAPORATOR_FAN'),
        'forbidden', JSON_ARRAY('CLAIM_CALIBRATED_PROBABILITY', 'CLAIM_OFFICIAL_REPLACEMENT')
    )
),
(
    @pt_cabinet_high_temp,
    'REFRIGERANT_CIRCUIT_CAPACITY_LOSS',
    '冷媒回路能力下降',
    '冷媒回路能力低下',
    'Refrigerant Circuit Capacity Loss',
    '冷媒泄漏、充注异常或回路问题造成制冷能力下降。',
    2,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('drierFinalUse', 5),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'oilTrace', 'operator', 'EQ', 'value', 'PRESENT', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'pressure', 'operator', 'ABNORMAL', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'coolingSpeed', 'operator', 'EQ', 'value', 'SLOW', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'pressure', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 25),
            JSON_OBJECT('field', 'oilTrace', 'operator', 'EQ', 'value', 'ABSENT', 'effect', 'PENALIZE', 'penalty', 10)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'pressure', 'level', 'B', 'questionZh', '高低压侧压力是多少？'),
        JSON_OBJECT('field', 'oilTrace', 'level', 'B', 'questionZh', '配管或接头附近是否有油迹？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_PRESSURE', 'LEAK_INSPECTION', 'PREPARE_CIRCUIT_SERVICE_PARTS'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('DRIER', 'REFRIGERANT_CIRCUIT_PARTS'),
        'forbidden', JSON_ARRAY('CHARGE_REFRIGERANT_WITHOUT_MEASUREMENT', 'CLAIM_OFFICIAL_REPLACEMENT')
    )
),
(
    @pt_cabinet_high_temp,
    'SENSOR_FAN_OR_CONTROL_FAILURE',
    '传感器、风机或控制异常',
    'センサ・ファン・制御異常',
    'Sensor, Fan, or Control Failure',
    '温度检测、蒸发器风机或控制回路异常造成冷却不足。',
    3,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT(
            'controlBoardFinalUse', 4,
            'cabinetThermistorFinalUse', 2,
            'evaporatorFanFinalUse', 1
        ),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'displayMeasuredDelta', 'operator', 'GT', 'value', 2.0, 'unit', 'C', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'fanState', 'operator', 'IN', 'value', JSON_ARRAY('STOPPED', 'INTERMITTENT'), 'strength', 'HIGH'),
            JSON_OBJECT('field', 'sensorReading', 'operator', 'UNSTABLE', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'fanState', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 10),
            JSON_OBJECT('field', 'displayMeasuredDelta', 'operator', 'LTE', 'value', 1.0, 'unit', 'C', 'effect', 'PENALIZE', 'penalty', 15)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'displayTemperature', 'level', 'B', 'questionZh', '面板显示温度是多少？'),
        JSON_OBJECT('field', 'measuredTemperature', 'level', 'B', 'questionZh', '独立温度计实测是多少？'),
        JSON_OBJECT('field', 'fanState', 'level', 'B', 'questionZh', '蒸发器风机是否持续运转？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('COMPARE_TEMPERATURE', 'CHECK_SENSOR_READING', 'CHECK_FAN_OUTPUT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('THERMISTOR', 'EVAPORATOR_FAN', 'CONTROL_BOARD'),
        'forbidden', JSON_ARRAY('REPLACE_CONTROL_BOARD_WITHOUT_INPUT_OUTPUT_CHECK')
    )
),
(
    @pt_cabinet_too_cold,
    'CABINET_THERMISTOR_FAILURE',
    '库内热敏电阻异常',
    '庫内サーミスタ異常',
    'Cabinet Thermistor Failure',
    '库内温度检测偏移或失效，导致持续制冷。',
    1,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT('exactIncidents', 21, 'thermistorFinalUse', 9),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'displayMeasuredDelta', 'operator', 'GT', 'value', 2.0, 'unit', 'C', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'sensorReading', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'errorCode', 'operator', 'EQ', 'value', 'E2', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'displayMeasuredDelta', 'operator', 'LTE', 'value', 1.0, 'unit', 'C', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'sensorReading', 'operator', 'IN_SPEC', 'effect', 'EXCLUDE')
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'displayTemperature', 'level', 'B', 'questionZh', '显示温度是多少？'),
        JSON_OBJECT('field', 'measuredTemperature', 'level', 'B', 'questionZh', '独立实测温度是多少？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('COMPARE_TEMPERATURE', 'MEASURE_THERMISTOR'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('CABINET_THERMISTOR'),
        'forbidden', JSON_ARRAY('REPLACE_WITHOUT_SENSOR_MEASUREMENT')
    )
),
(
    @pt_cabinet_too_cold,
    'COMPRESSOR_RELAY_CONTACT_STUCK',
    '压缩机继电器触点粘连',
    '圧縮機リレー接点溶着',
    'Compressor Relay Contact Stuck',
    '继电器触点粘连导致压缩机持续运行。',
    2,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('relayFinalUse', 7, 'relayFailedInitialUse', 4),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'compressorContinuousRun', 'operator', 'EQ', 'value', 'YES', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'relayOutputAfterStopCommand', 'operator', 'EQ', 'value', 'ENERGIZED', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'compressorContinuousRun', 'operator', 'EQ', 'value', 'NO', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'relayOutputAfterStopCommand', 'operator', 'EQ', 'value', 'OFF', 'effect', 'EXCLUDE')
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'compressorContinuousRun', 'level', 'B', 'questionZh', '达到设定温度后压缩机是否仍持续运行？'),
        JSON_OBJECT('field', 'relayOutputAfterStopCommand', 'level', 'C', 'questionZh', '停机命令后继电器输出是否仍存在？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('CHECK_RELAY_OUTPUT', 'CHECK_CONTACT_CONTINUITY'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('COMPRESSOR_RELAY'),
        'forbidden', JSON_ARRAY('REPLACE_RELAY_FROM_SYMPTOM_ONLY')
    )
),
(
    @pt_cabinet_too_cold,
    'TEMPERATURE_CONTROL_BOARD_FAILURE',
    '温控板或控制回路异常',
    '温度制御基板・制御回路異常',
    'Temperature Control Board Failure',
    '控制板输入或输出异常导致温度控制失效。',
    3,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('controlBoardFinalUse', 9),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'sensorReading', 'operator', 'IN_SPEC', 'strength', 'MEDIUM'),
            JSON_OBJECT('field', 'controlOutput', 'operator', 'INCONSISTENT_WITH_COMMAND', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'controlOutput', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'sensorReading', 'operator', 'OUT_OF_SPEC', 'effect', 'PENALIZE', 'penalty', 20)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'controlOutput', 'level', 'C', 'questionZh', '控制板输出是否与温控命令一致？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('CHECK_SENSOR_INPUT', 'CHECK_RELAY_COMMAND', 'CHECK_BOARD_OUTPUT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('CONTROL_BOARD'),
        'forbidden', JSON_ARRAY('REPLACE_CONTROL_BOARD_WITHOUT_INPUT_OUTPUT_CHECK')
    )
),
(
    @pt_defrost_failure,
    'DEFROST_SENSOR_OR_SAFETY_THERMOSTAT',
    '除霜传感器或安全温控器异常',
    'デフロストセンサ・安全サーモ異常',
    'Defrost Sensor or Safety Thermostat Failure',
    '除霜检测或安全保护元件异常导致除霜中断。',
    1,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT(
            'exactIncidents', 25,
            'safetyThermostatFinalUse', 9,
            'defrostThermistorFinalUse', 4
        ),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'sensorReading', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'safetyThermostatContinuity', 'operator', 'ABNORMAL', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'errorCode', 'operator', 'EQ', 'value', 'E3', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'sensorReading', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 15),
            JSON_OBJECT('field', 'safetyThermostatContinuity', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 15)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'sensorReading', 'level', 'C', 'questionZh', '除霜传感器读数是否在规格范围？'),
        JSON_OBJECT('field', 'safetyThermostatContinuity', 'level', 'C', 'questionZh', '安全温控器导通状态是否正常？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_DEFROST_SENSOR', 'CHECK_SAFETY_THERMOSTAT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('DEFROST_THERMISTOR', 'SAFETY_DEFROST_THERMOSTAT'),
        'forbidden', JSON_ARRAY('BYPASS_SAFETY_DEVICE')
    )
),
(
    @pt_defrost_failure,
    'DEFROST_HEATER_FAILURE',
    '除霜加热器异常',
    'デフロストヒータ異常',
    'Defrost Heater Failure',
    '除霜加热器断路、短路或未获得正常供电。',
    2,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('defrostHeaterFinalUse', 6),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'heaterResistance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'heaterVoltageDuringDefrost', 'operator', 'PRESENT_WITHOUT_HEAT', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'heaterResistance', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 20),
            JSON_OBJECT('field', 'heaterTemperatureRise', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'EXCLUDE')
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'heaterResistance', 'level', 'C', 'questionZh', '是否测量过除霜加热器阻值？'),
        JSON_OBJECT('field', 'heaterVoltageDuringDefrost', 'level', 'C', 'questionZh', '除霜时加热器端是否有电压？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_HEATER_RESISTANCE', 'CHECK_HEATER_SUPPLY'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('DEFROST_HEATER'),
        'forbidden', JSON_ARRAY('ENERGIZE_WITH_SAFETY_CIRCUIT_BYPASSED')
    )
),
(
    @pt_defrost_failure,
    'DEFROST_CONTROL_OR_FREQUENCY_SETTING',
    '除霜控制或周期设置异常',
    'デフロスト制御・周期設定異常',
    'Defrost Control or Frequency Setting Issue',
    '除霜参数、周期或控制板输出异常导致除霜不足。',
    3,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT('noPartFinalResolved', 6, 'controlBoardFinalUse', 7),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'defrostCycle', 'operator', 'INCOMPLETE_OR_SKIPPED', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'heaterResistance', 'operator', 'IN_SPEC', 'strength', 'MEDIUM'),
            JSON_OBJECT('field', 'controlOutput', 'operator', 'ABNORMAL_DURING_DEFROST', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'defrostCycle', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 20),
            JSON_OBJECT('field', 'controlOutput', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 20)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'defrostCycle', 'level', 'B', 'questionZh', '最近一次除霜是否完整执行？'),
        JSON_OBJECT('field', 'defrostSetting', 'level', 'C', 'questionZh', '除霜周期和参数是否符合设备要求？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('CHECK_DEFROST_SETTING', 'OBSERVE_DEFROST_CYCLE', 'CHECK_CONTROL_OUTPUT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('CONTROL_BOARD'),
        'forbidden', JSON_ARRAY('CHANGE_SAFETY_LIMITS_WITHOUT_MANUAL')
    )
),
(
    @pt_compressor_start,
    'POWER_OR_WIRING_CONNECTION_FAILURE',
    '电源、配线或连接异常',
    '電源・配線・接続異常',
    'Power, Wiring, or Connection Failure',
    '输入电源、保护装置、端子或配线异常导致压缩机无法启动。',
    1,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT('exactIncidents', 21, 'noPartFinalResolved', 3),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'inputVoltage', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'connectorState', 'operator', 'IN', 'value', JSON_ARRAY('LOOSE', 'BURNED', 'DISCONNECTED'), 'strength', 'HIGH'),
            JSON_OBJECT('field', 'protectorState', 'operator', 'EQ', 'value', 'TRIPPED', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'inputVoltage', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 15),
            JSON_OBJECT('field', 'connectorState', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 15)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'inputVoltage', 'level', 'B', 'questionZh', '压缩机输入端电压是多少？'),
        JSON_OBJECT('field', 'connectorState', 'level', 'B', 'questionZh', '接线端子是否松动、烧蚀或脱落？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_INPUT_VOLTAGE', 'INSPECT_WIRING', 'CHECK_PROTECTOR'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY(),
        'forbidden', JSON_ARRAY('ENERGIZED_WIRING_REPAIR')
    )
),
(
    @pt_compressor_start,
    'START_RELAY_OR_CAPACITOR_FAILURE',
    '启动继电器或启动电容异常',
    '始動リレー・始動コンデンサ異常',
    'Start Relay or Capacitor Failure',
    '启动元件异常导致压缩机无法建立启动转矩。',
    2,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('startCapacitorFinalUse', 5, 'compressorRelayFinalUse', 3),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'capacitance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'relayOutput', 'operator', 'ABNORMAL', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'relaySound', 'operator', 'IN', 'value', JSON_ARRAY('NO_CLICK', 'REPEATED_CLICK'), 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'capacitance', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 15),
            JSON_OBJECT('field', 'relayOutput', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 15)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'relaySound', 'level', 'B', 'questionZh', '启动时继电器是否动作或反复吸合？'),
        JSON_OBJECT('field', 'capacitance', 'level', 'C', 'questionZh', '启动电容实测值是多少？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_CAPACITANCE', 'CHECK_START_RELAY', 'MEASURE_START_CURRENT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('START_CAPACITOR', 'START_RELAY'),
        'forbidden', JSON_ARRAY('REPLACE_FROM_SOUND_ONLY')
    )
),
(
    @pt_compressor_start,
    'CONTROL_BOARD_OR_COMPRESSOR_FAILURE',
    '控制板或压缩机异常',
    '制御基板・圧縮機異常',
    'Control Board or Compressor Failure',
    '控制板未发出启动命令，或压缩机绕组、绝缘和机械状态异常。',
    3,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('controlBoardFinalUse', 2, 'compressorFinalUse', 4),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'controlOutput', 'operator', 'MISSING_START_COMMAND', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'windingResistance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'insulationResistance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'startCurrent', 'operator', 'LOCKED_ROTOR_PATTERN', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'controlOutput', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 10),
            JSON_OBJECT('field', 'windingResistance', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 10),
            JSON_OBJECT('field', 'insulationResistance', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 10)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'controlOutput', 'level', 'C', 'questionZh', '控制板是否输出启动命令？'),
        JSON_OBJECT('field', 'startCurrent', 'level', 'C', 'questionZh', '压缩机启动电流是多少？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('CHECK_BOARD_OUTPUT', 'MEASURE_WINDING', 'MEASURE_INSULATION', 'MEASURE_START_CURRENT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('CONTROL_BOARD', 'COMPRESSOR'),
        'forbidden', JSON_ARRAY('REPLACE_COMPRESSOR_WITHOUT_ELECTRICAL_TEST')
    )
),
(
    @pt_compressor_overload,
    'COMPRESSOR_MECHANICAL_LOCK',
    '压缩机机械锁定或内部异常',
    '圧縮機ロック・内部異常',
    'Compressor Mechanical Lock or Internal Failure',
    '压缩机锁定、绕组或内部机械异常引发过载保护。',
    1,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT('exactIncidents', 23, 'compressorFinalUse', 17),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'startCurrent', 'operator', 'LOCKED_ROTOR_PATTERN', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'windingResistance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'insulationResistance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'startCurrent', 'operator', 'IN_SPEC', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'windingResistance', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 10),
            JSON_OBJECT('field', 'insulationResistance', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 10)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'startCurrent', 'level', 'B', 'questionZh', '启动电流和保护动作间隔是多少？'),
        JSON_OBJECT('field', 'windingResistance', 'level', 'C', 'questionZh', '绕组和绝缘测量是否正常？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_START_CURRENT', 'MEASURE_WINDING', 'MEASURE_INSULATION'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('COMPRESSOR'),
        'companionPartsOnly', JSON_ARRAY('DRIER', 'COMPRESSOR_GROMMET'),
        'forbidden', JSON_ARRAY('TREAT_COMPANION_PART_AS_ROOT_CAUSE')
    )
),
(
    @pt_compressor_overload,
    'START_CAPACITOR_FAILURE',
    '启动电容异常',
    '始動コンデンサ異常',
    'Start Capacitor Failure',
    '启动电容容量衰减、开路或外观异常导致启动失败和过载。',
    2,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT('startCapacitorFinalUse', 9),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'capacitance', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'capacitorAppearance', 'operator', 'IN', 'value', JSON_ARRAY('BULGED', 'LEAKING'), 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'capacitance', 'operator', 'IN_SPEC', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'capacitorAppearance', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'PENALIZE', 'penalty', 10)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'capacitance', 'level', 'C', 'questionZh', '启动电容实测容量是多少？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_CAPACITANCE', 'INSPECT_CAPACITOR'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('START_CAPACITOR'),
        'forbidden', JSON_ARRAY('REPLACE_WITHOUT_DISCHARGE_AND_MEASUREMENT')
    )
),
(
    @pt_compressor_overload,
    'START_RELAY_FAILURE',
    '启动继电器异常',
    '始動リレー異常',
    'Start Relay Failure',
    '启动继电器触点或线圈异常导致压缩机反复启动并触发保护。',
    3,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('startRelayFinalUse', 3, 'startRelayFailedInitialUse', 2),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'relayOutput', 'operator', 'ABNORMAL', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'relaySound', 'operator', 'EQ', 'value', 'REPEATED_CLICK', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'relayOutput', 'operator', 'EQ', 'value', 'NORMAL', 'effect', 'EXCLUDE')
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'relaySound', 'level', 'B', 'questionZh', '启动继电器是否反复吸合？'),
        JSON_OBJECT('field', 'relayOutput', 'level', 'C', 'questionZh', '继电器输出是否正常？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('CHECK_START_RELAY', 'MEASURE_RELAY_OUTPUT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('START_RELAY'),
        'forbidden', JSON_ARRAY('REPLACE_FROM_SOUND_ONLY')
    )
),
(
    @pt_high_pressure,
    'CONDENSER_FILTER_CLOGGING',
    '冷凝器或过滤网堵塞',
    '凝縮器・フィルタ目詰まり',
    'Condenser or Filter Clogging',
    '冷凝器或过滤网积尘、风路受阻造成散热能力下降和高压保护。',
    1,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_STRONG',
        'history', JSON_OBJECT(
            'exactIncidents', 28,
            'finalResolved', 27,
            'noPartFinalResolved', 11
        ),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'condenserState', 'operator', 'IN', 'value', JSON_ARRAY('DIRTY', 'CLOGGED'), 'strength', 'HIGH'),
            JSON_OBJECT('field', 'airflowState', 'operator', 'EQ', 'value', 'BLOCKED', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'errorCode', 'operator', 'EQ', 'value', 'E4', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'condenserState', 'operator', 'EQ', 'value', 'CLEAN', 'effect', 'PENALIZE', 'penalty', 15),
            JSON_OBJECT('field', 'airflowState', 'operator', 'EQ', 'value', 'CLEAR', 'effect', 'PENALIZE', 'penalty', 15)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'condenserState', 'level', 'B', 'questionZh', '冷凝器和过滤网是否积尘或堵塞？'),
        JSON_OBJECT('field', 'airflowState', 'level', 'B', 'questionZh', '设备周围风路是否畅通？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('INSPECT_CONDENSER', 'CLEAN_CONDENSER_AND_FILTER', 'CHECK_AIRFLOW'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY(),
        'forbidden', JSON_ARRAY('OPEN_REFRIGERANT_CIRCUIT_BEFORE_CLEANING_CHECK')
    )
),
(
    @pt_high_pressure,
    'REFRIGERANT_CHARGE_OR_CIRCUIT_ABNORMALITY',
    '冷媒充注或回路异常',
    '冷媒充填・回路異常',
    'Refrigerant Charge or Circuit Abnormality',
    '冷媒过量、回路受阻或维修后充注异常导致系统高压。',
    2,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('drierFinalUse', 14),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'pressure', 'operator', 'HIGH_AFTER_AIRFLOW_CONFIRMED', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'recentRefrigerantService', 'operator', 'EQ', 'value', 'YES', 'strength', 'MEDIUM'),
            JSON_OBJECT('field', 'chargeAmount', 'operator', 'OUT_OF_SPEC', 'strength', 'HIGH')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'pressure', 'operator', 'NORMAL_AFTER_CLEANING', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'chargeAmount', 'operator', 'IN_SPEC', 'effect', 'PENALIZE', 'penalty', 20)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'pressure', 'level', 'B', 'questionZh', '清洁并确认风路后高低压分别是多少？'),
        JSON_OBJECT('field', 'recentRefrigerantService', 'level', 'C', 'questionZh', '近期是否进行过冷媒维修或充注？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_PRESSURE', 'VERIFY_CHARGE_HISTORY', 'PREPARE_CIRCUIT_SERVICE_PARTS'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('DRIER', 'REFRIGERANT_CIRCUIT_PARTS'),
        'forbidden', JSON_ARRAY('ADJUST_CHARGE_WITHOUT_MEASUREMENT')
    )
),
(
    @pt_high_pressure,
    'HIGH_PRESSURE_SWITCH_ABNORMALITY',
    '高压开关动作或本体异常',
    '高圧スイッチ作動・本体異常',
    'High Pressure Switch Activation or Failure',
    '高压开关正常保护动作，或在实际压力正常时出现开关本体异常。',
    3,
    JSON_OBJECT(
        'evidenceLevel', 'HISTORY_SUPPORTED',
        'history', JSON_OBJECT('pressureSwitchFinalUse', 4, 'pressureSwitchFailedInitialUse', 2),
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'pressureSwitchState', 'operator', 'INCONSISTENT_WITH_MEASURED_PRESSURE', 'strength', 'HIGH'),
            JSON_OBJECT('field', 'errorCode', 'operator', 'EQ', 'value', 'E4', 'strength', 'MEDIUM')
        )
    ),
    JSON_OBJECT(
        'signals', JSON_ARRAY(
            JSON_OBJECT('field', 'pressureSwitchState', 'operator', 'CONSISTENT_WITH_HIGH_PRESSURE', 'effect', 'EXCLUDE'),
            JSON_OBJECT('field', 'pressure', 'operator', 'HIGH', 'effect', 'PENALIZE', 'penalty', 20)
        )
    ),
    JSON_ARRAY(
        JSON_OBJECT('field', 'pressure', 'level', 'B', 'questionZh', '高压开关动作时的实测压力是多少？'),
        JSON_OBJECT('field', 'pressureSwitchState', 'level', 'C', 'questionZh', '压力正常时开关输出是否仍异常？')
    ),
    JSON_OBJECT(
        'evidenceClass', 'HISTORICAL_GUIDANCE',
        'allowed', JSON_ARRAY('MEASURE_PRESSURE', 'CHECK_PRESSURE_SWITCH_OUTPUT'),
        'requiresConfirmationBeforeReplacement', JSON_ARRAY('HIGH_PRESSURE_SWITCH'),
        'forbidden', JSON_ARRAY('BYPASS_PRESSURE_SWITCH', 'CALL_SWITCH_FAILURE_WHEN_PRESSURE_IS_HIGH')
    )
);
