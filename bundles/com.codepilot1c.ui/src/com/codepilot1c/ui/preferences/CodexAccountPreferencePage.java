/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.concurrent.CompletionException;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.provider.codex.CodexJwt;
import com.codepilot1c.core.provider.codex.CodexOAuthConstants;
import com.codepilot1c.core.provider.codex.CodexOAuthService;
import com.codepilot1c.core.provider.codex.CodexOAuthService.CodexLoginResult;
import com.codepilot1c.core.provider.codex.CodexOAuthService.CodexLoginSession;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;
import com.codepilot1c.ui.internal.Messages;

/**
 * Preference page for signing in to OpenAI Codex via a ChatGPT (Plus/Pro) subscription.
 *
 * <p>On a successful sign-in an {@code OPENAI_CODEX} provider is created (or updated) and set
 * active, so the user can start chatting without touching the LLM providers page.</p>
 */
public class CodexAccountPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String PAGE_ID = "com.codepilot1c.ui.preferences.CodexAccountPreferencePage"; //$NON-NLS-1$

    private final CodexOAuthService codexOAuthService = new CodexOAuthService();
    private CodexLoginSession session;

    private Label statusLabel;
    private Label planLabel;
    private Combo modelCombo;
    private Combo reasoningEffortCombo;
    private Button loginButton;
    private Button logoutButton;

    public CodexAccountPreferencePage() {
        super();
        setDescription("Вход в OpenAI Codex по подписке ChatGPT Plus/Pro (OAuth, без API-ключа)."); //$NON-NLS-1$
        noDefaultAndApplyButton();
    }

    @Override
    public void init(IWorkbench workbench) {
        // Preference store is not used here.
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group group = new Group(root, SWT.NONE);
        group.setText("Аккаунт ChatGPT"); //$NON-NLS-1$
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createLabel(group, "Статус:"); //$NON-NLS-1$
        statusLabel = new Label(group, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Тариф:"); //$NON-NLS-1$
        planLabel = new Label(group, SWT.NONE);
        planLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Модель:"); //$NON-NLS-1$
        modelCombo = new Combo(group, SWT.DROP_DOWN);
        modelCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelCombo.setItems(CodexOAuthConstants.KNOWN_MODELS);
        modelCombo.setText(resolveInitialModel());

        createLabel(group, "Reasoning:"); //$NON-NLS-1$
        reasoningEffortCombo = new Combo(group, SWT.DROP_DOWN | SWT.READ_ONLY);
        reasoningEffortCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reasoningEffortCombo.setItems(CodexOAuthConstants.REASONING_EFFORTS);
        reasoningEffortCombo.setText(resolveInitialReasoningEffort());

        Composite buttonRow = new Composite(group, SWT.NONE);
        GridLayout buttonLayout = new GridLayout(2, false);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttonRow.setLayout(buttonLayout);
        buttonRow.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        loginButton = new Button(buttonRow, SWT.PUSH);
        loginButton.setText("Подключить ChatGPT"); //$NON-NLS-1$
        loginButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onLogin();
            }
        });

        logoutButton = new Button(buttonRow, SWT.PUSH);
        logoutButton.setText("Выйти"); //$NON-NLS-1$
        logoutButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onLogout();
            }
        });

        Label hint = new Label(root, SWT.WRAP);
        hint.setText("После подключения провайдер «OpenAI Codex (ChatGPT)» создаётся и активируется " //$NON-NLS-1$
            + "автоматически с выбранной моделью. Набор доступных моделей задаёт OpenAI и периодически " //$NON-NLS-1$
            + "меняет — если выбранная модель отклонена (ошибка 400), выберите другую из списка и " //$NON-NLS-1$
            + "нажмите «Применить и закрыть»."); //$NON-NLS-1$
        GridData hintData = new GridData(SWT.FILL, SWT.TOP, true, false);
        hintData.widthHint = 520;
        hint.setLayoutData(hintData);

        refreshStatus();
        return root;
    }

    private void onLogin() {
        loginButton.setEnabled(false);
        setErrorMessage(null);
        if (statusLabel != null && !statusLabel.isDisposed()) {
            statusLabel.setText("Открываю браузер…"); //$NON-NLS-1$
        }
        session = codexOAuthService.beginLogin(
            url -> Display.getDefault().asyncExec(() -> Program.launch(url)));
        session.result().whenComplete((result, error) ->
            Display.getDefault().asyncExec(() -> onLoginDone(result, error)));
    }

    private void onLoginDone(CodexLoginResult result, Throwable error) {
        if (getControl() == null || getControl().isDisposed()) {
            return;
        }
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
            setErrorMessage(cause.getMessage());
        } else if (result != null) {
            try {
                boolean persisted = ensureActiveCodexProvider(
                        modelCombo.getText().trim(), reasoningEffortCombo.getText());
                setErrorMessage(persisted ? null : Messages.ProvidersPreferencePage_SaveError);
            } catch (Exception e) {
                VibeCorePlugin.logWarn("Failed to activate Codex provider: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        refreshStatus();
    }

    private void onLogout() {
        boolean confirm = MessageDialog.openConfirm(getShell(), "Выход", //$NON-NLS-1$
            "Отключить аккаунт ChatGPT? Сохранённые токены будут удалены."); //$NON-NLS-1$
        if (!confirm) {
            return;
        }
        codexOAuthService.logout();
        refreshStatus();
    }

    private boolean ensureActiveCodexProvider(String model, String reasoningEffort) {
        String effectiveModel = model != null && !model.isBlank() ? model : CodexOAuthConstants.DEFAULT_MODEL;
        LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();
        LlmProviderConfig codex = store.getProviders().stream()
            .filter(config -> config.getType() == ProviderType.OPENAI_CODEX)
            .findFirst()
            .orElse(null);
        if (codex == null) {
            codex = new LlmProviderConfig(null, "OpenAI Codex (ChatGPT)", ProviderType.OPENAI_CODEX, //$NON-NLS-1$
                CodexOAuthConstants.CODEX_BASE_URL, "", effectiveModel, 4096); //$NON-NLS-1$
            codex.setStreamingEnabled(true);
            codex.setReasoningEffort(reasoningEffort);
            if (!store.addProvider(codex)) {
                return false;
            }
        } else {
            codex.setBaseUrl(CodexOAuthConstants.CODEX_BASE_URL);
            codex.setModel(effectiveModel);
            codex.setReasoningEffort(reasoningEffort);
            if (!store.updateProvider(codex)) {
                return false;
            }
        }
        return store.setActiveProviderId(codex.getId());
    }

    private void refreshStatus() {
        boolean loggedIn = codexOAuthService.isLoggedIn();
        CodexJwt.CodexIdentity identity = loggedIn ? codexOAuthService.currentIdentity() : null;

        if (statusLabel != null && !statusLabel.isDisposed()) {
            if (loggedIn) {
                String email = identity != null && identity.email() != null ? identity.email() : ""; //$NON-NLS-1$
                statusLabel.setText("● Подключён" + (email.isEmpty() ? "" : ": " + email)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
            } else {
                statusLabel.setText("● Не подключён"); //$NON-NLS-1$
                statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_RED));
            }
        }
        if (planLabel != null && !planLabel.isDisposed()) {
            planLabel.setText(identity != null && identity.planType() != null ? identity.planType() : "—"); //$NON-NLS-1$
        }
        if (loginButton != null && !loginButton.isDisposed()) {
            loginButton.setEnabled(true);
            loginButton.setText(loggedIn ? "Переподключить" : "Подключить ChatGPT"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (logoutButton != null && !logoutButton.isDisposed()) {
            logoutButton.setEnabled(loggedIn);
        }
    }

    private String resolveInitialModel() {
        return LlmProviderConfigStore.getInstance().getProviders().stream()
            .filter(config -> config.getType() == ProviderType.OPENAI_CODEX)
            .map(LlmProviderConfig::getModel)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(CodexOAuthConstants.DEFAULT_MODEL);
    }

    private String resolveInitialReasoningEffort() {
        return LlmProviderConfigStore.getInstance().getProviders().stream()
            .filter(config -> config.getType() == ProviderType.OPENAI_CODEX)
            .map(LlmProviderConfig::getReasoningEffort)
            .findFirst()
            .orElse(CodexOAuthConstants.DEFAULT_REASONING_EFFORT);
    }

    private Label createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return label;
    }

    @Override
    public boolean performOk() {
        if (codexOAuthService.isLoggedIn()) {
            ensureActiveCodexProvider(modelCombo.getText().trim(), reasoningEffortCombo.getText());
        }
        return super.performOk();
    }

    @Override
    public void dispose() {
        if (session != null) {
            session.close();
            session = null;
        }
        super.dispose();
    }
}
