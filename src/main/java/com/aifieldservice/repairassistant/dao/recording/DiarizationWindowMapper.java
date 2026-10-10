package com.aifieldservice.repairassistant.dao.recording;

import org.apache.ibatis.annotations.*;

@Mapper
public interface DiarizationWindowMapper {
    @Insert("INSERT INTO recording_diarization_window(window_key,session_key,file_key,start_ms,end_ms,overlap_ms,status,version_no) VALUES(#{id},#{session},#{file},#{start},#{end},#{overlap},'QUEUED',1)")
    void insert(String id, String session, String file, long start, long end, long overlap);
    @Update("UPDATE recording_diarization_window SET status=#{status},attempts=#{attempts},result_json=#{result},error_detail=#{error},version_no=version_no+1 WHERE window_key=#{id}")
    void update(String id, String status, int attempts, String result, String error);
}
