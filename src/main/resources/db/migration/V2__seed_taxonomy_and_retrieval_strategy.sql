-- AI Repair Assistant - Taxonomy domains and retrieval strategies V1
-- Flyway migration: V2__seed_taxonomy_and_retrieval_strategy.sql

INSERT INTO taxonomy_version (
    version_code,
    version_no,
    status,
    effective_from
) VALUES (
    'MAINTENANCE_TAXONOMY_V1',
    1,
    'ACTIVE',
    CURRENT_TIMESTAMP(3)
);

SET @taxonomy_id = LAST_INSERT_ID();

INSERT INTO problem_domain (
    taxonomy_version_id,
    code,
    name_zh,
    name_ja,
    name_en,
    description,
    sort_order
) VALUES
    (
        @taxonomy_id,
        'COOLING_CAPACITY',
        '冷却能力与冷媒回路',
        '冷却能力・冷媒回路',
        'Cooling Capacity and Refrigerant Circuit',
        '不冷、库内高温、制冷能力下降和冷媒泄漏。',
        10
    ),
    (
        @taxonomy_id,
        'TEMPERATURE_CONTROL',
        '温度控制与显示',
        '温度制御・表示',
        'Temperature Control and Display',
        '库内过冷、温度显示异常和 HNC 库内干燥。',
        20
    ),
    (
        @taxonomy_id,
        'DEFROST_DRAINAGE',
        '除霜、着霜与排水',
        'デフロスト・着霜・排水',
        'Defrost, Frost and Drainage',
        '除霜失败、环境性着霜和排水溢出。',
        30
    ),
    (
        @taxonomy_id,
        'COMPRESSOR_POWER',
        '压缩机、启动与电源',
        '圧縮機・起動・電源',
        'Compressor, Start and Power',
        '压缩机启动失败、过载跳闸和整机无法启动。',
        40
    ),
    (
        @taxonomy_id,
        'CONDENSATION_HIGH_PRESSURE',
        '冷凝与高压',
        '凝縮・高圧',
        'Condensation and High Pressure',
        '高压保护、冷凝器散热不良和冷媒充注异常。',
        50
    ),
    (
        @taxonomy_id,
        'MECHANICAL_NOISE',
        '机械振动与异音',
        '機械振動・異音',
        'Mechanical Vibration and Noise',
        '风机、压缩机、继电器和安装松动导致的异音。',
        60
    );

SET @json_true = JSON_EXTRACT('true', '$');
SET @json_false = JSON_EXTRACT('false', '$');

