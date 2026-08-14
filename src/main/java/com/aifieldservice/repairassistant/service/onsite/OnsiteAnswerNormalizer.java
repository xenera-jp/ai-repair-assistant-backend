package com.aifieldservice.repairassistant.service.onsite;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.aifieldservice.repairassistant.domain.diagnosis.model.*;
import com.aifieldservice.repairassistant.domain.onsite.command.OnsiteQuestionResponseRequest;
import com.aifieldservice.repairassistant.domain.onsite.model.OnsiteAnswer;

/** Converts the supported onsite input controls into one stable domain answer. */
@Component
public class OnsiteAnswerNormalizer {

    public OnsiteAnswer normalize(
            OnsiteQuestion question, OnsiteQuestionResponseRequest request, boolean japanese) {
        if (request == null || request.responseType() == null) {
            throw badRequest("请选择或输入现场确认结果。");
        }
        String responseType = request.responseType().strip().toUpperCase(Locale.ROOT);
        String value;
        String label;
        switch (responseType) {
            case "OPTION" -> {
                String selectedCode = strip(request.selectedOptionCode());
                QuestionOption option = question.options().stream()
                        .filter(item -> item.code().equals(selectedCode))
                        .findFirst()
                        .orElseThrow(() -> badRequest("请选择有效的现场状态。"));
                value = option.code();
                label = option.label();
            }
            case "MEASUREMENT" -> {
                if (request.valueNumber() == null) throw badRequest("请输入测量值。");
                String unit = strip(request.unit());
                value = request.valueNumber() + (unit.isBlank()
                        ? question.unit() == null ? "" : question.unit() : unit);
                label = (japanese ? "測定値 " : "测量值 ") + value;
            }
            case "OTHER_TEXT" -> {
                value = strip(request.rawText());
                if (value.isBlank()) throw badRequest("请输入现场观察内容。");
                label = truncate(value, 80);
            }
            case "UNAVAILABLE" -> { value = "UNAVAILABLE"; label = japanese ? "現場では確認できない" : "现场暂时无法确认"; }
            case "SKIPPED" -> { value = "SKIPPED"; label = japanese ? "今回はスキップ" : "本轮已跳过"; }
            default -> throw badRequest("不支持的现场回答类型。");
        }
        return new OnsiteAnswer(question.signalCode(), question.candidateCode(), responseType,
                value, label, question.round(), question.id());
    }

    private String strip(String value) { return value == null ? "" : value.strip(); }
    private String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max - 1) + "…"; }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
