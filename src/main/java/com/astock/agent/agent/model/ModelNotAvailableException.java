package com.astock.agent.agent.model;

/** 请求指定的命名模型未注册或不可用。 */
public final class ModelNotAvailableException extends IllegalArgumentException {

    public ModelNotAvailableException() {
        super("Selected model is not available");
    }
}
