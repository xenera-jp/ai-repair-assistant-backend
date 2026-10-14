package com.aifieldservice.repairassistant.service.recording;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aifieldservice.repairassistant.dao.knowledge.EquipmentIdentifierMapper;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;
class TranscriptIdentifierProtectorTests {
    @Test void repeatedModelMentionsAndSpokenSuffixKeepSerialUntouched() {
        var target=t("d",36000,47000,"機種がRI-R1SSBで製造番号がRIK298-724です 機種がRIRワンです。");
        var result=state().protect(List.of(target),List.of(t("rt",36000,47000,"機種はRIR1-SSBです。")));
        assertEquals("機種がRIR1-SSBで製造番号がRIK298-724です 機種がRIR1-SSBです。",result.segments().getFirst().text());
        assertEquals(2,result.replacements().size());
    }
    @Test void unlabelledRepeatedSpellingUsesRealtimeModelWithoutChangingSerial() {
        var target=t("d",47000,66000,"RIR-1 r i r1ssp製造番号 rik2987-24ですねありがとうございます。");
        assertEquals("RIR1-SSB製造番号 rik2987-24ですねありがとうございます。",
                state().protect(List.of(target),List.of(t("rt",47000,66000,"RIR1-SSBですね。"))).segments().getFirst().text());
        assertEquals(List.of(target),state().protect(List.of(target),List.of()).segments());
    }
    @Test void repeatedConflictingModelsAreNotPatched() {
        var target=t("d",0,2000,"機種はRIR1です。機種はFH1-AACです。");
        assertEquals(List.of(target),state().protect(List.of(target),List.of(t("rt",0,2000,"機種はRIR1-SSBです。"))).segments());
        var spelling=t("s",0,2000,"RIR-1 r i r9aacです。");
        assertEquals(List.of(spelling),state().protect(List.of(spelling),List.of(t("rt",0,2000,"RIR1-SSBです。"))).segments());
    }
    @Test void spacedRealtimeModelPatchesAcrossDiarizationRows() {
        var targets=List.of(t("d1",0,2000,"機種はRIR1です。"),t("d2",2000,4000,"機種はRIRI-SSBですね。"));
        var result=state().protect(targets,List.of(t("rt",0,4000,"機種はr i r 1 s s bです。")));
        assertEquals("機種はRIR1-SSBです。",result.segments().getFirst().text());
        assertEquals("機種はRIR1-SSBですね。",result.segments().get(1).text());
    }
    @Test void repeatedCodeFieldsUseUniqueRealtimeCodeAndKeepUnits() {
        var result=state().protect(List.of(t("d",0,4000,"エラーコードはE1です。エラーコードE1、温度4℃です。")),
                List.of(t("rt",0,4000,"エラーコードはE4です。")));
        assertEquals("エラーコードはE4です。エラーコードE4、温度4℃です。",result.segments().getFirst().text());
        assertEquals(2,result.replacements().size());
    }
    @Test void englishSpokenDigitModelIsReplacedAsOneSpan() {
        var result=state().protect(List.of(t("d",47000,66000,"r i r one s s b 製造番号rik29874724ですね。")),
                List.of(t("rt",47000,66000,"RIR1-SSBですね。")));
        assertEquals("RIR1-SSB 製造番号rik29874724ですね。",result.segments().getFirst().text());
        assertEquals("機種はRIR1-SSBです。",state().protect(List.of(t("d",0,2000,"機種はRIR1です。")),
                List.of(t("rt",0,2000,"機種はr i r one s s bです。"))).segments().getFirst().text());
    }
    @Test void adjacentRepeatedModelFieldIsCollapsedButRestatementsRemain() {
        var result=state().protect(List.of(t("d",0,2000,"機種がRIR1-SSB 機種がRIR1 SSBで、製造番号がRIK298724です。")),
                List.of(t("rt",0,2000,"機種がRIR1-SSBです。")));
        assertEquals("機種がRIR1-SSBで、製造番号がRIK298724です。",result.segments().getFirst().text());
        var text="機種はRIR1-SSBです。確認します。機種はRIR1-SSBです。";
        assertEquals(text,state().protect(List.of(t("d",0,2000,text)),List.of(t("rt",0,2000,"RIR1-SSBです。"))).segments().getFirst().text());
    }
    @Test void dottedAndSpokenCodesAndUnlabelledBlinkingAnswerAreProtected() {
        var result=state().protect(List.of(t("d",0,12000,"あ、はい。E・1というのが点滅しています 1というのが点滅しています。原因がわからなくて。")),
                List.of(t("rt",0,12000,"E1というのが点滅しています。")));
        assertEquals("あ、はい。E1というのが点滅しています。原因がわからなくて。",result.segments().getFirst().text());
        assertEquals("E1は異常を示します。",state().protect(List.of(t("d",0,2000,"イーチは異常を示します。")),
                List.of(t("rt",0,2000,"E1は異常を示します。"))).segments().getFirst().text());
        assertEquals("エラーコードはE1です。",state().protect(List.of(t("d",0,2000,"エラーコードはE4です。")),
                List.of(t("rt",0,2000,"エラーコードはE・1です。"))).segments().getFirst().text());
    }
    private Transcript t(String id,long a,long b,String text) { return new Transcript(id,a,b,"B",text); }
    private TranscriptIdentifierProtector.ProtectionState state() {
        var mapper=mock(EquipmentIdentifierMapper.class);
        when(mapper.listIdentifiers()).thenReturn(List.of(
                new EquipmentIdentifierMapper.Row("FH1-SSB","[\"E1\",\"E4\",\"CF\"]"),
                new EquipmentIdentifierMapper.Row("FH1-AAC","[]"),
                new EquipmentIdentifierMapper.Row("RIR1-SSB","[\"E1\",\"E4\"]")));
        return new TranscriptIdentifierProtector(mapper).newSession();
    }
    @Test void patchesOnlyModelAndKeepsDiarizedWordingSpeakerAndTimes() {
        var target=t("d",1000,3000,"機種はFH1です。お待ちください。");
        var result=state().protect(List.of(target),List.of(t("rt",0,3500,"機種はFH1-SSBです。別の文章。")));
        assertEquals("機種はFH1-SSBです。お待ちください。",result.segments().getFirst().text());
        assertEquals(target.speaker(),result.segments().getFirst().speaker());
        assertEquals(1000,result.segments().getFirst().startMs()); assertEquals(3000,result.segments().getFirst().endMs());
        assertEquals("FH1",result.replacements().getFirst().originalValue()); assertEquals("rt",result.replacements().getFirst().sourceId());
    }
    @Test void protectsModelAndCodeTogetherWithoutBreakingOffsets() {
        var result=state().protect(List.of(t("d",0,4000,"型式はFH1です。エラーコードはE1です。")),
                List.of(t("rt",0,4000,"型式はFH1-SSBです。エラーコードはE4です。")));
        assertEquals("型式はFH1-SSBです。エラーコードはE4です。",result.segments().getFirst().text());
        assertEquals(2,result.replacements().size());
    }
    @Test void usesEarlierConfirmedModelForCodeInLaterTurn() {
        var result=state().protect(List.of(t("d",10000,12000,"エラーコードはE1です。")),List.of(
                t("model",0,2000,"機種はFH1-SSBです。"),t("code",10000,12000,"エラーコードはE4です。")));
        assertEquals("エラーコードはE4です。",result.segments().getFirst().text());
    }
    @Test void explicitCodeCanBePatchedBeforeModelIsMentioned() {
        var target=t("d",0,2000,"エラーコードはE1です。");
        var result=state().protect(List.of(target),List.of(t("rt",0,2000,"エラーコードはE4です。")));
        assertEquals("エラーコードはE4です。",result.segments().getFirst().text()); assertEquals(1,result.replacements().size());
    }
    @Test void conflictingRealtimeModelsAreSkippedButUniqueCandidateCanProtectMultipleTargets() {
        var target=t("d",0,4000,"機種はFH1です。");
        assertEquals(List.of(target),state().protect(List.of(target),List.of(t("rt",0,4000,"機種はFH1-SSBです。機種はRIR1-SSBです。"))).segments());
        var another=t("other",2000,3000,"機種はFH1です。");
        assertEquals(2,state().protect(List.of(target,another),List.of(t("rt",0,4000,"機種はFH1-SSBです。"))).replacements().size());
    }
    @Test void singleModelFieldPrefersRealtimeAndDisjointAudioIsIgnored() {
        var target=t("d",10000,12000,"機種はFH1-AACです。");
        var source=t("rt",10000,12000,"機種はFH1-SSBです。");
        assertEquals("機種はFH1-SSBです。",state().protect(List.of(target),List.of(source)).segments().getFirst().text());
        target=t("d",10000,12000,"機種はFH1です。");
        assertEquals(List.of(target),state().protect(List.of(target),List.of(t("rt",0,2000,"機種はFH1-SSBです。"))).segments());
    }
    @Test void serialTemperatureNegationAndCorrectionAreNeverPatched() {
        var source=t("rt",0,4000,"機種はFH1-SSBです。エラーコードはE4です。");
        for(String text:List.of("製造番号はFH1です。","温度表示は4℃です。","機種はFH1ではないです。")) {
            var target=t("d",0,4000,text); assertEquals(List.of(target),state().protect(List.of(target),List.of(source)).segments());
        }
        var target=t("d",0,4000,"機種はFH1です。");
        assertEquals(List.of(target),state().protect(List.of(target),List.of(t("rt",0,4000,"機種はFH1-SSBではなくRIR1-SSBです。"))).segments());
    }
    @Test void patchesLetterDigitConfusionAndEnglishTruncatedModel() {
        assertEquals("機種はRIR1-SSBです。",state().protect(List.of(t("d",0,2000,"機種はRIRI-SSBです。")),
                List.of(t("rt",0,2000,"機種はRIR1-SSBです。"))).segments().getFirst().text());
        assertEquals("model FH1-SSB is broken",state().protect(List.of(t("d",0,2000,"model FH1 is broken")),
                List.of(t("rt",0,2000,"model FH1-SSB is broken"))).segments().getFirst().text());
    }
    @Test void repeatedProtectionStartsFromOriginalAndDoesNotCascadeEdits() {
        var state=state(); var target=t("d",0,2000,"機種はFH1です。"); var rt=List.of(t("rt",0,2000,"機種はFH1-SSBです。"));
        var result=state.protect(List.of(target),rt);
        assertEquals(result,state.protect(List.of(target),rt));
        assertEquals(List.of(target),state.protect(List.of(target),List.of()).segments());
    }
    @Test void protectsNormalizedModelAndSpokenCodeFormatting() {
        var result=state().protect(List.of(t("d",0,4000,"型式はFH1 SSBです。エラーコードはイー四です。")),
                List.of(t("rt",0,4000,"型式はFH1-SSBです。エラーコードはE4です。")));
        assertEquals("型式はFH1-SSBです。エラーコードはE4です。",result.segments().getFirst().text());
        assertEquals(2,result.replacements().size());
    }
    @Test void conflictingCodeCandidatesAndWrongModelCodeDoNotOverride() {
        var target=t("d",4000,6000,"エラーコードはE1です。");
        var result=state().protect(List.of(target),List.of(t("model",0,2000,"機種はFH1-SSBです。"),
                t("rt",4000,6000,"エラーコードはE4です。エラーコードはE1です。")));
        assertEquals(List.of(target),result.segments());
        result=state().protect(List.of(target),List.of(t("model",0,2000,"機種はRIR1-SSBです。"),t("rt",4000,6000,"エラーコードはCFです。")));
        assertEquals(List.of(target),result.segments());
    }
    @Test void standaloneCodeAnswerUsesRealtimeModelDespiteDiarizedModelConflict() {
        var target=t("d",4000,6000,"E1です。");
        var rt=List.of(t("model",0,2000,"機種はFH1-SSBです。"),t("rt",4000,6000,"E4です。"));
        assertEquals("E4です。",state().protect(List.of(target),rt).segments().getFirst().text());
        var originals=List.of(t("model-d",0,2000,"機種はFH1-AACです。"),target);
        assertEquals("機種はFH1-SSBです。",state().protect(originals,rt).segments().getFirst().text());
        assertEquals("E4です。",state().protect(originals,rt).segments().get(1).text());
    }
    @Test void singleValidModelUsesRealtimeAndKeepsAudit() {
        var result=state().protect(List.of(t("d",0,2000,"機種はFH1-AACです。")),
                List.of(t("rt",0,2000,"機種はFH1-SSBです。")));
        assertEquals(1,result.replacements().size()); assertEquals("FH1-SSB",result.candidates().getFirst().value());
        assertEquals("機種はFH1-SSBです。",result.segments().getFirst().text());
    }
}