SET @base_strategy = JSON_OBJECT(
    'planVersion', '1.2',
    'allowedStages', JSON_ARRAY('PRE_DEPARTURE', 'ONSITE'),
    'hardFilters', JSON_OBJECT(
        'productModelRequiredForHighConfidence', @json_true,
        'crossModelRetrieval', @json_false,
        'errorCodeMode', 'EXACT_WHEN_PRESENT',
        'partNumberMode', 'EXACT_WHEN_PRESENT'
    ),
    'knowledgeTypes', JSON_ARRAY(
        'FAULT_DEFINITION',
        'REPAIR_PROCEDURE',
        'REPAIR_CASE',
        'PART_REFERENCE',
        'INTAKE_OBSERVATION'
    ),
    'steps', JSON_ARRAY(
        JSON_OBJECT(
            'code', 'REQUIRED_INFORMATION_GATE',
            'method', 'RULE',
            'onMissingRequired', 'ASK_CLARIFICATION',
            'required', @json_true
        ),
        JSON_OBJECT(
            'code', 'STRUCTURED_CASE_SQL',
            'method', 'SQL',
            'knowledgeTypes', JSON_ARRAY('REPAIR_CASE'),
            'required', @json_true
        ),
        JSON_OBJECT(
            'code', 'PROBLEM_SEMANTIC',
            'method', 'VECTOR',
            'vectorName', 'problem_vector',
            'topK', 20,
            'runWhen', 'STRUCTURED_EVIDENCE_INSUFFICIENT'
        ),
        JSON_OBJECT(
            'code', 'AUTHORITATIVE_GUIDANCE',
            'method', 'HYBRID',
            'knowledgeTypes', JSON_ARRAY(
                'FAULT_DEFINITION',
                'REPAIR_PROCEDURE'
            ),
            'topK', 10
        ),
        JSON_OBJECT(
            'code', 'LLM_CONTROLLED_FALLBACK',
            'method', 'LLM',
            'runWhen', 'NO_QUALIFIED_EVIDENCE',
            'allowedOutputs', JSON_ARRAY(
                'CLARIFICATION_QUESTION',
                'INSUFFICIENT_EVIDENCE_EXPLANATION'
            ),
            'unsupportedRepairAnswerAllowed', @json_false
        ),
        JSON_OBJECT(
            'code', 'HISTORICAL_PARTS',
            'method', 'METADATA',
            'knowledgeTypes', JSON_ARRAY('REPAIR_CASE', 'PART_REFERENCE'),
            'eligibility', 'SELECTED_CASES_ONLY'
        )
    ),
    'grouping', JSON_OBJECT(
        'caseIdentity', 'SOURCE_RECEPTION_ID',
        'preserveVisitTimeline', @json_true,
        'deduplicateKnowledgeVersion', @json_true
    ),
    'candidatePolicy', JSON_OBJECT(
        'minCandidates', 0,
        'maxCandidates', 3,
        'source', 'REGISTERED_HYPOTHESIS_ONLY',
        'scoreLabel', 'EVIDENCE_SUPPORT',
        'neverBackfillBelowThreshold', @json_true,
        'eligibility', JSON_OBJECT(
            'registeredHypothesisRequired', @json_true,
            'modelScopeMatchRequired', @json_true,
            'traceableEvidenceRequired', @json_true,
            'hardConflictPolicy', 'EXCLUDE',
            'hardFiltersNotScored', JSON_ARRAY('PRODUCT_MODEL', 'ERROR_CODE')
        ),
        'displayThresholds', JSON_OBJECT(
            'strongSupport', 75,
            'supported', 55,
            'needsConfirmation', 40,
            'hiddenBelow', 40
        ),
        'lowEvidencePolicy', JSON_OBJECT(
            'allowZeroCandidates', @json_true,
            'status', 'INSUFFICIENT_EVIDENCE',
            'nextAction', 'ASK_CLARIFICATION',
            'replacementRecommendationAllowed', @json_false
        )
    ),
    'candidateScoring', JSON_OBJECT(
        'scoreScale', 100,
        'weights', JSON_OBJECT(
            'currentSignalMatch', 0.40,
            'evidenceStrength', 0.25,
            'crossSourceConsistency', 0.15,
            'historicalOutcomeSupport', 0.10,
            'informationCompleteness', 0.10
        ),
        'conflictPolicy', JSON_OBJECT(
            'hardConflict', 'EXCLUDE',
            'hardConflictRequiresConfirmedSignal', @json_true,
            'softConflictMinPenalty', 5,
            'softConflictMaxPenalty', 30,
            'maxTotalSoftPenalty', 30,
            'unconfirmedSignalPolicy', 'PENALIZE_OR_CLARIFY'
        ),
        'evidenceTrustWeights', JSON_OBJECT(
            'AUTHORITATIVE', 1.00,
            'VERIFIED_CASE', 0.80,
            'OBSERVED_CASE', 0.50,
            'UNVERIFIED_OBSERVATION', 0.20
        ),
        'historicalAggregation', JSON_OBJECT(
            'identity', 'SOURCE_RECEPTION_ID',
            'deduplicateByIndependentEvent', @json_true
        ),
        'scoreMeaning', 'SUPPORT_NOT_PROBABILITY'
    ),
    'onsiteQuestioning', JSON_OBJECT(
        'presentationMode', 'ONE_AT_A_TIME',
        'maxAutomaticRounds', 3,
        'safetyCriticalFirst', @json_true,
        'questionSources', JSON_ARRAY(
            'SUPPORTING_SIGNAL',
            'CONFLICTING_SIGNAL',
            'CLARIFICATION_TEMPLATE',
            'SAFETY_CONFIRMATION'
        ),
        'questionPriorityWeights', JSON_OBJECT(
            'candidateDiscrimination', 0.50,
            'decisionImpact', 0.25,
            'onsiteAnswerability', 0.15,
            'safetyImportance', 0.10
        ),
        'supportedQuestionTypes', JSON_ARRAY(
            'SINGLE_CHOICE',
            'BOOLEAN',
            'MEASUREMENT',
            'TEXT'
        ),
        'specialResponses', JSON_OBJECT(
            'UNAVAILABLE', 'NO_EVIDENCE_AND_SKIP_DEPENDENT_QUESTIONS',
            'SKIPPED', 'NO_EVIDENCE',
            'OTHER_TEXT', 'EXTRACT_AND_REQUIRE_USER_CONFIRMATION'
        ),
        'otherTextRequiresConfirmation', @json_true,
        'reanalyzeAfterConfirmedResponse', @json_true,
        'defaultReanalysisMode', 'RESCORE_EXISTING_EVIDENCE',
        'fullRetrievalTriggers', JSON_ARRAY(
            'NEW_PRODUCT_MODEL',
            'NEW_ERROR_CODE',
            'PRIMARY_PROBLEM_TYPE_CHANGED',
            'NEW_KEY_SYMPTOM',
            'NEW_PART_NUMBER',
            'FALLBACK_REQUIRED'
        ),
        'stopConditions', JSON_OBJECT(
            'converged', JSON_OBJECT(
                'topCandidateMinScore', 75,
                'minLeadOverSecond', 15,
                'unresolvedHardConflictAllowed', @json_false,
                'requiredSafetyConfirmation', @json_true
            ),
            'partiallySupported', JSON_OBJECT(
                'topCandidateMinScore', 55,
                'whenMaxRoundsOrNoUsefulQuestion', @json_true
            ),
            'insufficientEvidence', JSON_OBJECT(
                'topCandidateBelowScore', 55,
                'whenNoUsefulQuestionOrMaxRounds', @json_true
            )
        ),
        'stopStates', JSON_ARRAY(
            'CONVERGED',
            'PARTIALLY_SUPPORTED',
            'INSUFFICIENT_EVIDENCE'
        ),
        'terminationReasons', JSON_ARRAY(
            'AUTO_CONVERGED',
            'MAX_ROUNDS',
            'NO_USEFUL_QUESTION',
            'KEY_INFORMATION_UNAVAILABLE',
            'USER_ENDED'
        )
    ),
    'retrievalRanking', JSON_OBJECT(
        'symptomSemantic', 0.35,
        'signalCoverage', 0.25,
        'problemTypeMatch', 0.20,
        'recordCompleteness', 0.10,
        'trustLevel', 0.10,
        'conflictPenalty', 0.25
    ),
    'evidencePolicy', JSON_OBJECT(
        'candidateEvidenceRequired', @json_true,
        'traceableSourceRequired', @json_true,
        'allowedTrustLevels', JSON_ARRAY(
            'AUTHORITATIVE',
            'VERIFIED_CASE',
            'OBSERVED_CASE'
        ),
        'supplementalTrustLevels', JSON_ARRAY(
            'UNVERIFIED_OBSERVATION'
        ),
        'supplementalEvidenceCannotQualifyCandidate', @json_true,
        'solutionSourcePriority', JSON_ARRAY(
            'VERIFIED_RESOLVED_CASE',
            'SERVICE_MANUAL'
        ),
        'serviceManualOverridesCaseFor', JSON_ARRAY(
            'SAFETY_WARNING',
            'PROHIBITED_ACTION',
            'MEASUREMENT_STANDARD',
            'APPLICABILITY'
        ),
        'intakeObservationRole', 'PROBLEM_COLLECTION_AND_CONTEXT_ONLY',
        'intakeObservationCannotSupportSolution', @json_true,
        'partUsageRole', 'SPARE_PART_PREPARATION_ONLY',
        'partUsageCannotProveRootCause', @json_true,
        'historicalPartLabel', 'HISTORICAL_USE',
        'officialPartClaimRequiresPartReference', @json_true
    ),
    'fallback', JSON_ARRAY(
        'ASK_FOR_REQUIRED_INFORMATION',
        'STRUCTURED_SQL',
        'SAME_MODEL_SEMANTIC',
        'LLM_CLARIFICATION_OR_INSUFFICIENT_EVIDENCE'
    )
);

