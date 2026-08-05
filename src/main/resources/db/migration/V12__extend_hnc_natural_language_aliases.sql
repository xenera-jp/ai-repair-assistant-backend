-- Extend HNC-120AA problem categories with the phrases engineers and customers
-- actually use. These aliases are deterministic routing hints: matching one of
-- them selects the reviewed HNC taxonomy before retrieval or LLM reasoning.

UPDATE problem_type
SET aliases_json = JSON_ARRAY_APPEND(
        aliases_json,
        '$', '冷却效果差',
        '$', '冷却不够',
        '$', '冷不下来',
        '$', '温度降不下去',
        '$', '冷えが弱い',
        '$', '冷えない')
WHERE code = 'COOLING_INSUFFICIENT_HNC';

UPDATE problem_type
SET aliases_json = JSON_ARRAY_APPEND(
        aliases_json,
        '$', '食品变干',
        '$', '食材变干',
        '$', '表面干燥',
        '$', '食品が乾く',
        '$', '食材が乾燥する')
WHERE code = 'CABINET_DRYNESS_HNC';

UPDATE problem_type
SET aliases_json = JSON_ARRAY_APPEND(
        aliases_json,
        '$', '外部结霜',
        '$', '内部结霜',
        '$', '门附近结霜',
        '$', '外側に霜が付く',
        '$', '庫内に霜が付く')
WHERE code = 'ENVIRONMENTAL_FROST_HNC';

UPDATE problem_type
SET aliases_json = JSON_ARRAY_APPEND(
        aliases_json,
        '$', '无法启动',
        '$', '设备没反应',
        '$', '通电后没反应',
        '$', '起動できない',
        '$', '通電しても動かない')
WHERE code = 'EQUIPMENT_NO_START_HNC';
