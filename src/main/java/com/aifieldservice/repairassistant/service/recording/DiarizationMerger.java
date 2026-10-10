package com.aifieldservice.repairassistant.service.recording;

import java.util.*;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;

/** Associates model output only. Never guesses identity from order or acoustic features. */
public final class DiarizationMerger {
    private List<Transcript> confirmed = new ArrayList<>();
    private boolean hasA, hasB;
    public List<Transcript> merge(long offset, List<Transcript> local, Set<String> referenceNames) {
        var mapping = new HashMap<String, String>();
        // Reference names deliberately differ from provider-local A/B identifiers.
        var labels = local.stream().map(Transcript::speaker).filter(s -> !s.equals("UNKNOWN")).distinct().toList();
        for (String name : referenceNames) if(labels.contains(name)) mapping.put(name, name.equals("session_A") ? "A" : "B");
        long speechDuration=local.stream().mapToLong(s -> s.endMs()-s.startMs()).sum();
        if (!hasA && !labels.isEmpty() && speechDuration>=4000) {
            mapping.put(labels.get(0), "A"); hasA=true;
            if(labels.size()>=2) { mapping.put(labels.get(1), "B"); hasB=true; }
        } else {
            for (String label : labels) {
                var votes = new HashSet<String>();
                for (Transcript s : local) if (s.speaker().equals(label)) {
                    for (Transcript old : confirmed) {
                        long overlap = Math.min(old.endMs(), offset + s.endMs()) - Math.max(old.startMs(), offset + s.startMs());
                        String text = normalize(s.text()), previous = normalize(old.text());
                        long shorter=Math.min(old.endMs()-old.startMs(),s.endMs()-s.startMs());
                        boolean sameText=text.length()>=4 && previous.length()>=4
                                && (text.equals(previous) || text.contains(previous) || previous.contains(text));
                        if (overlap >= 250 && overlap*2>=shorter && sameText
                                && Set.of("A", "B").contains(old.speaker())) votes.add(old.speaker());
                    }
                }
                if (votes.size() == 1 && !mapping.containsKey(label)) mapping.put(label, votes.iterator().next());
            }
            var unmapped=labels.stream().filter(label -> !mapping.containsKey(label)).toList();
            // A clean A reference makes the model's newly labelled speaker evidence for a new identity.
            // This only creates the second identity once; later unmatched voices remain unknown.
            boolean anchoredA=mapping.containsValue("A") || referenceNames.contains("session_A");
            if(hasA && !hasB && anchoredA && unmapped.size()==1 && speechDuration>=2000) {
                mapping.put(unmapped.getFirst(),"B"); hasB=true;
            }
            // Multiple local speakers claiming one identity is ambiguous, unless model used a reference name.
            for (String identity : List.of("A", "B")) {
                var keys = mapping.entrySet().stream().filter(e -> e.getValue().equals(identity)).map(Map.Entry::getKey).toList();
                if (keys.size() > 1) keys.stream().filter(k -> !referenceNames.contains(k)).forEach(mapping::remove);
            }
        }
        var result = new ArrayList<Transcript>();
        for (Transcript old : confirmed) {
            boolean revised=local.stream().anyMatch(s -> offset+s.startMs()<old.endMs() && offset+s.endMs()>old.startMs());
            if(old.endMs()<=offset || !revised) result.add(old);
        }
        for (Transcript s : local) {
            long start = offset + s.startMs(), end = offset + s.endMs();
            String speaker = mapping.getOrDefault(s.speaker(), "UNKNOWN");
            // Keep boundary utterances whole; only suppress a corroborated duplicate, never cut text by character count.
            boolean duplicate = result.stream().anyMatch(old -> old.endMs() > start && old.startMs() < end
                    && normalize(old.text()).equals(normalize(s.text())));
            if (!duplicate) result.add(new Transcript(s.providerId(), start, end, speaker, s.text()));
        }
        result.sort(Comparator.comparingLong(Transcript::startMs));
        if (result.size() > 10000) throw new IllegalStateException("演示对话过长，请使用较短录音。");
        confirmed = result;
        return List.copyOf(result);
    }
    private static String normalize(String text) { return text.replaceAll("[\\p{P}\\s]", ""); }
}
