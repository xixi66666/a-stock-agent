package com.astock.agent.agent.overall;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.regex.Pattern;

/**
 * 从模型原始文本中提取并解析 {@link OverallReportDraft}。
 *
 * <p>模型可能返回代码围栏、前后解释文字、思考标签、尾逗号或未转义控制字符；
 * 解析器先定位第一个完整的 JSON 对象，再以宽容模式映射为草稿。它只处理格式，
 * 不校验报告内容。</p>
 */
final class OverallReportDraftJsonParser {

    private static final Pattern THINKING_TAG = Pattern.compile(
            "(?is)<think(?:ing)?>.*?</think(?:ing)?>");
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
            .findAndRegisterModules();

    private OverallReportDraftJsonParser() {
    }

    static OverallReportDraft parse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new IllegalStateException("模型返回空响应 (empty response)");
        }
        String candidate = extractJsonObject(rawResponse);
        OverallReportDraft draft;
        try {
            draft = MAPPER.readValue(candidate, OverallReportDraft.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "模型响应不是可解析的 OverallReportDraft JSON (JSON parse failed): "
                            + summarize(failure),
                    failure);
        }
        if (draft.overallConclusion() == null || draft.overallConclusion().isBlank()) {
            throw new IllegalStateException(
                    "模型响应缺少 OverallReportDraft 核心字段 (JSON parse failed)");
        }
        return draft;
    }

    /** 定位第一个括号配平的 JSON 对象，忽略其前后的围栏、说明文字和思考标签。 */
    private static String extractJsonObject(String rawResponse) {
        String text = THINKING_TAG.matcher(rawResponse.replace("\uFEFF", "")).replaceAll("");
        int start = text.indexOf('{');
        if (start < 0) {
            throw new IllegalStateException("模型响应中未找到 JSON 对象 (JSON parse failed)");
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char current = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        throw new IllegalStateException("模型响应 JSON 未闭合，可能被截断 (JSON parse failed: truncated)");
    }

    private static String summarize(JsonProcessingException failure) {
        String message = failure.getOriginalMessage();
        if (message == null || message.isBlank()) {
            message = failure.getMessage();
        }
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
