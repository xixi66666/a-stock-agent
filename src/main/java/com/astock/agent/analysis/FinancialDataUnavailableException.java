package com.astock.agent.analysis;

/** 财报历史数据源不可用时抛出;由 web 层转换为 RFC 9457 问题详情。 */
public final class FinancialDataUnavailableException extends RuntimeException {

    public FinancialDataUnavailableException(String message) {
        super(message);
    }
}
