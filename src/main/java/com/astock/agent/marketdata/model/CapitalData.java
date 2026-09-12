package com.astock.agent.marketdata.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CapitalData(
        List<MarginRecord> marginHistory,
        List<BlockTrade> blockTrades,
        List<ShareholderChange> shareholderChanges,
        List<UnlockRecord> unlocks,
        List<DividendRecord> dividends,
        List<DragonTigerRecord> dragonTigerRecords,
        java.util.Map<String, DataSection<?>> components) {

    public CapitalData(List<MarginRecord> marginHistory, List<BlockTrade> blockTrades,
            List<ShareholderChange> shareholderChanges, List<UnlockRecord> unlocks,
            List<DividendRecord> dividends, List<DragonTigerRecord> dragonTigerRecords) {
        this(marginHistory,blockTrades,shareholderChanges,unlocks,dividends,dragonTigerRecords,java.util.Map.of());
    }

    public CapitalData {
        marginHistory = copy(marginHistory);
        blockTrades = copy(blockTrades);
        shareholderChanges = copy(shareholderChanges);
        unlocks = copy(unlocks);
        dividends = copy(dividends);
        dragonTigerRecords = copy(dragonTigerRecords);
        components = components == null ? java.util.Map.of() : java.util.Map.copyOf(components);
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    public record MarginRecord(
            LocalDate date,
            BigDecimal financingBalanceYuan,
            BigDecimal financingBuyYuan,
            BigDecimal securitiesLendingBalanceYuan,
            BigDecimal totalBalanceYuan) {
    }

    public record BlockTrade(
            LocalDate date,
            BigDecimal price,
            BigDecimal close,
            BigDecimal premiumPercent,
            BigDecimal volumeShares,
            BigDecimal amountYuan,
            String buyer,
            String seller) {
    }

    public record ShareholderChange(
            LocalDate date,
            BigDecimal holderCount,
            BigDecimal changePercent,
            BigDecimal averageShares) {
    }

    public record UnlockRecord(
            LocalDate date,
            String type,
            BigDecimal sharesTenThousand,
            BigDecimal tradableSharesTenThousand,
            BigDecimal totalShareRatio) {
    }

    public record DividendRecord(
            LocalDate exDate,
            BigDecimal cashPerShareYuan,
            BigDecimal transferPerTenShares,
            BigDecimal bonusPerTenShares,
            String status) {
    }

    public record DragonTigerRecord(
            LocalDate date,
            String reason,
            BigDecimal netBuyYuan,
            BigDecimal turnoverPercent) {
    }
}
