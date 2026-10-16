package com.aifieldservice.repairassistant.controller.cost;
import com.aifieldservice.repairassistant.service.cost.ModelCostService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@RestController
@RequestMapping("/api/v1/model-costs")
public class ModelCostController {
    private final ModelCostService costs;
    public ModelCostController(ModelCostService costs){this.costs=costs;}
    @GetMapping("/history")
    public java.util.List<ModelCostService.HistoryEntry> history(){return costs.history();}
    @GetMapping("/{scope}")
    public ModelCostService.Summary get(@PathVariable String scope){
        if(!scope.matches("[A-Za-z0-9_-]{1,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return costs.summary(scope);
    }
}
