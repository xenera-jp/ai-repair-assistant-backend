package com.aifieldservice.repairassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import com.aifieldservice.repairassistant.integration.qdrant.QdrantGateway;
import com.aifieldservice.repairassistant.service.recording.RecordingService;

@SpringBootTest
@ActiveProfiles("test")
class AiRepairAssistantBackendApplicationTests {

	@MockitoBean
	RecordingService recordingService;

	@MockitoBean
	QdrantGateway qdrantGateway;

	@Test
	void contextLoads() {
	}

}