INSERT INTO retrieval_strategy (
    taxonomy_version_id,
    code,
    name,
    config_json
) VALUES
    (
        @taxonomy_id,
        'COOLING_CAPACITY_V1',
        '冷却能力检索策略 V1',
        JSON_MERGE_PATCH(
            @base_strategy,
            JSON_OBJECT(
                'domainCode', 'COOLING_CAPACITY',
                'semanticFields', JSON_ARRAY(
                    'cabinetTemperature',
                    'coolingSpeed',
                    'fanState',
                    'doorSealState',
                    'oilTrace',
                    'pressure'
                ),
                'manualSectionHints', JSON_ARRAY(
                    'cooling',
                    'refrigerant',
                    'fan',
                    'door seal'
                )
            )
        )
    ),
    (
        @taxonomy_id,
        'TEMPERATURE_CONTROL_V1',
        '温度控制检索策略 V1',
        JSON_MERGE_PATCH(
            @base_strategy,
            JSON_OBJECT(
                'domainCode', 'TEMPERATURE_CONTROL',
                'semanticFields', JSON_ARRAY(
                    'setTemperature',
                    'displayTemperature',
                    'measuredTemperature',
                    'compressorContinuousRun',
                    'connectorState'
                ),
                'manualSectionHints', JSON_ARRAY(
                    'thermistor',
                    'temperature control',
                    'display board',
                    'relay'
                )
            )
        )
    ),
    (
        @taxonomy_id,
        'DEFROST_DRAINAGE_V1',
        '除霜排水检索策略 V1',
        JSON_MERGE_PATCH(
            @base_strategy,
            JSON_OBJECT(
                'domainCode', 'DEFROST_DRAINAGE',
                'semanticFields', JSON_ARRAY(
                    'frostLocation',
                    'frostPattern',
                    'defrostCycle',
                    'heaterResistance',
                    'sensorReading',
                    'drainFlow'
                ),
                'manualSectionHints', JSON_ARRAY(
                    'defrost',
                    'drain',
                    'heater',
                    'safety thermostat'
                )
            )
        )
    ),
    (
        @taxonomy_id,
        'COMPRESSOR_POWER_V1',
        '压缩机电源检索策略 V1',
        JSON_MERGE_PATCH(
            @base_strategy,
            JSON_OBJECT(
                'domainCode', 'COMPRESSOR_POWER',
                'semanticFields', JSON_ARRAY(
                    'inputVoltage',
                    'startCurrent',
                    'relaySound',
                    'protectorState',
                    'fanState',
                    'restartInterval'
                ),
                'manualSectionHints', JSON_ARRAY(
                    'compressor',
                    'start circuit',
                    'relay',
                    'overload protector',
                    'power supply'
                )
            )
        )
    ),
    (
        @taxonomy_id,
        'CONDENSATION_HIGH_PRESSURE_V1',
        '冷凝高压检索策略 V1',
        JSON_MERGE_PATCH(
            @base_strategy,
            JSON_OBJECT(
                'domainCode', 'CONDENSATION_HIGH_PRESSURE',
                'semanticFields', JSON_ARRAY(
                    'ambientTemperature',
                    'condenserCleanliness',
                    'airflow',
                    'highSidePressure',
                    'refrigerantCharge'
                ),
                'manualSectionHints', JSON_ARRAY(
                    'condenser',
                    'high pressure',
                    'airflow',
                    'refrigerant charge'
                )
            )
        )
    ),
    (
        @taxonomy_id,
        'MECHANICAL_NOISE_V1',
        '机械异音检索策略 V1',
        JSON_MERGE_PATCH(
            @base_strategy,
            JSON_OBJECT(
                'domainCode', 'MECHANICAL_NOISE',
                'semanticFields', JSON_ARRAY(
                    'soundLocation',
                    'soundTiming',
                    'soundCharacter',
                    'compressorAssociation',
                    'fanAssociation',
                    'vibrationPoint'
                ),
                'manualSectionHints', JSON_ARRAY(
                    'fan motor',
                    'compressor mounting',
                    'relay',
                    'fastener'
                )
            )
        )
    );
