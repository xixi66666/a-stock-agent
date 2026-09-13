package com.astock.agent.agent.finrobot;

import com.astock.agent.agent.overall.SupplementalResearchEvidence;
import java.util.Optional;

/** FinRobot 读取最新 UZI 证据的受限端口。 */
@FunctionalInterface
interface UziEvidenceProvider {

    Optional<SupplementalResearchEvidence> latest(String code);
}
