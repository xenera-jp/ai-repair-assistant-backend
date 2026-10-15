package com.aifieldservice.repairassistant.service.recording;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.aifieldservice.repairassistant.dao.recording.*;
import com.aifieldservice.repairassistant.domain.recording.model.*;
import com.aifieldservice.repairassistant.integration.moss.MossRecordingGateway;
import com.aifieldservice.repairassistant.integration.openai.OpenAiRecordingGateway.Transcript;

class MossPlaybackServiceTests {
    private RecordingService recordings;
    private RecordingMapper files;
    private MossRecordingMapper mapper;
    private MossRecordingGateway gateway;
    private MossPlaybackService service;
    private final Map<String,RecordingRows.File> uploads=new ConcurrentHashMap<>();
    private final Map<String,List<Transcript>> visible=new ConcurrentHashMap<>();
    private final CountDownLatch finish=new CountDownLatch(1);
    @BeforeEach void setup() throws Exception {
        var data=new JdbcDataSource(); data.setURL("jdbc:h2:mem:moss_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var connection=data.getConnection();var sql=connection.createStatement();
                var migration=getClass().getResourceAsStream("/db/migration/V21__add_moss_full_recording_cache.sql")) {
            sql.execute(new String(migration.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var config=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(MossRecordingMapper.class);
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(MossRecordingMapper.class);
        recordings=mock(RecordingService.class); files=mock(RecordingMapper.class); gateway=mock(MossRecordingGateway.class);
        when(recordings.getFile(anyString())).thenAnswer(i -> uploads.get(i.getArgument(0)));
        when(files.findFileIncludingDeleted(anyString())).thenAnswer(i -> uploads.get(i.getArgument(0)));
        when(files.findFileIncludingDeletedForUpdate(anyString())).thenAnswer(i -> uploads.get(i.getArgument(0)));
        when(recordings.resolveContent(any())).thenReturn(Path.of("source.mp3"));
        var batch=new RecordingRows.Batch(1,"batch","ja-JP","TRANSCRIBING",0,null,LocalDateTime.now(),false,null);
        when(files.findBatchById(anyLong())).thenReturn(batch);
        when(recordings.getBatch(anyString())).thenReturn(new RecordingViews.Batch("batch","ja-JP","TRANSCRIBING",0,null,List.of(),List.of(),LocalDateTime.now()));
        doAnswer(i -> { visible.put(i.getArgument(0),List.copyOf(i.getArgument(1))); return null; }).when(recordings).replaceConfirmed(anyString(),anyList());
        doAnswer(i -> { finish.countDown(); return null; }).when(recordings).finishRealtime(anyString());
        upload("first","call.mp3"); upload("second","call.mp3");
        service=new MossPlaybackService(recordings,files,mapper,gateway,new DataSourceTransactionManager(data));
    }
    private void upload(String key,String name) {
        uploads.put(key,new RecordingRows.File(key.hashCode(),key,1,0,name,"unused","audio/mpeg",100,"sha","REALTIME_TRANSCRIBING",null,null,false,null));
    }
    private Transcript segment(int index,long start,long end,String speaker) { return new Transcript("s"+index,start,end,speaker,"text "+index); }
    private void awaitComplete(String key) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!"COMPLETED".equals(mapper.job(key).status()) && System.nanoTime()<deadline) Thread.sleep(10);
        assertEquals("COMPLETED",mapper.job(key).status());
    }
    @AfterEach void cleanup() { service.close(); }

    @Test void endedPlaybackKeepsCutoffWhileFullTranscriptionContinuesAndSameNameReusesCache() throws Exception {
        var emitted=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(gateway.transcribe(any(),eq("call.mp3"),any())).thenAnswer(i -> {
            Consumer<Transcript> output=i.getArgument(2);
            output.accept(segment(1,0,1000,"S01")); output.accept(segment(2,1000,3000,"S02")); emitted.countDown();
            assertTrue(release.await(5,TimeUnit.SECONDS));
            output.accept(segment(3,3000,5000,"S01")); return List.of();
        });
        try {
            service.start("first"); assertTrue(emitted.await(5,TimeUnit.SECONDS));
            assertTrue(visible.get("first").isEmpty());
            service.progress("first",1500,false);
            assertEquals(List.of("text 1"),visible.get("first").stream().map(Transcript::text).toList());
            service.progress("first",1500,true);
            assertEquals("RUNNING",mapper.job(MossPlaybackService.cacheKey("call.mp3")).status());
            verify(recordings,never()).finishRealtime(anyString());
            release.countDown(); assertTrue(finish.await(5,TimeUnit.SECONDS));
            assertEquals(3,service.readSegments(mapper.job(MossPlaybackService.cacheKey("call.mp3")).resultJson()).size());
            service.progress("first",99999,false);
            assertEquals(1500,mapper.playback("first").positionMs()); assertEquals(1,visible.get("first").size());
            service.start("second"); service.progress("second",4000,true);
            assertEquals(2,visible.get("second").size());
            assertEquals(List.of("A","B"),visible.get("second").stream().map(Transcript::speaker).toList());
            verify(gateway,times(1)).transcribe(any(),anyString(),any()); verify(gateway,times(1)).validate();
        } finally { release.countDown(); }
    }
    @Test void sameNameWhileRunningSharesOneJobAndKeepsSeparatePlaybackPositions() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(gateway.transcribe(any(),anyString(),any())).thenAnswer(i -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            Consumer<Transcript> output=i.getArgument(2); output.accept(segment(1,0,1000,"S01")); return List.of();
        });
        try {
            service.start("first"); assertTrue(entered.await(5,TimeUnit.SECONDS)); service.start("second");
            assertEquals(mapper.playback("first").jobKey(),mapper.playback("second").jobKey());
            service.progress("first",500,true); service.progress("second",1500,true); release.countDown();
            awaitComplete(MossPlaybackService.cacheKey("call.mp3"));
            verify(recordings,timeout(3000)).finishRealtime("second");
            assertTrue(visible.get("first").isEmpty()); assertEquals(1,visible.get("second").size());
            verify(gateway,times(1)).transcribe(any(),anyString(),any());
        } finally { release.countDown(); }
    }
    @Test void deletingPlaybackDoesNotCancelCacheWriteAndNextUploadCanReuseIt() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(gateway.transcribe(any(),anyString(),any())).thenAnswer(i -> {
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            Consumer<Transcript> output=i.getArgument(2); output.accept(segment(1,0,1000,"S01")); return List.of();
        });
        try {
            service.start("first"); assertTrue(entered.await(5,TimeUnit.SECONDS));
            var file=uploads.get("first"); uploads.put("first",new RecordingRows.File(file.id(),file.fileKey(),1,0,file.originalName(),file.storageKey(),file.contentType(),100,"sha","DELETED",null,null,true,LocalDateTime.now()));
            release.countDown(); awaitComplete(MossPlaybackService.cacheKey("call.mp3"));
            service.start("second"); service.progress("second",1000,true);
            assertEquals(1,visible.get("second").size()); verify(gateway,times(1)).transcribe(any(),anyString(),any());
        } finally { release.countDown(); }
    }
    @Test void cacheIsFilenameBasedAndClaimAndCutoffAreAtomic() {
        String key=MossPlaybackService.cacheKey("call.mp3");
        assertNotEquals(key,MossPlaybackService.cacheKey("other.mp3"));
        assertEquals(1,mapper.insertJob(key,"call.mp3","first")); assertEquals(0,mapper.insertJob(key,"call.mp3","second"));
        assertEquals(1,mapper.claim(key)); assertEquals(0,mapper.claim(key));
        mapper.insertPlayback("first",key); mapper.progress("first",1000,true);
        assertEquals(0,mapper.progress("first",5000,false)); assertEquals(1000,mapper.playback("first").positionMs());
    }
}
