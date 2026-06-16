/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.List;
import java.util.concurrent.CompletionException;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ModelFetchService;
import com.codepilot1c.core.provider.config.ModelFetchService.FetchResult;
import com.codepilot1c.core.provider.config.ModelFetchService.ModelInfo;
import com.codepilot1c.core.provider.codex.CodexOAuthConstants;
import com.codepilot1c.core.provider.codex.CodexOAuthService;
import com.codepilot1c.core.provider.codex.CodexOAuthService.CodexLoginResult;
import com.codepilot1c.core.provider.codex.CodexOAuthService.CodexLoginSession;
import com.codepilot1c.core.provider.config.ProviderType;
import com.codepilot1c.ui.internal.Messages;

/**
 * Dialog for adding or editing an LLM provider configuration.
 */
public class ProviderEditDialog extends TitleAreaDialog {

    private final LlmProviderConfig config;
    private final boolean isNew;

    private Text nameText;
    private Combo typeCombo;
    private Text baseUrlText;
    private Text apiKeyText;
    private Text modelText;
    private Button fetchModelsButton;
    private Spinner maxTokensSpinner;
    private Button streamingCheckbox;
    private Button codexLoginButton;
    private Label codexStatusLabel;
    private final CodexOAuthService codexOAuthService = new CodexOAuthService();
    private CodexLoginSession codexSession;

    /**
     * Creates a dialog for editing an existing configuration.
     */
    public ProviderEditDialog(Shell parentShell, LlmProviderConfig config) {
        super(parentShell);
        this.config = config.copy();
        this.isNew = false;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    /**
     * Creates a dialog for adding a new configuration.
     */
    public ProviderEditDialog(Shell parentShell) {
        super(parentShell);
        this.config = new LlmProviderConfig();
        this.isNew = true;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(isNew ? Messages.ProviderEditDialog_TitleAdd : Messages.ProviderEditDialog_TitleEdit);
        shell.setMinimumSize(500, 400);
    }

    @Override
    public void create() {
        super.create();
        setTitle(isNew ? Messages.ProviderEditDialog_TitleAdd : Messages.ProviderEditDialog_TitleEdit);
        setMessage(Messages.ProviderEditDialog_Description, IMessageProvider.INFORMATION);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);

        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(3, false));

        // Name
        createLabel(container, Messages.ProviderEditDialog_Name);
        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(createTextGridData(2));
        nameText.setText(config.getName() != null ? config.getName() : ""); //$NON-NLS-1$
        nameText.addModifyListener(e -> validateInput());

