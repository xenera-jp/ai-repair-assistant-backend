package com.aifieldservice.repairassistant.service.recording;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import com.aifieldservice.repairassistant.config.RepairAssistantProperties;
import com.aifieldservice.repairassistant.dao.recording.RecordingMapper;
import com.aifieldservice.repairassistant.domain.recording.model.RecordingRows;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway;
import com.aifieldservice.repairassistant.service.recording.impl.RecordingServiceImpl;

class RecordingManualExtractionTests {
    @TempDir Path directory;
    @Test void staleConversationVersionCannotClaimExtraction() {
        var mapper=mock(RecordingMapper.class);
        var batch=new RecordingRows.Batch(1,"batch","ja-JP","TRANSCRIBED",0,null,LocalDateTime.now(),false,null);
        when(mapper.findBatch("batch")).thenReturn(batch);
        when(mapper.findBatchByIdForUpdate(1)).thenReturn(batch);
        var ai=mock(OpenAiRecordingGateway.class);
        var props=new RepairAssistantProperties(null,null,null,null,new RepairAssistantProperties.Recording(directory.toString(),100,1,1,"model",0.75,1,15,30),null);
        var service=new RecordingServiceImpl(mapper,props,ai,mock(EquipmentIdentifierMatchingService.class),mock(RecordingTranscriptionStream.class),mock(PlatformTransactionManager.class));
        try {
            assertThrows(ResponseStatusException.class,() -> service.retryExtraction("batch","stale-version"));
            verify(mapper,never()).claimExtraction(anyLong()); verifyNoInteractions(ai);
        } finally {service.close();}
    }
    @Test void repeatedConfirmedSnapshotPreservesExistingEvidenceIds() {
        var mapper=mock(RecordingMapper.class);
        var file=new RecordingRows.File(1,"file",1,0,"call.wav","unused","audio/wav",100,"sha","REALTIME_TRANSCRIBING",null,null,false,null);
        var batch=new RecordingRows.Batch(1,"batch","ja-JP","TRANSCRIBING",0,null,LocalDateTime.now(),false,null);
        when(mapper.findBatchById(1)).thenReturn(batch);
        when(mapper.findBatch("batch")).thenReturn(batch);
        when(mapper.findFileIncludingDeletedForUpdate("file")).thenReturn(file);
        when(mapper.listSegments(1)).thenReturn(List.of(new RecordingRows.Segment(1,"stable-evidence",1,0,"A",null,null,"NONE",0,1000,"text","provider")));
        var props=new RepairAssistantProperties(null,null,null,null,new RepairAssistantProperties.Recording(directory.toString(),100,1,1,"model",0.75,1,15,30),null);
        var service=new RecordingServiceImpl(mapper,props,mock(OpenAiRecordingGateway.class),mock(EquipmentIdentifierMatchingService.class),mock(RecordingTranscriptionStream.class),mock(PlatformTransactionManager.class));
        try {
            var text=List.of(new OpenAiRecordingGateway.Transcript("provider",0,1000,"A","text"));
            service.replaceConfirmed("file",text); service.replaceConfirmed("file",text);
            verify(mapper,never()).insertSegment(anyString(),anyLong(),anyInt(),anyString(),anyLong(),anyLong(),anyString(),anyString(),anyBoolean());
            verify(mapper,never()).retireSegment(anyLong());
        } finally {service.close();}
    }
    @Test void completedTranscriptionRefreshDoesNotInvokeExtraction() throws Exception {
        var mapper = mock(RecordingMapper.class);
        var ai = mock(OpenAiRecordingGateway.class);
        var batch = new RecordingRows.Batch(1,"batch","ja-JP","TRANSCRIBING",0,null,LocalDateTime.now(),false,null);
        when(mapper.findBatchById(1)).thenReturn(batch);
        when(mapper.listFiles(1)).thenReturn(List.of(new RecordingRows.File(1,"file",1,0,"call.wav","unused","audio/wav",100,"sha","COMPLETED",null,null,false,null)));
        var recording = new RepairAssistantProperties.Recording(directory.toString(),100,1,1,"model",0.75,1,15,30);
        var props = new RepairAssistantProperties(null,null,null,null,recording,null);
        var service = new RecordingServiceImpl(mapper,props,ai,mock(EquipmentIdentifierMatchingService.class),mock(RecordingTranscriptionStream.class),mock(PlatformTransactionManager.class));
        try {
            var refresh = RecordingServiceImpl.class.getDeclaredMethod("refreshBatch",long.class); refresh.setAccessible(true); refresh.invoke(service,1L);
            verify(mapper).updateBatchStatus(1,"TRANSCRIBED",null);
            verifyNoInteractions(ai);
            verify(mapper,never()).claimExtraction(anyLong());
        } finally { service.close(); }
    }
    @Test void roleInferenceAndConcurrentExtractionCannotBeBypassed() {
        var mapper = mock(RecordingMapper.class);
        var batch = new RecordingRows.Batch(1,"batch","ja-JP","ROLE_INFERENCE",0,null,LocalDateTime.now(),false,null);
        when(mapper.findBatch("batch")).thenReturn(batch);
        when(mapper.listBatchSegments(1)).thenReturn(List.of(new RecordingRows.Segment(1,"segment",1,0,"A",null,null,"NONE",0,1000,"text","item")));
        when(mapper.claimExtraction(1)).thenReturn(0);
        var props = new RepairAssistantProperties(null,null,null,null,new RepairAssistantProperties.Recording(directory.toString(),100,1,1,"model",0.75,1,15,30),null);
        var ai = mock(OpenAiRecordingGateway.class);
        var service = new RecordingServiceImpl(mapper,props,ai,mock(EquipmentIdentifierMatchingService.class),mock(RecordingTranscriptionStream.class),mock(PlatformTransactionManager.class));
        try { assertThrows(ResponseStatusException.class,()->service.retryExtraction("batch")); verifyNoInteractions(ai); }
        finally { service.close(); }
    }

