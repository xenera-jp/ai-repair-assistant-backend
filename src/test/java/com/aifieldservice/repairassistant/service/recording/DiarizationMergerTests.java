package com.aifieldservice.repairassistant.service.recording;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;

class DiarizationMergerTests {
    private Transcript s(long start,long end,String speaker,String text) { return new Transcript("id",start,end,speaker,text); }
    @Test void mapsSwappedLocalLabelsOnlyThroughMatchingOverlap() {
        var merger=new DiarizationMerger();
        merger.merge(0,List.of(s(0,3000,"x","first speaker"),s(9000,12000,"y","second speaker")),Set.of());
        var result=merger.merge(9000,List.of(s(0,3000,"x","second speaker"),s(3000,4000,"y","no shared context")),Set.of());
        assertEquals("B",result.get(1).speaker());
        assertEquals("UNKNOWN",result.get(2).speaker());
        assertEquals(3,result.size());
    }
    @Test void singleSpeakerWindowDoesNotEstablishIdentityByOrder() {
        var result=new DiarizationMerger().merge(0,List.of(s(0,1000,"A","hello")),Set.of());
        assertEquals("UNKNOWN",result.getFirst().speaker());
    }
    @Test void distinguishesReferenceNamesFromProviderLocalLabels() {
        var merger=new DiarizationMerger();
        merger.merge(0,List.of(s(0,3000,"x","first"),s(3000,6000,"y","second")),Set.of());
        var result=merger.merge(6000,List.of(s(0,2000,"session_B","reference"),s(2000,3000,"A","extra")),Set.of("session_B"));
        assertEquals("B",result.get(2).speaker()); assertEquals("UNKNOWN",result.get(3).speaker());
    }
    @Test void wavHeaderMatchesPcmDurationAndFormat() {
        byte[] wav=DiarizationWindows.wav(new byte[48000]);
        var bytes=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(48044,wav.length); assertEquals(24000,bytes.getInt(24)); assertEquals(48000,bytes.getInt(40));
    }
    @Test void absentReferenceNamesDoNotEraseReliableOverlapMappingForB() {
        var merger=new DiarizationMerger();
        merger.merge(0,List.of(s(0,3000,"x","first speaker"),s(9000,12000,"y","second speaker")),Set.of());
        var result=merger.merge(9000,List.of(s(0,3000,"local_b","second speaker"),s(3000,6000,"session_A","agent returns")),Set.of("session_A","session_B"));
        assertEquals("B",result.get(1).speaker()); assertEquals("A",result.get(2).speaker());
    }
    @Test void createsBWhenModelDistinguishesNewVoiceAgainstOnlyKnownAReference() {
        var merger=new DiarizationMerger();
        var first=merger.merge(0,List.of(s(0,8000,"local_first","お電話ありがとうございます。担当の田中です。")),Set.of());
        assertEquals("A",first.getFirst().speaker());
        var second=merger.merge(9000,List.of(s(0,3000,"new_voice","庫内が冷えていないんです")),Set.of("session_A"));
        assertEquals("B",second.getLast().speaker());
        var third=merger.merge(15000,List.of(s(0,3000,"another_voice","追加の話者です")),Set.of("session_A","session_B"));
        assertEquals("UNKNOWN",third.getLast().speaker());
    }
    @Test void doesNotCreateBFromUnmatchedLabelWithoutModelReferenceOrSharedContext() {
        var merger=new DiarizationMerger();
        merger.merge(0,List.of(s(0,8000,"first","first speaker greeting")),Set.of());
        var result=merger.merge(9000,List.of(s(0,3000,"unmatched","no common audio")),Set.of());
        assertEquals("UNKNOWN",result.getLast().speaker());
    }
    @Test void truncatedOverlapTextStillMapsCustomerAndKeepsEarlierUncoveredUtterances() {
        var merger=new DiarizationMerger();
        merger.merge(0,List.of(s(0,3000,"agent","お電話ありがとうございます"),s(8000,12000,"customer","庫内が全然冷えていないんです")),Set.of());
        var result=merger.merge(10000,List.of(s(0,2000,"new_customer","全然冷えていないんです")),Set.of());
        assertEquals("B",result.get(1).speaker());
        var next=merger.merge(10000,List.of(s(4000,6000,"session_A","別の質問です")),Set.of("session_A"));
        assertTrue(next.stream().anyMatch(segment -> segment.speaker().equals("B") && segment.text().equals("全然冷えていないんです")));
    }
}
