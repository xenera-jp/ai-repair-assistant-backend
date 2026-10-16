package com.aifieldservice.repairassistant.service.cost;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
class ModelCostServiceTests {
    private ModelCostService service;
    private final ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:cost_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V22__add_model_call_cost.sql"),new ClassPathResource("db/migration/V23__add_model_cost_stage.sql")).execute(source);
        var pricing=new ModelPricing();
        pricing.getModels().put("text",new ModelPricing.Rate(new BigDecimal("1"),null,null,new BigDecimal("2"),null,null));
        pricing.getModels().put("live",new ModelPricing.Rate(null,null,null,null,new BigDecimal("0.017"),null));
        service=new ModelCostService(new JdbcTemplate(source),pricing,json);
    }
    @Test void aggregatesSeparatelyDeduplicatesAndIsolatesScopes(){
        var response=json.readTree("{\"usage\":{\"input_tokens\":1000000,\"output_tokens\":1000000}}");
        service.record("a","DIARIZATION","text",response,null,"d");
        service.record("a","DIARIZATION","text",response,null,"d");
        service.record("a","ROLE_RECOGNITION","text",response,null,"r");
        service.record("a","DIAGNOSIS","text",response,null,"x");
        service.record("b","DIAGNOSIS","text",response,null,"y");
        var summary=service.summary("a");
        assertEquals(3,summary.calls().size());
        assertEquals(0,new BigDecimal("9").compareTo(summary.totalUsd()));
        assertEquals(0,new BigDecimal("3").compareTo(summary.withoutSpeakerProcessingUsd()));
        assertEquals(0,new BigDecimal("3").compareTo(summary.roleRecognitionUsd()));
        assertEquals(0,new BigDecimal("3").compareTo(summary.diarizationUsd()));
        assertTrue(summary.calls().getFirst().pricingJson().contains("input"));
        assertTrue(service.history().isEmpty()); // In-flight calls are not published.
        service.complete("a","DIAGNOSIS");service.complete("b","DIAGNOSIS");
        var history=service.history();
        assertEquals(4,history.size());
        assertEquals(2,history.stream().filter(e->e.functionCode().equals("TOTAL")).count());
        assertTrue(history.stream().allMatch(e->e.costUsd().compareTo(new BigDecimal("3"))==0));
    }
    @Test void incompleteCallsRemainVisibleAndEstimatesAreFlagged(){
        service.record("a","DIAGNOSIS","text",null,null,"missing");
        service.record("a","REALTIME_TRANSCRIPTION","live",null,new BigDecimal("60"),"estimate");
        var summary=service.summary("a");assertEquals(1,summary.unpricedCalls());assertEquals(1,summary.estimatedCalls());
        assertEquals(0,new BigDecimal("0.017").compareTo(summary.totalUsd()));
    }

    @Test void configurationBindsExactModelNamesAndDecimalRates() throws Exception {
        var sources=new org.springframework.boot.env.YamlPropertySourceLoader().load("pricing",new ClassPathResource("application.yml"));
        var environment=new org.springframework.core.env.StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);
        var pricing=org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind("repair-assistant.model-pricing",org.springframework.boot.context.properties.bind.Bindable.of(ModelPricing.class)).get();
        assertEquals(new BigDecimal("0.017"),pricing.getModels().get("gpt-live-transcribe").perMinute());
        assertEquals(new BigDecimal("0.25"),pricing.getModels().get("gpt-5-mini").input());
        assertEquals(new BigDecimal("0.006"),pricing.getModels().get("gpt-4o-transcribe-diarize").estimatedPerMinute());
    }
    @Test void chargesSurviveCallerRollback(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:rollback_"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V22__add_model_call_cost.sql"),new ClassPathResource("db/migration/V23__add_model_cost_stage.sql")).execute(source);
        var manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var target=new ModelCostService(new JdbcTemplate(source),new ModelPricing(),json);
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        factory.addAdvice(interceptor);
        var proxy=(ModelCostService)factory.getProxy();
        new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{
            proxy.record("a","DIAGNOSIS","unknown",null,null);status.setRollbackOnly();return null;
        });
        assertEquals(1,target.summary("a").calls().size());
    }

    @Test void publishesCompletedFunctionsAndWholeFlowTotalWithoutCountingInFlightCalls(){
        var response=json.readTree("{\"usage\":{\"input_tokens\":1000000,\"output_tokens\":1000000}}");
        service.record("flow","INFORMATION_EXTRACTION","text",response,null,"extract");
        service.record("flow","IDENTIFIER_MATCHING","text",response,null,"match");
        service.complete("flow","DEVICE_EXTRACTION");
        service.complete("flow","PROBLEM_UNDERSTANDING"); // Rule-only operation: zero is still a completed step.
        assertEquals(2,service.history().size());
        assertFalse(service.history().stream().anyMatch(e->e.functionCode().equals("TOTAL")));
        service.record("flow","DIAGNOSIS","text",response,null,"diagnose");
        assertEquals(2,service.history().size());
        service.complete("flow","DIAGNOSIS");
        var total=service.history().stream().filter(e->e.functionCode().equals("TOTAL")).findFirst().orElseThrow();
        assertEquals(0,new BigDecimal("9").compareTo(total.costUsd()));
        service.record("flow","DIAGNOSIS","text",response,null,"retry");
        assertEquals(0,new BigDecimal("9").compareTo(service.history().getLast().costUsd()));
        service.complete("flow","DIAGNOSIS");
        assertEquals(0,new BigDecimal("12").compareTo(service.history().getLast().costUsd()));
    }
}