    @Test void completedRealtimeRetainsExistingRoleModelThresholdAndManualEntry() {
        var mapper = mock(RecordingMapper.class);
        var file = new RecordingRows.File(1,"file",1,0,"call.wav","unused","audio/wav",100,"sha","ROLE_INFERENCE",null,null,false,null);
        var batch = new RecordingRows.Batch(1,"batch","ja-JP","ROLE_INFERENCE",0,null,LocalDateTime.now(),false,null);
        when(mapper.findFile("file")).thenReturn(file);
        when(mapper.findFileIncludingDeletedForUpdate("file")).thenReturn(file);
        when(mapper.findBatchById(1)).thenReturn(batch);
        when(mapper.findBatch("batch")).thenReturn(batch);
        when(mapper.listFiles(1)).thenReturn(List.of(file));
        when(mapper.isRealtime(1)).thenReturn(true);
        var a = new RecordingRows.Segment(1,"a",1,0,"A",null,null,"NONE",0,1000,"担当者です","one");
        var b = new RecordingRows.Segment(2,"b",1,1,"B",null,null,"NONE",1000,2000,"故障しました","two");
        var unknown = new RecordingRows.Segment(3,"u",1,2,"UNKNOWN",null,null,"NONE",2000,2100,"はい","three");
        when(mapper.listSegments(1)).thenReturn(List.of(a,b,unknown));
        var ai = mock(OpenAiRecordingGateway.class);
        when(ai.inferRoles(List.of(a,b),"ja-JP")).thenReturn(List.of(
                new OpenAiRecordingGateway.SpeakerRole("A","CUSTOMER_SERVICE",0.95),
                new OpenAiRecordingGateway.SpeakerRole("B","CUSTOMER",0.60)));
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        var props = new RepairAssistantProperties(null,null,null,null,new RepairAssistantProperties.Recording(directory.toString(),100,1,1,"realtime-model",0.75,1,15,30),null);
        var service = new RecordingServiceImpl(mapper,props,ai,mock(EquipmentIdentifierMatchingService.class),mock(RecordingTranscriptionStream.class),tx);
        try {
            service.finishRealtime("file");
            verify(ai,timeout(2000)).inferRoles(List.of(a,b),"ja-JP");
            verify(mapper,timeout(2000)).updateSpeakerRole(1,"A","CUSTOMER_SERVICE",0.95,"MODEL");
            verify(mapper,timeout(2000)).updateSpeakerRole(1,"B","UNKNOWN",0.60,"MODEL");
            verify(ai,never()).extract(anyList(),anyString());
        } finally {service.close();}
    }
}
