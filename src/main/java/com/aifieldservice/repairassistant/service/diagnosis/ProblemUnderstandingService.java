package com.aifieldservice.repairassistant.service.diagnosis;


import com.aifieldservice.repairassistant.domain.diagnosis.command.ProblemUnderstandingRequest;
import com.aifieldservice.repairassistant.domain.diagnosis.model.*;


/** 将自然语言报障转化并保存为结构化问题理解。 */
public interface ProblemUnderstandingService {

    /** 识别故障类别、抽取字段并判断信息是否足以开始诊断。 */
    ProblemUnderstanding understand(ProblemUnderstandingRequest request);

    /** 按标识读取先前保存的问题理解结果。 */
    ProblemUnderstanding get(String id);
}
