package com.aifieldservice.repairassistant.service.knowledge.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.ManualDocument;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.ManualUnit;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.PageText;
import com.aifieldservice.repairassistant.domain.knowledge.model.ServiceManualKnowledge.SourceRegion;

/**
 * 表格型服务手册的公共确定性解析骨架。
 *
 * <p>PDFBox 只负责结构和坐标提取；具体产品 Profile 仍需声明身份标记、章节标记、
 * 原文事实和经审查的中日文业务解释。任何关键标记缺失都会阻止发布。
 */
public abstract class ReviewedServiceManualParser implements ServiceManualParser {

    private static final Pattern PRINTED_PAGE = Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*$");

    protected abstract String model();

    protected abstract String parserVersion();

    protected abstract List<String> identityMarkers();

    protected abstract List<UnitSpec> unitSpecs();

    @Override
    public boolean supports(Path path) {
        String name = path.getFileName().toString().toUpperCase(Locale.ROOT);
        return name.contains(model())
                && name.contains("SERVICE MANUAL")
                && name.endsWith(".PDF");
    }

    @Override
    public ManualDocument parse(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            List<PageText> pages = extractPages(document);
            requireDocumentIdentity(pages);
            List<ManualUnit> units = unitSpecs().stream()
                    .map(spec -> createUnit(document, pages, spec))
                    .toList();
            return new ManualDocument(
                    path.getFileName().toString(),
                    "SERVICE_MANUAL:" + model(),
                    parserVersion(),
                    "Hoshizaki",
                    model(),
                    document.getNumberOfPages(),
                    units);
        }
    }

    private ManualUnit createUnit(
            PDDocument document,
            List<PageText> pages,
            UnitSpec spec) {
        PageText page = findPage(pages, spec.pageMarkers());
        requireText(page, spec.requiredFacts());
        try {
            SourceRegion region = locateSourceRegion(
                    document,
                    page.pdfPageIndex(),
                    spec.sourceAnchor());
            return new ManualUnit(
                    spec.unitKey(),
                    spec.unitType(),
                    spec.problemTypeCode(),
                    spec.errorCode(),
                    spec.title(),
                    spec.titleJa(),
                    spec.summary(),
                    spec.summaryJa(),
                    spec.sourceQuote(),
                    spec.sourceAnchor(),
                    region,
                    spec.actionSteps(),
                    spec.actionStepsJa(),
                    spec.safetyWarnings(),
                    spec.safetyWarningsJa(),
                    spec.candidateCodes(),
                    page,
                    spec.sectionPath(),
                    spec.problemProjection(),
                    spec.problemProjectionJa(),
                    spec.resolutionProjection(),
                    spec.resolutionProjectionJa());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to locate source region for " + spec.unitKey(),
                    exception);
        }
    }

    private List<PageText> extractPages(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        List<PageText> pages = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String raw = stripper.getText(document);
            pages.add(new PageText(page, printedPageLabel(raw), normalize(raw)));
        }
        return pages;
    }

    private void requireDocumentIdentity(List<PageText> pages) {
        String frontMatter = pages.stream()
                .limit(8)
                .map(PageText::text)
                .reduce("", (left, right) -> left + "\n" + right);
        if (!containsAll(frontMatter, identityMarkers())) {
            throw new IllegalArgumentException(
                    "PDF is not the reviewed " + model() + " service manual");
        }
    }

    private PageText findPage(List<PageText> pages, List<String> markers) {
        return pages.stream()
                .filter(page -> containsAll(page.text(), markers))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Required manual section not found: " + String.join(" / ", markers)));
    }

    private void requireText(PageText page, List<String> requiredFacts) {
        List<String> missing = requiredFacts.stream()
                .filter(marker -> !page.text().contains(marker))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Manual page " + page.pdfPageIndex()
                            + " failed semantic validation; missing: " + missing);
        }
    }

    private boolean containsAll(String text, List<String> markers) {
        return markers.stream().allMatch(text::contains);
    }

    private SourceRegion locateSourceRegion(
            PDDocument document,
            int pdfPageIndex,
            String sourceAnchor) throws IOException {
        AnchorPositionStripper stripper = new AnchorPositionStripper(sourceAnchor);
        stripper.setSortByPosition(true);
        stripper.setStartPage(pdfPageIndex);
        stripper.setEndPage(pdfPageIndex);
        stripper.getText(document);
        SourceRegion region = stripper.region();
        if (region == null) {
            throw new IllegalArgumentException(
                    "Source anchor not found on PDF page %d: %s"
                            .formatted(pdfPageIndex, sourceAnchor));
        }
        var cropBox = document.getPage(pdfPageIndex - 1).getCropBox();
        return new SourceRegion(
                region.x(),
                region.y(),
                region.width(),
                region.height(),
                cropBox.getWidth(),
                cropBox.getHeight());
    }

    private String normalize(String text) {
        return text
                .replace('\u00a0', ' ')
                .replace('\u2011', '-')
                .replace('\u2013', '-')
                .replaceAll("(?m)^Downloaded from .*manuals search engine\\s*$", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private String printedPageLabel(String raw) {
        Matcher matcher = PRINTED_PAGE.matcher(raw);
        String value = null;
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }

    private static final class AnchorPositionStripper extends PDFTextStripper {

        private final String anchor;
        private SourceRegion region;

        private AnchorPositionStripper(String anchor) throws IOException {
            this.anchor = normalizeAnchor(anchor);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (region == null
                    && normalizeAnchor(text).contains(anchor)
                    && !positions.isEmpty()) {
                double minX = Double.MAX_VALUE;
                double minTop = Double.MAX_VALUE;
                double maxX = Double.MIN_VALUE;
                double maxBottom = Double.MIN_VALUE;
                for (TextPosition position : positions) {
                    double x = position.getXDirAdj();
                    double bottom = position.getYDirAdj();
                    double top = bottom - position.getHeightDir();
                    minX = Math.min(minX, x);
                    minTop = Math.min(minTop, top);
                    maxX = Math.max(maxX, x + position.getWidthDirAdj());
                    maxBottom = Math.max(maxBottom, bottom);
                }
                region = new SourceRegion(
                        minX,
                        minTop,
                        maxX - minX,
                        maxBottom - minTop,
                        0,
                        0);
            }
            super.writeString(text, positions);
        }

        private SourceRegion region() {
            return region;
        }

        private static String normalizeAnchor(String value) {
            return value
                    .replace('\u00a0', ' ')
                    .replace('\u2011', '-')
                    .replace('\u2013', '-')
                    .replaceAll("\\s+", " ")
                    .strip();
        }
    }

    /** 经人工审查的一个手册知识单元定义。 */
    protected record UnitSpec(
            String unitKey,
            String unitType,
            String problemTypeCode,
            String errorCode,
            List<String> pageMarkers,
            List<String> requiredFacts,
            String sourceAnchor,
            String sectionPath,
            String title,
            String titleJa,
            String summary,
            String summaryJa,
            String sourceQuote,
            List<String> actionSteps,
            List<String> actionStepsJa,
            List<String> safetyWarnings,
            List<String> safetyWarningsJa,
            List<String> candidateCodes,
            String problemProjection,
            String problemProjectionJa,
            String resolutionProjection,
            String resolutionProjectionJa) {
    }
}
