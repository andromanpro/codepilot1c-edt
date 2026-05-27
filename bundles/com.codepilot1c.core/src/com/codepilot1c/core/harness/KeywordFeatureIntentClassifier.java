package com.codepilot1c.core.harness;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class KeywordFeatureIntentClassifier implements FeatureIntentClassifier {

    @Override
    public FeatureIntent classify(String originalPrompt) {
        String prompt = originalPrompt == null ? "" : originalPrompt; //$NON-NLS-1$
        String text = " " + prompt.toLowerCase(Locale.ROOT) + " "; //$NON-NLS-1$ //$NON-NLS-2$
        Set<FeatureFrame> frames = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();

        match(text, frames, reasons, FeatureFrame.REFERENCE_DATA,
                "catalog", "reference", "classifier", "product", "goods", "warehouse", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "справочник", "каталог", "классификатор", "номенклатур", "товар", "склад"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        match(text, frames, reasons, FeatureFrame.BUSINESS_EVENT,
                "document", "receipt", "sale", "purchase", "order", "business event", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "документ", "поступлен", "реализац", "продаж", "закуп", "заказ"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        match(text, frames, reasons, FeatureFrame.STATE_HISTORY,
                "status", "history", "state", "periodic", "information register", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "статус", "истори", "состояни", "период", "регистр сведений"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        match(text, frames, reasons, FeatureFrame.REPORTING,
                "report", "dcs", "analytics", "dashboard", "statement", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "отчет", "отчёт", "скд", "аналитик", "ведомость"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        match(text, frames, reasons, FeatureFrame.FORM_UX,
                " form ", "managed form", "command", "button", " ui ", " ux ", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "форма", "управляемая форма", "команда", "кнопка", "интерфейс"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        match(text, frames, reasons, FeatureFrame.MODULE_LOGIC,
                "module", "bsl", "posting", "movement", "validation", "availability", "logic", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                "модуль", "проведен", "проведение", "движени", "провер", "логик", "доступн"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        match(text, frames, reasons, FeatureFrame.QA_VERIFICATION,
                "test", "qa", "verification", "smoke", "scenario", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "тест", "проверка", "сценарий", "дымовой"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        match(text, frames, reasons, FeatureFrame.INTEGRATION,
                "integration", "exchange", "api", "external", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "интеграц", "обмен", "внешн", "api"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        match(text, frames, reasons, FeatureFrame.EXTENSION_LAYER,
                "extension", "overlay", "adopt", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "расширение", "заимств", "адапт"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (isResourceAccountingPrompt(text, frames)) {
            add(frames, reasons, FeatureFrame.RESOURCE_ACCOUNTING, "matched RESOURCE_ACCOUNTING keyword"); //$NON-NLS-1$
        }

        return new FeatureIntent(prompt, frames, confidence(frames), reasons);
    }

    private static boolean isResourceAccountingPrompt(String text, Set<FeatureFrame> frames) {
        if (isReportOnlyOverExistingData(text, frames)) {
            return false;
        }
        return containsAny(text, "accounting", "balance", "turnover", "stock", "inventory", "accumulation register", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "учет", "учёт", "остат", "оборот", "наличи", "регистр накопления", "приход", "расход"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
    }

    private static boolean isReportOnlyOverExistingData(String text, Set<FeatureFrame> frames) {
        if (!frames.contains(FeatureFrame.REPORTING)) {
            return false;
        }
        if (containsAny(text, "implement accounting", "create accounting", "реализовать учет", "реализовать учёт")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return false;
        }
        if (containsAny(text, "document", "документ", "register", "регистр", "posting", "проведение")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            return false;
        }
        return containsAny(text, "existing", "существующ", "только отчет", "только отчёт", "report only"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    private static void match(String text, Set<FeatureFrame> frames, List<String> reasons, FeatureFrame frame,
                              String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                add(frames, reasons, frame, "matched " + frame.name() + " keyword: " + keyword); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
        }
    }

    private static void add(Set<FeatureFrame> frames, List<String> reasons, FeatureFrame frame, String reason) {
        if (frames.add(frame)) {
            reasons.add(reason);
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static double confidence(Set<FeatureFrame> frames) {
        if (frames.isEmpty()) {
            return 0.0d;
        }
        return Math.min(0.95d, 0.4d + frames.size() * 0.1d);
    }
}
