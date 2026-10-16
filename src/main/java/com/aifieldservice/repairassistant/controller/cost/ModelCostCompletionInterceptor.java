package com.aifieldservice.repairassistant.controller.cost;
import com.aifieldservice.repairassistant.service.cost.ModelCostService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
@Component
public class ModelCostCompletionInterceptor implements HandlerInterceptor,WebMvcConfigurer {
    private final ModelCostService costs;
    public ModelCostCompletionInterceptor(ModelCostService costs){this.costs=costs;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/v1/**");}
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error){
        if(error!=null||response.getStatus()>=400||!"POST".equals(request.getMethod()))return;
        String scope=costs.requestScope();if(scope==null)return;
        String path=request.getRequestURI();String function=null;
        if(path.endsWith("/problem-understandings")||path.matches(".*/diagnosis-sessions/[^/]+/rejections"))function="PROBLEM_UNDERSTANDING";
        if(path.endsWith("/diagnosis-sessions")||path.matches(".*/diagnosis-sessions/[^/]+/reanalysis"))function="DIAGNOSIS";
        if(function!=null)try{costs.complete(scope,function);}catch(RuntimeException exception){org.slf4j.LoggerFactory.getLogger(getClass()).error("Unable to publish cost history for {}",scope,exception);}
    }
}
