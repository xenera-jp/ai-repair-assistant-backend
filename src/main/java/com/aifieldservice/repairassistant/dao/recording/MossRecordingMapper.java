package com.aifieldservice.repairassistant.dao.recording;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MossRecordingMapper {
    record Job(String jobKey,String originalName,String sourceFileKey,String status,String resultJson,String errorDetail) {}
    record Playback(String fileKey,String jobKey,long positionMs,boolean ended,boolean finalized) {}
    @Select("SELECT job_key,original_name,source_file_key,status,result_json,error_detail FROM recording_moss_transcription WHERE job_key=#{key}")
    Job job(String key);
    @Insert("INSERT IGNORE INTO recording_moss_transcription(job_key,original_name,source_file_key,status,result_json) VALUES(#{key},#{name},#{file},'PENDING','[]')")
    int insertJob(@Param("key") String key,@Param("name") String name,@Param("file") String file);
    @Update("UPDATE recording_moss_transcription SET status='RUNNING',result_json='[]',error_detail=NULL WHERE job_key=#{key} AND status='PENDING'")
    int claim(String key);
    @Update("UPDATE recording_moss_transcription SET status='PENDING',source_file_key=#{file},error_detail=NULL WHERE job_key=#{key} AND status='FAILED'")
    int retry(@Param("key") String key,@Param("file") String file);
    @Update("UPDATE recording_moss_transcription SET result_json=#{json},status=#{status},error_detail=#{error} WHERE job_key=#{key} AND status='RUNNING'")
    int save(@Param("key") String key,@Param("json") String json,@Param("status") String status,@Param("error") String error);
    @Select("SELECT file_key,job_key,position_ms,ended,finalized FROM recording_moss_playback WHERE file_key=#{key}")
    Playback playback(String key);
    @Select("SELECT file_key,job_key,position_ms,ended,finalized FROM recording_moss_playback WHERE file_key=#{key} FOR UPDATE")
    Playback lockPlayback(String key);
    @Insert("INSERT INTO recording_moss_playback(file_key,job_key) VALUES(#{file},#{job})")
    int insertPlayback(@Param("file") String file,@Param("job") String job);
    @Update("UPDATE recording_moss_playback SET position_ms=#{position},ended=#{ended} WHERE file_key=#{file} AND ended=FALSE")
    int progress(@Param("file") String file,@Param("position") long position,@Param("ended") boolean ended);
    @Select("SELECT file_key,job_key,position_ms,ended,finalized FROM recording_moss_playback WHERE job_key=#{key} AND finalized=FALSE")
    List<Playback> subscribers(String key);
    @Update("UPDATE recording_moss_playback SET finalized=TRUE WHERE file_key=#{key} AND ended=TRUE AND finalized=FALSE")
    int finalizePlayback(String key);
    @Update("UPDATE recording_moss_transcription SET status='PENDING' WHERE status='RUNNING'")
    int recoverJobs();
    @Select("SELECT job_key,original_name,source_file_key,status,result_json,error_detail FROM recording_moss_transcription WHERE status='PENDING'")
    List<Job> pendingJobs();
    @Select("SELECT p.file_key,p.job_key,p.position_ms,p.ended,p.finalized FROM recording_moss_playback p JOIN recording_file f ON f.file_key=p.file_key WHERE f.deleted=FALSE AND f.status IN ('REALTIME_TRANSCRIBING','ROLE_INFERENCE','DIARIZATION_FAILED')")
    List<Playback> recoverablePlaybacks();
    @Update("UPDATE recording_moss_playback SET finalized=FALSE WHERE file_key=#{key}")
    int recoverPlayback(String key);
}
