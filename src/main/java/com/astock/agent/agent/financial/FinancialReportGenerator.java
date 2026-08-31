package com.astock.agent.agent.financial;

import java.util.List;

/** 财务叙事生成器:只写叙事,不接触数据或计算。 */
public interface FinancialReportGenerator {

    FinancialNarrativeDraft generate(FinancialEvidencePackage pack) throws Exception;

    FinancialNarrativeDraft repair(FinancialEvidencePackage pack,
            FinancialNarrativeDraft draft, List<String> issues) throws Exception;

    String modelName();
}
