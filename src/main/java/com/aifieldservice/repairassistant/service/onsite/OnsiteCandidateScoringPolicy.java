package com.aifieldservice.repairassistant.service.onsite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteAnswer;

/** Stable scoring rules for onsite observations. */
@Component
public class OnsiteCandidateScoringPolicy {

    public int scoreDelta(String responseType, String value) {
        if (Set.of("UNAVAILABLE", "SKIPPED").contains(responseType)) {
            return 0;
        }
        if ("OTHER_TEXT".equals(responseType)) {
            return 3;
        }
        Set<String> conflictingValues = Set.of(
                "NORMAL", "CLEAN", "CLEAR", "ABSENT", "NO", "OFF", "NOT_RUNNING");
        return conflictingValues.contains(value) ? -18 : 8;
    }

    public String supportBand(double score) {
        return score >= 80 ? "STRONG_SUPPORT"
                : score >= 65 ? "SUPPORTED" : "NEEDS_CONFIRMATION";
    }

    public boolean isConverged(List<DiagnosisCandidate> candidates) {
        if (candidates.isEmpty()) {
            return false;
        }
        double first = candidates.get(0).supportScore();
        double second = candidates.size() > 1 ? candidates.get(1).supportScore() : 0;
        return first >= 75 && first - second >= 15;
    }

    public boolean hasSupportedCandidate(List<DiagnosisCandidate> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.supportScore() >= 55);
    }

    public List<DiagnosisCandidate> rescore(
            List<DiagnosisCandidate> candidates, OnsiteQuestion question, OnsiteAnswer answer, boolean japanese) {
        int delta = scoreDelta(answer.responseType(), answer.value());
        List<DiagnosisCandidate> rescored = new ArrayList<>();
        for (DiagnosisCandidate candidate : candidates) {
            double score = candidate.supportScore();
            String explanation = candidate.explanation();
            List<String> evidenceIds = candidate.evidenceIds();
            if (candidate.code().equals(question.candidateCode())) {
                score = Math.max(0, Math.min(95, score + delta));
                explanation = japanese ? "%s 現場確認（第%dラウンド）：%s。".formatted(explanation, question.round(), answer.label())
                        : "%s 现场第 %d 轮确认：%s。".formatted(explanation, question.round(), answer.label());
                if (!Set.of("UNAVAILABLE", "SKIPPED").contains(answer.responseType())) {
                    evidenceIds = new ArrayList<>(evidenceIds);
                    evidenceIds.add("ONSITE-" + question.id());
                }
            }
            rescored.add(new DiagnosisCandidate(candidate.code(), candidate.label(), candidate.rank(),
                    Math.round(score), supportBand(score), explanation, List.copyOf(evidenceIds)));
        }
        rescored.sort(Comparator.comparingDouble(DiagnosisCandidate::supportScore).reversed()
                .thenComparing(DiagnosisCandidate::rank));
        List<DiagnosisCandidate> ranked = new ArrayList<>();
        for (int index = 0; index < rescored.size(); index++) {
            DiagnosisCandidate candidate = rescored.get(index);
            ranked.add(new DiagnosisCandidate(candidate.code(), candidate.label(), index + 1,
                    candidate.supportScore(), candidate.supportBand(), candidate.explanation(), candidate.evidenceIds()));
        }
        return List.copyOf(ranked);
    }

    public List<EvidenceGroup> appendEvidence(
            List<EvidenceGroup> groups, OnsiteQuestion question, OnsiteAnswer answer, boolean japanese) {
        EvidenceItem evidence = new EvidenceItem("ONSITE-" + question.id(),
                (japanese ? "現場確認・" : "现场确认 · ") + question.prompt(),
                japanese ? "現場分析・第%dラウンド".formatted(question.round()) : "现场分析第 %d 轮".formatted(question.round()),
                japanese ? "回答：%s".formatted(answer.label()) : "用户回答：%s".formatted(answer.label()),
                "USER_CONFIRMED", List.of(question.signalCode() + "=" + answer.value()), null);
        List<EvidenceGroup> result = new ArrayList<>();
        boolean found = false;
        for (EvidenceGroup group : groups) {
            if ("ONSITE_OBSERVATION".equals(group.type())) {
                List<EvidenceItem> items = new ArrayList<>(group.items()); items.add(evidence);
                result.add(new EvidenceGroup(group.type(), group.label(), List.copyOf(items))); found = true;
            } else result.add(group);
        }
        if (!found) result.add(0, new EvidenceGroup("ONSITE_OBSERVATION",
                japanese ? "現場確認事実" : "现场确认事实", List.of(evidence)));
        return List.copyOf(result);
    }
}
