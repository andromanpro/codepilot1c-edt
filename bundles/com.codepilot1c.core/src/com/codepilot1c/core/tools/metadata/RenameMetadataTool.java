package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.metadata.RenameMetadataRequest;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.metadata.SupportLockGuard;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for EDT-grade metadata rename refactoring with reference updates.
 */
@ToolMeta(name = "rename_metadata", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class RenameMetadataTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(RenameMetadataTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, где существует переименовываемый объект"
                },
                "target_fqn": {
                  "type": "string",
                  "description": "FQN объекта метаданных или дочернего элемента (Catalog.X, Document.Y.Attribute.Z)"
                },
                "new_name": {
                  "type": "string",
                  "description": "Новое имя (только имя, без типа и точек)"
                },
                "predefined_item": {
                  "type": "string",
                  "description": "Имя предопределенного элемента внутри target_fqn, если переименовывается предопределенный"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request для operation=rename_metadata; передавать без изменений"
                },
                "allow_supported_object_edit": {
                  "type": "boolean",
                  "description": "Явное согласие менять объект типовой конфигурации, находящийся на поддержке с замком (антипаттерн #70). По умолчанию false: мутация типового объекта с запретом изменений отклоняется — доработка выполняется расширением."
                }
              },
              "required": ["project", "target_fqn", "new_name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public RenameMetadataTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    RenameMetadataTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Переименовывает объект метаданных или предопределенный элемент как EDT-рефакторинг: с обновлением всех ссылок (код, формы, СКД, права). Использовать вместо update_metadata name."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("rename-md"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START rename_metadata", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.RENAME_METADATA,
                        projectName);

                String targetFqn = asRequiredString(validatedPayload, "target_fqn"); //$NON-NLS-1$
                String newName = asRequiredString(validatedPayload, "new_name"); //$NON-NLS-1$
                Object predefined = validatedPayload.get("predefined_item"); //$NON-NLS-1$
                String predefinedItem = predefined == null ? null : String.valueOf(predefined);

                RenameMetadataRequest request = new RenameMetadataRequest(
                        projectName, targetFqn, newName, predefinedItem,
                        SupportLockGuard.isAllowed(parameters));
                MetadataOperationResult result = metadataService.renameMetadata(request);
                LOG.info("[%s] SUCCESS in %s, fqn=%s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.fqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] rename_metadata failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка rename_metadata: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return failRequired(key);
        }
        return String.valueOf(value);
    }

    private String failRequired(String key) {
        throw new IllegalArgumentException("Required field missing in validated payload: " + key); //$NON-NLS-1$
    }
}