        // Type
        createLabel(container, Messages.ProviderEditDialog_Type);
        typeCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        typeCombo.setLayoutData(createTextGridData(2));
        for (ProviderType type : ProviderType.values()) {
            typeCombo.add(type.getDisplayName());
        }
        typeCombo.select(config.getType().ordinal());
        typeCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateFetchButtonState();
                updateCodexControls();
                validateInput();
            }
        });

        // Base URL
        createLabel(container, Messages.ProviderEditDialog_BaseUrl);
        baseUrlText = new Text(container, SWT.BORDER);
        baseUrlText.setLayoutData(createTextGridData(2));
        baseUrlText.setText(config.getBaseUrl() != null ? config.getBaseUrl() : ""); //$NON-NLS-1$
        baseUrlText.setMessage("https://api.example.com/v1"); //$NON-NLS-1$
        baseUrlText.addModifyListener(e -> validateInput());

        // API Key
        createLabel(container, Messages.ProviderEditDialog_ApiKey);
        apiKeyText = new Text(container, SWT.BORDER | SWT.PASSWORD);
        apiKeyText.setLayoutData(createTextGridData(2));
        apiKeyText.setText(config.getApiKey() != null ? config.getApiKey() : ""); //$NON-NLS-1$
        apiKeyText.addModifyListener(e -> validateInput());

        // Model with Fetch button
        createLabel(container, Messages.ProviderEditDialog_Model);
        modelText = new Text(container, SWT.BORDER);
        modelText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelText.setText(config.getModel() != null ? config.getModel() : ""); //$NON-NLS-1$
        modelText.addModifyListener(e -> validateInput());

        fetchModelsButton = new Button(container, SWT.PUSH);
        fetchModelsButton.setText(Messages.ProviderEditDialog_FetchModels);
        fetchModelsButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                fetchModels();
            }
        });

        // Max Tokens
        createLabel(container, Messages.ProviderEditDialog_MaxTokens);
        maxTokensSpinner = new Spinner(container, SWT.BORDER);
        maxTokensSpinner.setLayoutData(createTextGridData(2));
        maxTokensSpinner.setMinimum(1);
        maxTokensSpinner.setMaximum(1000000);
        maxTokensSpinner.setSelection(config.getMaxTokens());

        // Streaming
        createLabel(container, ""); //$NON-NLS-1$
        streamingCheckbox = new Button(container, SWT.CHECK);
        streamingCheckbox.setLayoutData(createTextGridData(2));
        streamingCheckbox.setText(Messages.ProviderEditDialog_EnableStreaming);
        streamingCheckbox.setSelection(config.isStreamingEnabled());

        // ChatGPT (Codex OAuth) sign-in row
        createLabel(container, "Аккаунт ChatGPT"); //$NON-NLS-1$
        Composite codexRow = new Composite(container, SWT.NONE);
        codexRow.setLayoutData(createTextGridData(2));
        GridLayout codexRowLayout = new GridLayout(2, false);
        codexRowLayout.marginWidth = 0;
        codexRowLayout.marginHeight = 0;
        codexRow.setLayout(codexRowLayout);
        codexLoginButton = new Button(codexRow, SWT.PUSH);
        codexLoginButton.setText("Подключить ChatGPT"); //$NON-NLS-1$
        codexLoginButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                startCodexLogin();
            }
        });
        codexStatusLabel = new Label(codexRow, SWT.NONE);
        codexStatusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        updateFetchButtonState();
        updateCodexControls();

        return area;
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    private GridData createTextGridData(int span) {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.horizontalSpan = span;
        return gd;
    }

    private ProviderType getSelectedType() {
        int index = typeCombo.getSelectionIndex();
        if (index >= 0 && index < ProviderType.values().length) {
            return ProviderType.values()[index];
        }
        return ProviderType.OPENAI_COMPATIBLE;
    }

    private void updateFetchButtonState() {
        ProviderType type = getSelectedType();
        fetchModelsButton.setEnabled(type.supportsModelListing());
        if (!type.supportsModelListing()) {
            fetchModelsButton.setToolTipText(Messages.ProviderEditDialog_FetchNotSupported);
        } else {
            fetchModelsButton.setToolTipText(Messages.ProviderEditDialog_FetchTooltip);
        }
    }

    private void updateCodexControls() {
        boolean codex = getSelectedType() == ProviderType.OPENAI_CODEX;
        codexLoginButton.setEnabled(codex);
        if (codex) {
            if (baseUrlText.getText().trim().isEmpty()) {
                baseUrlText.setText(CodexOAuthConstants.CODEX_BASE_URL);
            }
            if (modelText.getText().trim().isEmpty()) {
                modelText.setText(CodexOAuthConstants.DEFAULT_MODEL);
            }
            apiKeyText.setEnabled(false);
        } else {
            apiKeyText.setEnabled(true);
        }
        refreshCodexStatus();
    }

    private void refreshCodexStatus() {
        if (codexStatusLabel == null || codexStatusLabel.isDisposed()) {
            return;
        }
        if (getSelectedType() != ProviderType.OPENAI_CODEX) {
            codexStatusLabel.setText(""); //$NON-NLS-1$
        } else {
            codexStatusLabel.setText(codexOAuthService.isLoggedIn() ? "Подключён" : "Не подключён"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void startCodexLogin() {
        codexLoginButton.setEnabled(false);
        codexStatusLabel.setText("Открываю браузер…"); //$NON-NLS-1$
        setErrorMessage(null);
        codexSession = codexOAuthService.beginLogin(
            url -> Display.getDefault().asyncExec(() -> Program.launch(url)));
        codexSession.result().whenComplete((result, error) ->
            Display.getDefault().asyncExec(() -> onCodexLoginDone(result, error)));
    }

    private void onCodexLoginDone(CodexLoginResult result, Throwable error) {
        if (codexLoginButton == null || codexLoginButton.isDisposed()) {
            return;
        }
        codexLoginButton.setEnabled(getSelectedType() == ProviderType.OPENAI_CODEX);
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
            codexStatusLabel.setText("Не удалось подключить"); //$NON-NLS-1$
            setErrorMessage(cause.getMessage());
        } else if (result != null) {
            codexStatusLabel.setText(result.email() != null ? "Подключён: " + result.email() : "Подключён"); //$NON-NLS-1$ //$NON-NLS-2$
            setErrorMessage(null);
        }
        validateInput();
    }

    private void fetchModels() {
        String baseUrl = baseUrlText.getText().trim();
        String apiKey = apiKeyText.getText().trim();
        ProviderType type = getSelectedType();

        if (baseUrl.isEmpty()) {
            setErrorMessage(Messages.ProviderEditDialog_BaseUrlRequired);
            return;
        }

        fetchModelsButton.setEnabled(false);
        fetchModelsButton.setText(Messages.ProviderEditDialog_Fetching);

        ModelFetchService.getInstance().fetchModels(baseUrl, apiKey, type)
                .thenAccept(result -> {
                    Display.getDefault().asyncExec(() -> {
                        handleFetchResult(result);
                    });
                });
    }

    private void handleFetchResult(FetchResult result) {
        fetchModelsButton.setEnabled(true);
        fetchModelsButton.setText(Messages.ProviderEditDialog_FetchModels);

        if (!result.isSuccess()) {
            if (result.requiresManualModelEntry()) {
                setErrorMessage(null);
                setMessage(Messages.ProviderEditDialog_ManualModelEntryRequired, IMessageProvider.WARNING);
                modelText.setFocus();
                return;
            }
            setErrorMessage(result.getError());
            return;
        }

        List<ModelInfo> models = result.getModels();
        if (models.isEmpty()) {
            setMessage(Messages.ProviderEditDialog_NoModelsFound, IMessageProvider.WARNING);
            return;
        }

        // Show model selection dialog
        ModelSelectionDialog dialog = new ModelSelectionDialog(getShell(), models);
        if (dialog.open() == IDialogConstants.OK_ID) {
            ModelInfo selected = dialog.getSelectedModel();
            if (selected != null) {
                modelText.setText(selected.getId());
                setErrorMessage(null);
            }
        }
    }

    private void validateInput() {
        String name = nameText.getText().trim();
        String baseUrl = baseUrlText.getText().trim();
        String model = modelText.getText().trim();
        String apiKey = apiKeyText.getText().trim();
        ProviderType type = getSelectedType();

        Button okButton = getButton(IDialogConstants.OK_ID);

        if (name.isEmpty()) {
            setErrorMessage(Messages.ProviderEditDialog_NameRequired);
            if (okButton != null) okButton.setEnabled(false);
            return;
        }

        if (baseUrl.isEmpty()) {
            setErrorMessage(Messages.ProviderEditDialog_BaseUrlRequired);
            if (okButton != null) okButton.setEnabled(false);
            return;
        }

        if (model.isEmpty()) {
            setErrorMessage(Messages.ProviderEditDialog_ModelRequired);
            if (okButton != null) okButton.setEnabled(false);
            return;
        }

        // API key required for non-Ollama, non-Codex providers
        if (type != ProviderType.OLLAMA && type != ProviderType.OPENAI_CODEX && apiKey.isEmpty()) {
            setErrorMessage(Messages.ProviderEditDialog_ApiKeyRequired);
            if (okButton != null) okButton.setEnabled(false);
            return;
        }

        // Codex authenticates via ChatGPT OAuth instead of an API key.
        if (type == ProviderType.OPENAI_CODEX && !codexOAuthService.isLoggedIn()) {
            setErrorMessage("Подключите ChatGPT для использования OpenAI Codex."); //$NON-NLS-1$
            if (okButton != null) okButton.setEnabled(false);
            return;
        }

        setErrorMessage(null);
        if (okButton != null) okButton.setEnabled(true);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        validateInput();
    }

    @Override
    protected void okPressed() {
        // Save values to config
        config.setName(nameText.getText().trim());
        config.setType(getSelectedType());
        config.setBaseUrl(baseUrlText.getText().trim());
        config.setApiKey(apiKeyText.getText().trim());
        config.setModel(modelText.getText().trim());
        config.setMaxTokens(maxTokensSpinner.getSelection());
        config.setStreamingEnabled(streamingCheckbox.getSelection());

        super.okPressed();
    }

    @Override
    public boolean close() {
        if (codexSession != null) {
            codexSession.close();
            codexSession = null;
        }
        return super.close();
    }

    /**
     * Returns the edited configuration.
     */
    public LlmProviderConfig getConfig() {
        return config;
    }

    /**
     * Returns whether this is a new provider.
     */
    public boolean isNew() {
        return isNew;
    }
}
