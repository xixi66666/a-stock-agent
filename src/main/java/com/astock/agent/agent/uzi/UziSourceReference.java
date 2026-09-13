package com.astock.agent.agent.uzi;

/** UZI 产物中可追溯的原始数据来源。 */
public record UziSourceReference(String dimension, String provider, String url, String observedAt) {
}
