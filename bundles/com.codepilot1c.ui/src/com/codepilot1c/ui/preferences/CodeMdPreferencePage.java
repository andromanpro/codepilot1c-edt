/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.memory.project.ProjectMemoryContextService;
import com.codepilot1c.core.memory.project.ProjectMemoryContextService.ReadResult;
import com.codepilot1c.core.memory.project.ProjectMemoryContextService.Status;
import com.codepilot1c.core.memory.project.ProjectMemoryContextService.WriteResult;
import com.codepilot1c.ui.internal.Messages;
import com.codepilot1c.ui.internal.VibeUiPlugin;
import com.codepilot1c.ui.markdown.FlexmarkParser;

/**
 * Preference page for editing the project-level Code.md instruction file.
 */
public class CodeMdPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private final ProjectMemoryContextService projectMemoryService = new ProjectMemoryContextService();
    private final FlexmarkParser markdownParser = new FlexmarkParser(true);
    private final List<IProject> openProjects = new ArrayList<>();

    private Combo projectCombo;
    private Label filePathLabel;
    private TabFolder contentTabs;
    private Text editor;
    private Browser previewBrowser;
    private Text previewFallback;
    private Label statusLabel;
    private Button clearButton;
    private Button insertTemplateButton;
    private IProject selectedProject;
    private Status loadedStatus;
    private boolean previewDirty = true;

    public CodeMdPreferencePage() {
        setDescription(Messages.CodeMdPreferencePage_Description);
    }

    @Override
    public void init(IWorkbench workbench) {
        // No-op.
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createProjectSelector(root);
        createFilePathRow(root);
        createEditorArea(root);
        createActionButtons(root);
        createStatusRow(root);

        loadProjects();
        return root;
    }

    private void createProjectSelector(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayout(new GridLayout(2, false));
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label projectLabel = new Label(row, SWT.NONE);
        projectLabel.setText(Messages.CodeMdPreferencePage_ProjectLabel);

        projectCombo = new Combo(row, SWT.READ_ONLY | SWT.DROP_DOWN);
        projectCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectCombo.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(e -> {
            int index = projectCombo.getSelectionIndex();
            if (index >= 0 && index < openProjects.size()) {
                selectedProject = openProjects.get(index);
                refreshEditor();
            }
        }));
    }

    private void createFilePathRow(Composite parent) {
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayout(new GridLayout(2, false));
        row.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Label pathTitle = new Label(row, SWT.NONE);
        pathTitle.setText(Messages.CodeMdPreferencePage_FileLabel);

        filePathLabel = new Label(row, SWT.WRAP);
        filePathLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filePathLabel.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
    }

    private void createEditorArea(Composite parent) {
        Label editorTitle = new Label(parent, SWT.NONE);
        editorTitle.setText(Messages.CodeMdPreferencePage_EditorLabel);
        editorTitle.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));

        contentTabs = new TabFolder(parent, SWT.NONE);
        GridData tabsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tabsData.heightHint = 420;
        contentTabs.setLayoutData(tabsData);

        TabItem editorTab = new TabItem(contentTabs, SWT.NONE);
        editorTab.setText(Messages.CodeMdPreferencePage_EditTab);

        Composite editorContainer = new Composite(contentTabs, SWT.NONE);
        editorContainer.setLayout(new GridLayout(1, false));
        editorTab.setControl(editorContainer);

        editor = new Text(editorContainer, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.H_SCROLL);
        editor.setFont(JFaceResources.getTextFont());
        editor.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        editor.addModifyListener(e -> markPreviewDirty());

        TabItem previewTab = new TabItem(contentTabs, SWT.NONE);
        previewTab.setText(Messages.CodeMdPreferencePage_PreviewTab);

        Composite previewContainer = new Composite(contentTabs, SWT.NONE);
        previewContainer.setLayout(new GridLayout(1, false));
        previewTab.setControl(previewContainer);
        createPreviewControl(previewContainer);

        contentTabs.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(e -> {
            if (contentTabs.getSelectionIndex() == 1) {
                renderPreviewIfDirty();
            }
        }));

        Label hint = new Label(parent, SWT.WRAP);
        hint.setText(Messages.CodeMdPreferencePage_Hint);
        hint.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        hint.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void createPreviewControl(Composite parent) {
        try {
            previewBrowser = new Browser(parent, SWT.EDGE);
        } catch (SWTError e) {
            try {
                previewBrowser = new Browser(parent, SWT.NONE);
            } catch (SWTError fallbackError) {
                VibeUiPlugin.log(fallbackError);
                previewFallback = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
                previewFallback.setText(Messages.CodeMdPreferencePage_PreviewUnavailable);
                previewFallback.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
                return;
            }
        }

        previewBrowser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        previewBrowser.addLocationListener(new LocationAdapter() {
            @Override
            public void changing(LocationEvent event) {
                if (event.location != null && !event.location.startsWith("about:")) { //$NON-NLS-1$
                    event.doit = false;
                }
            }
        });
        previewBrowser.setText(buildPreviewHtml("")); //$NON-NLS-1$
    }

    private void createActionButtons(Composite parent) {
        Composite actions = new Composite(parent, SWT.NONE);
        RowLayout rowLayout = new RowLayout();
        rowLayout.spacing = 8;
        rowLayout.marginLeft = 0;
        rowLayout.marginTop = 4;
        actions.setLayout(rowLayout);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        insertTemplateButton = new Button(actions, SWT.PUSH);
        insertTemplateButton.setText(Messages.CodeMdPreferencePage_InsertTemplateButton);
        insertTemplateButton.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(
                e -> editor.setText(defaultTemplate())));

        clearButton = new Button(actions, SWT.PUSH);
        clearButton.setText(Messages.CodeMdPreferencePage_ClearButton);
        clearButton.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(
                e -> editor.setText(""))); //$NON-NLS-1$
    }

    private void createStatusRow(Composite parent) {
        statusLabel = new Label(parent, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void loadProjects() {
        openProjects.clear();
        projectCombo.removeAll();

        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                openProjects.add(project);
                projectCombo.add(project.getName());
            }
        }

        if (openProjects.isEmpty()) {
            selectedProject = null;
            filePathLabel.setText(""); //$NON-NLS-1$
            editor.setText(""); //$NON-NLS-1$
            loadedStatus = null;
            setControlsEnabled(false);
            setStatus(Messages.CodeMdPreferencePage_NoOpenProjects, false);
            return;
        }

        setControlsEnabled(true);
        int preselect = findProjectWithMemory();
        projectCombo.select(preselect);
        selectedProject = openProjects.get(preselect);
        refreshEditor();
    }

    private int findProjectWithMemory() {
        for (int i = 0; i < openProjects.size(); i++) {
            IPath location = openProjects.get(i).getLocation();
            if (location != null && isExistingStatus(projectMemoryService.status(location.toOSString()).getStatus())) {
                return i;
            }
        }
        return 0;
    }

    private void refreshEditor() {
        setErrorMessage(null);
        if (selectedProject == null) {
            return;
        }

        IPath projectLocation = selectedProject.getLocation();
        if (projectLocation == null) {
            filePathLabel.setText(""); //$NON-NLS-1$
            editor.setText(""); //$NON-NLS-1$
            loadedStatus = null;
            setControlsEnabled(false);
            setStatus(Messages.CodeMdPreferencePage_NoProjectLocation, false);
            return;
        }

        setControlsEnabled(true);
        ReadResult result = projectMemoryService.readFull(projectLocation.toOSString());
        loadedStatus = result.getStatus();
        filePathLabel.setText(displayPath(result));

        if (result.getStatus() == Status.MISSING) {
            editor.setText(""); //$NON-NLS-1$
            setStatus(buildStatus(Messages.CodeMdPreferencePage_FileMissingStatus, result), true);
            return;
        }

        if (result.getStatus() == Status.FOUND || result.getStatus() == Status.EMPTY) {
            editor.setText(result.getContent());
            setStatus(buildStatus(Messages.CodeMdPreferencePage_FileLoadedStatus, result), true);
            return;
        }

        editor.setText(""); //$NON-NLS-1$
        setStatus(buildStatus(MessageFormat.format(
                Messages.CodeMdPreferencePage_ReadErrorStatus,
                safeWarning(result.getWarning())), result), false);
    }

    @Override
    public boolean performOk() {
        if (selectedProject == null) {
            return true;
        }

        IPath projectLocation = selectedProject.getLocation();
        if (projectLocation == null) {
            setErrorMessage(Messages.CodeMdPreferencePage_NoProjectLocation);
            return false;
        }

        if (loadedStatus == Status.MISSING && editor.getText().isBlank()) {
            ReadResult result = projectMemoryService.status(projectLocation.toOSString());
            setErrorMessage(null);
            setStatus(buildStatus(Messages.CodeMdPreferencePage_FileMissingStatus, result), true);
            return true;
        }

        WriteResult result = projectMemoryService.write(projectLocation.toOSString(), editor.getText());
        if (result.getStatus() != Status.FOUND) {
            setErrorMessage(MessageFormat.format(
                    Messages.CodeMdPreferencePage_SaveError,
                    safeWarning(result.getWarning())));
            setStatus(buildStatus(safeWarning(result.getWarning()), result), false);
            return false;
        }

        try {
            selectedProject.refreshLocal(IResource.DEPTH_INFINITE, null);
        } catch (CoreException e) {
            VibeUiPlugin.log(e);
            setErrorMessage(MessageFormat.format(Messages.CodeMdPreferencePage_SaveError, e.getMessage()));
            return false;
        }

        setErrorMessage(null);
        loadedStatus = Status.FOUND;
        setStatus(buildStatus(Messages.CodeMdPreferencePage_SavedStatus, result), true);
        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        refreshEditor();
        setErrorMessage(null);
        super.performDefaults();
    }

    private void setControlsEnabled(boolean enabled) {
        if (editor != null) {
            editor.setEnabled(enabled);
        }
        if (contentTabs != null) {
            contentTabs.setEnabled(enabled);
        }
        if (previewFallback != null) {
            previewFallback.setEnabled(enabled);
        }
        if (clearButton != null) {
            clearButton.setEnabled(enabled);
        }
        if (insertTemplateButton != null) {
            insertTemplateButton.setEnabled(enabled);
        }
    }

    private void markPreviewDirty() {
        previewDirty = true;
        if (contentTabs != null && !contentTabs.isDisposed() && contentTabs.getSelectionIndex() == 1) {
            renderPreviewIfDirty();
        }
    }

    private void renderPreviewIfDirty() {
        if (!previewDirty || editor == null || editor.isDisposed()) {
            return;
        }
        previewDirty = false;
        String markdown = editor.getText();
        if (previewBrowser != null && !previewBrowser.isDisposed()) {
            previewBrowser.setText(buildPreviewHtml(markdown));
            return;
        }
        if (previewFallback != null && !previewFallback.isDisposed()) {
            previewFallback.setText(markdown == null || markdown.isBlank()
                    ? Messages.CodeMdPreferencePage_PreviewEmpty
                    : markdown);
        }
    }

    private String buildPreviewHtml(String markdown) {
        String content = markdown == null || markdown.isBlank()
                ? "<p class=\"empty\">" + escapeHtml(Messages.CodeMdPreferencePage_PreviewEmpty) + "</p>" //$NON-NLS-1$ //$NON-NLS-2$
                : markdownParser.toHtml(markdown);
        return "<!DOCTYPE html>\n" + //$NON-NLS-1$
               "<html><head><meta charset=\"UTF-8\">" + //$NON-NLS-1$
               "<style>" + previewCss() + "</style>" + //$NON-NLS-1$ //$NON-NLS-2$
               "</head><body><main class=\"markdown-body\">" + //$NON-NLS-1$
               content +
               "</main></body></html>"; //$NON-NLS-1$
    }

    private String previewCss() {
        return """
                body {
                    margin: 0;
                    background: #ffffff;
                    color: #24292f;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                    font-size: 14px;
                    line-height: 1.55;
                }
                .markdown-body {
                    max-width: 980px;
                    padding: 18px 22px 28px;
                }
                h1, h2, h3, h4 {
                    margin: 1.2em 0 .55em;
                    line-height: 1.25;
                    color: #0f172a;
                }
                h1 { font-size: 1.8em; border-bottom: 1px solid #d8dee4; padding-bottom: .3em; }
                h2 { font-size: 1.4em; border-bottom: 1px solid #d8dee4; padding-bottom: .25em; }
                h3 { font-size: 1.15em; }
                p, ul, ol, table, pre, blockquote { margin: .65em 0; }
                ul, ol { padding-left: 1.6em; }
                table {
                    border-collapse: collapse;
                    display: block;
                    overflow-x: auto;
                    width: max-content;
                    max-width: 100%;
                }
                th, td {
                    border: 1px solid #d0d7de;
                    padding: 6px 10px;
                    vertical-align: top;
                }
                th { background: #f6f8fa; font-weight: 600; }
                code {
                    background: #f6f8fa;
                    border-radius: 4px;
                    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                    font-size: .92em;
                    padding: .12em .3em;
                }
                pre {
                    background: #f6f8fa;
                    border: 1px solid #d0d7de;
                    border-radius: 6px;
                    overflow: auto;
                    padding: 12px;
                }
                pre code { background: transparent; padding: 0; }
                blockquote {
                    border-left: 4px solid #d0d7de;
                    color: #57606a;
                    padding: 2px 0 2px 12px;
                }
                a { color: #0969da; text-decoration: none; }
                .code-header, .copy-btn { display: none; }
                .empty { color: #6e7781; }
                """;
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return ""; //$NON-NLS-1$
        }
        return text
                .replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "&quot;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("'", "&#39;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String displayPath(ReadResult result) {
        return result.getSourcePath() != null ? result.getSourcePath().toString() : ""; //$NON-NLS-1$
    }

    private String buildStatus(String message, ReadResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append(MessageFormat.format(
                Messages.CodeMdPreferencePage_StatusFormat,
                message,
                statusLabel(result.getStatus()),
                Long.valueOf(result.getSizeBytes()),
                displayPath(result)));
        if (result.getWarning() != null && !result.getWarning().isBlank()) {
            builder.append(' ');
            builder.append(MessageFormat.format(Messages.CodeMdPreferencePage_WarningFormat, result.getWarning()));
        }
        return builder.toString();
    }

    private String buildStatus(String message, WriteResult result) {
        return MessageFormat.format(
                Messages.CodeMdPreferencePage_WriteStatusFormat,
                message,
                statusLabel(result.getStatus()),
                result.getSourcePath() != null ? result.getSourcePath().toString() : ""); //$NON-NLS-1$
    }

    private String statusLabel(Status status) {
        if (status == null) {
            return ""; //$NON-NLS-1$
        }
        switch (status) {
        case FOUND:
            return Messages.CodeMdPreferencePage_StatusFound;
        case MISSING:
            return Messages.CodeMdPreferencePage_StatusMissing;
        case EMPTY:
            return Messages.CodeMdPreferencePage_StatusEmpty;
        case TRUNCATED:
            return Messages.CodeMdPreferencePage_StatusTruncated;
        case READ_ERROR:
            return Messages.CodeMdPreferencePage_StatusReadError;
        case WRITE_ERROR:
            return Messages.CodeMdPreferencePage_StatusWriteError;
        case OUTSIDE_PROJECT:
            return Messages.CodeMdPreferencePage_StatusOutsideProject;
        default:
            return status.name();
        }
    }

    private boolean isExistingStatus(Status status) {
        return status == Status.FOUND || status == Status.EMPTY || status == Status.TRUNCATED;
    }

    private String safeWarning(String warning) {
        return warning == null || warning.isBlank() ? Messages.CodeMdPreferencePage_UnknownError : warning;
    }

    private void setStatus(String message, boolean ok) {
        if (statusLabel == null || statusLabel.isDisposed()) {
            return;
        }
        statusLabel.setText(message);
        statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(
                ok ? SWT.COLOR_DARK_GREEN : SWT.COLOR_DARK_RED));
    }

    private String defaultTemplate() {
        return Messages.CodeMdPreferencePage_DefaultTemplate.strip();
    }
}
