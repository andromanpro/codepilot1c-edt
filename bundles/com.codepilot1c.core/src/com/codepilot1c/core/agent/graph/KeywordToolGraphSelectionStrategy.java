package com.codepilot1c.core.agent.graph;

import java.util.List;

/**
 * Simple keyword-based graph selection.
 */
public class KeywordToolGraphSelectionStrategy implements ToolGraphSelectionStrategy {

    @Override
    public String selectGraphId(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return ToolGraphRegistry.GENERAL_GRAPH_ID;
        }
        String prompt = userPrompt.toLowerCase();

        if (isMultiDomainFeaturePrompt(prompt)) {
            return ToolGraphRegistry.FEATURE_GRAPH_ID;
        }

        if (isPrimarilyDcsPrompt(prompt)) {
            return ToolGraphRegistry.DCS_GRAPH_ID;
        }

        if (containsAny(prompt, List.of("форма", "форму", "формы", "управляемая форма"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return ToolGraphRegistry.FORMS_GRAPH_ID;
        }

        if (containsAny(prompt, List.of("реквизит", "таблич", "справочник", "документ", "регистр", "метаданные"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            return ToolGraphRegistry.METADATA_GRAPH_ID;
        }

        if (containsAny(prompt, List.of("процедур", "функц", "модуль", "bsl", "код"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return ToolGraphRegistry.BSL_GRAPH_ID;
        }

        return ToolGraphRegistry.GENERAL_GRAPH_ID;
    }

    private boolean isPrimarilyDcsPrompt(String prompt) {
        if (!containsAny(prompt, List.of("отчет", "отчёт", "скд", "компоновк", "dcs", "data composition"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            return false;
        }
        return countFeatureDomains(prompt) <= 1
                && !containsAny(prompt, List.of("форма", "форму", "формы", "документ", "регистр", "учет", "учёт")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    }

    private boolean isMultiDomainFeaturePrompt(String prompt) {
        if (!containsAny(prompt, List.of("учет", "учёт", "функционал", "подсистема", "сценарий"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return false;
        }
        return countFeatureDomains(prompt) >= 2;
    }

    private int countFeatureDomains(String prompt) {
        int domains = 0;
        if (containsAny(prompt, List.of("справочник", "номенклатур", "контрагент", "классификатор"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            domains++;
        }
        if (containsAny(prompt, List.of("документ", "поступление", "реализац", "продаж", "закуп"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            domains++;
        }
        if (containsAny(prompt, List.of("регистр", "движен", "ресурс"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            domains++;
        }
        if (containsAny(prompt, List.of("форма", "форму", "формы", "управляемая форма"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            domains++;
        }
        if (containsAny(prompt, List.of("отчет", "отчёт", "скд", "компоновк", "dcs"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            domains++;
        }
        return domains;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
