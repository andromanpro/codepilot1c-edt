package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostConfig.MutationPolicy;

/**
 * Правило отказа неподтверждаемым вызовам на MCP-транспорте.
 *
 * <p>Каждое «обязан пропустить» здесь спарено с «обязан отказать»: правило,
 * которое умеет только разрешать, не отличается от снятого гейта, а правило,
 * которое умеет только отказывать, — это ровно тот дефект, из-за которого
 * сборка 1.3.9 оказалась непригодной (Gitea #85).</p>
 */
public class McpHostConfirmationPolicyTest {

    // ---------- legacy-режим: профиль не задан ----------

    /**
     * Регрессия #85. В legacy-режиме роутер отказывал безусловно, игнорируя
     * настроенную политику мутаций: конфигурация говорила ALLOW, хост отвечал
     * DENY, и вся мутирующая поверхность умирала.
     */
    @Test
    public void legacyСПолитикойAllowПропускает() {
        assertFalse("ALLOW — записанное решение оператора, его нельзя переопределять отказом",
                McpHostConfirmationPolicy.refusesUnconfirmable(true, false, MutationPolicy.ALLOW));
    }

    @Test
    public void legacyСПолитикойAskОтказывает() {
        assertTrue("ASK требует спросить, а спросить по этому транспорту негде",
                McpHostConfirmationPolicy.refusesUnconfirmable(true, false, MutationPolicy.ASK));
    }

    @Test
    public void legacyСПолитикойDenyОтказывает() {
        assertTrue(McpHostConfirmationPolicy.refusesUnconfirmable(true, false, MutationPolicy.DENY));
    }

    /**
     * Отсутствие политики — не то же самое, что ALLOW: молчание не разрешение.
     */
    @Test
    public void legacyБезПолитикиОтказывает() {
        assertTrue("null-политика обязана трактоваться как отказ",
                McpHostConfirmationPolicy.refusesUnconfirmable(true, false, null));
    }

    // ---------- профильный гейт включён: контракт upstream не трогаем ----------

    @Test
    public void сПрофилемОтказываетДажеПриAllow() {
        assertTrue("при включённом профиле решение принимает профиль, а не эта политика",
                McpHostConfirmationPolicy.refusesUnconfirmable(true, true, MutationPolicy.ALLOW));
    }

    // ---------- инструменты, подтверждения не требующие ----------

    @Test
    public void безТребованияПодтвержденияПропускаетВсегда() {
        for (MutationPolicy policy : MutationPolicy.values()) {
            assertFalse("read-only инструмент не должен упираться в эту проверку: " + policy,
                    McpHostConfirmationPolicy.refusesUnconfirmable(false, false, policy));
            assertFalse("то же самое при включённом профиле: " + policy,
                    McpHostConfirmationPolicy.refusesUnconfirmable(false, true, policy));
        }
    }
}
