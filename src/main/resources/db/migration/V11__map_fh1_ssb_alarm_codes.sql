-- Extend the reviewed maintenance taxonomy with FH1-SSB alarm codes.
--
-- These mappings are deterministic metadata routes. They let the problem
-- understanding layer select the right retrieval strategy before semantic
-- search or LLM reasoning is considered.

UPDATE problem_type
SET error_codes_json = JSON_ARRAY_APPEND(
        error_codes_json,
        '$', JSON_OBJECT(
            'code', 'E6',
            'models', JSON_ARRAY('FH1-SSB'),
            'strength', 'STRONG'),
        '$', JSON_OBJECT(
            'code', 'E7',
            'models', JSON_ARRAY('FH1-SSB'),
            'strength', 'STRONG'),
        '$', JSON_OBJECT(
            'code', 'E10',
            'models', JSON_ARRAY('FH1-SSB'),
            'strength', 'STRONG'))
WHERE code = 'COMPRESSOR_START_FAILURE';

UPDATE problem_type
SET error_codes_json = JSON_ARRAY_APPEND(
        error_codes_json,
        '$', JSON_OBJECT(
            'code', 'E8',
            'models', JSON_ARRAY('FH1-SSB'),
            'strength', 'STRONG'))
WHERE code = 'TEMPERATURE_DISPLAY_FAULT';

UPDATE problem_type
SET error_codes_json = JSON_ARRAY_APPEND(
        error_codes_json,
        '$', JSON_OBJECT(
            'code', 'E9',
            'models', JSON_ARRAY('FH1-SSB'),
            'strength', 'STRONG'))
WHERE code = 'DEFROST_FAILURE_FROST';

UPDATE problem_type
SET error_codes_json = JSON_ARRAY_APPEND(
        error_codes_json,
        '$', JSON_OBJECT(
            'code', 'CF',
            'models', JSON_ARRAY('FH1-SSB'),
            'strength', 'STRONG'))
WHERE code = 'HIGH_PRESSURE_CONDENSATION';
