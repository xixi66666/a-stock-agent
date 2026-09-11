package com.astock.agent.knowledge;

/** 已核对的短引文；scope 为项目说明，不能混入原文。章节定位不冒充纸书页码。 */
public record BookExcerpt(String bookTitle, String author, String chapter,
        String text, String sourceLocator, String scope) {}
