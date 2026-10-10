package com.aifieldservice.repairassistant.dao.recording;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

class RecordingRealtimeMapperTests {
    @Test void migrationAndRealMapperKeepModeAndGuardExtractionAdmission() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:realtime_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = data.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE recording_batch(id BIGINT PRIMARY KEY,status VARCHAR(32),deleted BOOLEAN DEFAULT FALSE,extraction_error VARCHAR(1000))");
            sql.execute("CREATE TABLE recording_file(id BIGINT PRIMARY KEY,file_key VARCHAR(64),batch_id BIGINT,display_order INT,original_name VARCHAR(512),storage_key VARCHAR(512),content_type VARCHAR(128),size_bytes BIGINT,sha256 VARCHAR(64),status VARCHAR(32),error_code VARCHAR(64),error_detail VARCHAR(1000),deleted BOOLEAN DEFAULT FALSE,deleted_at TIMESTAMP)");
            try (var migration = getClass().getResourceAsStream("/db/migration/V19__add_recording_realtime_mode.sql")) {
                sql.execute(new String(migration.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            sql.execute("INSERT INTO recording_batch(id,status) VALUES(1,'PREPARED')");
            sql.execute("INSERT INTO recording_file(id,file_key,batch_id,display_order,original_name,storage_key,content_type,size_bytes,status) VALUES(1,'file',1,0,'call.wav','test','audio/wav',100,'PREPARED')");
        }
        var config = new Configuration(new Environment("test",new JdbcTransactionFactory(),data));
        config.setMapUnderscoreToCamelCase(true);
        try (var xml = getClass().getResourceAsStream("/mapper/recording/RecordingMapper.xml")) {
            new XMLMapperBuilder(xml,config,"recording/RecordingMapper.xml",config.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(RecordingMapper.class);
            assertFalse(mapper.isRealtime(1));
            mapper.markRealtime(1); assertTrue(mapper.isRealtime(1));
            assertEquals("PREPARED",mapper.findFile("file").status());
            assertEquals(0,mapper.claimExtraction(1));
            mapper.updateBatchStatus(1,"ROLE_INFERENCE",null); assertEquals(0,mapper.claimExtraction(1));
            mapper.updateBatchStatus(1,"DIARIZATION_FAILED",null); assertEquals(0,mapper.claimExtraction(1));
            mapper.updateBatchStatus(1,"TRANSCRIBED",null); assertEquals(1,mapper.claimExtraction(1));
            assertEquals(0,mapper.claimExtraction(1));
            mapper.updateBatchStatus(1,"EXTRACTION_FAILED","failure"); assertEquals(1,mapper.claimExtraction(1));
        }
    }
    @Test void diarizationMigrationAndMapperPersistStableWindowVersion() throws Exception {
        var data=new JdbcDataSource(); data.setURL("jdbc:h2:mem:diarization_mapper;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var connection=data.getConnection();var sql=connection.createStatement();var migration=getClass().getResourceAsStream("/db/migration/V20__add_recording_diarization_windows.sql")) {
            sql.execute(new String(migration.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),data));
        config.addMapper(DiarizationWindowMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper=session.getMapper(DiarizationWindowMapper.class);
            mapper.insert("window","session","file",9000,24000,3000);
            mapper.update("window","FAILED",3,null,"failed");
            mapper.update("window","COMPLETED",1,"[]",null);
            try(var sql=session.getConnection().createStatement();var row=sql.executeQuery("SELECT status,version_no,overlap_ms FROM recording_diarization_window WHERE window_key='window'")) {
                assertTrue(row.next()); assertEquals("COMPLETED",row.getString(1)); assertEquals(3,row.getInt(2)); assertEquals(3000,row.getLong(3));
            }
        }
    }
}
