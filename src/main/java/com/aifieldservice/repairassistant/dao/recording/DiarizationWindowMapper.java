package com.aifieldservice.repairassistant.dao.recording;

import org.apache.ibatis.annotations.*;

@Mapper
public interface DiarizationWindowMapper {
    @Insert("INSERT INTO recording_diarization_window(window_key,session_key,file_key,start_ms,end_ms,overlap_ms,status,version_no) VALUES(#{id},#{session},#{file},#{start},#{end},#{overlap},'QUEUED',1)")
    void insert(String id, String session, String file, long start, long end, long overlap);
    @Update("UPDATE recording_diarization_window SET status=#{status},attempts=#{attempts},result_json=#{result},error_detail=#{error},version_no=version_no+1 WHERE window_key=#{id}")
    void update(String id, String status, int attempts, String result, String error);
    /** Original model results remain intact; the latest completed window carries the correction audit. */
    @Update("UPDATE recording_diarization_window SET result_json=JSON_SET(COALESCE(result_json,JSON_OBJECT()), '$.identifierProtection', CAST(#{audit} AS JSON)),version_no=version_no+1 WHERE file_key=#{file} AND session_key=#{session} AND status='COMPLETED' ORDER BY start_ms DESC LIMIT 1")
    void storeIdentifierProtection(String session,String file,String audit);
}
