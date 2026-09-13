package com.astock.agent.agent.overall;

import org.springframework.ai.converter.BeanOutputConverter;

/**
 * 保留 Spring AI 的结构化输出请求（格式指令与 JSON Schema），
 * 仅把客户端转换替换为容忍模型输出噪声的 {@link OverallReportDraftJsonParser}。
 */
final class RobustOverallReportDraftConverter extends BeanOutputConverter<OverallReportDraft> {

    RobustOverallReportDraftConverter() {
        super(OverallReportDraft.class);
    }

    @Override
    public OverallReportDraft convert(String text) {
        return OverallReportDraftJsonParser.parse(text);
    }
}
