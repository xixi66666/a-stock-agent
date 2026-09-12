package com.astock.agent.marketdata.model;

import java.math.BigDecimal;

/** Latest valuation multiples; MRQ and TTM are distinct. Missing and negative values are preserved. */
public record ValuationSnapshot(SecurityId security, BigDecimal peTtm, BigDecimal peMrq,
        BigDecimal pbMrq, BigDecimal psTtm, BigDecimal pcfTtm) { }
