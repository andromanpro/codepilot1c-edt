package com.codepilot1c.core.edt.metadata;

import java.util.List;

import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Single source of truth for mapping a {@link MetadataKind} to its backing top-level configuration
 * collection.
 *
 * <p>Resolution and enumeration of top-level metadata objects MUST go through the typed
 * {@code Configuration.getXxx()} accessors exposed here. In the EDT mdclass model the typed
 * top-level collections are <b>derived</b> features computed from the configuration content, so a
 * reflective {@code eGet} walk over {@code Configuration.eClass().getEAllReferences()} that skips
 * derived references returns nothing for them. The typed accessors are the only reliable path
 * (proven by the mutation flow and the index-scan fallback).</p>
 *
 * <p>The switch is intentionally exhaustive over {@link MetadataKind}: adding a new kind without a
 * collection mapping here is a compile error, which prevents the "type silently unsupported" class
 * of bugs (e.g. Task/HTTPService not resolving) from recurring across the multiple call sites that
 * resolve metadata by kind.</p>
 */
public final class MetadataConfigurationCollections {

    private MetadataConfigurationCollections() {
        // utility
    }

    public static List<? extends MdObject> topLevelForKind(Configuration configuration, MetadataKind kind) {
        return switch (kind) {
            case CATALOG -> configuration.getCatalogs();
            case DOCUMENT -> configuration.getDocuments();
            case INFORMATION_REGISTER -> configuration.getInformationRegisters();
            case ACCUMULATION_REGISTER -> configuration.getAccumulationRegisters();
            case ACCOUNTING_REGISTER -> configuration.getAccountingRegisters();
            case CALCULATION_REGISTER -> configuration.getCalculationRegisters();
            case COMMON_MODULE -> configuration.getCommonModules();
            case COMMON_ATTRIBUTE -> configuration.getCommonAttributes();
            case ENUM -> configuration.getEnums();
            case REPORT -> configuration.getReports();
            case DATA_PROCESSOR -> configuration.getDataProcessors();
            case CONSTANT -> configuration.getConstants();
            case COMMAND_GROUP -> configuration.getCommandGroups();
            case INTERFACE -> configuration.getInterfaces();
            case LANGUAGE -> configuration.getLanguages();
            case STYLE -> configuration.getStyles();
            case STYLE_ITEM -> configuration.getStyleItems();
            case SESSION_PARAMETER -> configuration.getSessionParameters();
            case SETTINGS_STORAGE -> configuration.getSettingsStorages();
            case XDTO_PACKAGE -> configuration.getXDTOPackages();
            case WS_REFERENCE -> configuration.getWsReferences();
            case ROLE -> configuration.getRoles();
            case SUBSYSTEM -> configuration.getSubsystems();
            case EXCHANGE_PLAN -> configuration.getExchangePlans();
            case CHART_OF_ACCOUNTS -> configuration.getChartsOfAccounts();
            case CHART_OF_CHARACTERISTIC_TYPES -> configuration.getChartsOfCharacteristicTypes();
            case CHART_OF_CALCULATION_TYPES -> configuration.getChartsOfCalculationTypes();
            case BUSINESS_PROCESS -> configuration.getBusinessProcesses();
            case TASK -> configuration.getTasks();
            case COMMON_FORM -> configuration.getCommonForms();
            case COMMON_COMMAND -> configuration.getCommonCommands();
            case COMMON_TEMPLATE -> configuration.getCommonTemplates();
            case COMMON_PICTURE -> configuration.getCommonPictures();
            case SCHEDULED_JOB -> configuration.getScheduledJobs();
            case FILTER_CRITERION -> configuration.getFilterCriteria();
            case DEFINED_TYPE -> configuration.getDefinedTypes();
            case SEQUENCE -> configuration.getSequences();
            case DOCUMENT_JOURNAL -> configuration.getDocumentJournals();
            case DOCUMENT_NUMERATOR -> configuration.getDocumentNumerators();
            case EVENT_SUBSCRIPTION -> configuration.getEventSubscriptions();
            case FUNCTIONAL_OPTION -> configuration.getFunctionalOptions();
            case FUNCTIONAL_OPTIONS_PARAMETER -> configuration.getFunctionalOptionsParameters();
            case WEB_SERVICE -> configuration.getWebServices();
            case HTTP_SERVICE -> configuration.getHttpServices();
            case EXTERNAL_DATA_SOURCE -> configuration.getExternalDataSources();
            case INTEGRATION_SERVICE -> configuration.getIntegrationServices();
            case BOT -> configuration.getBots();
            case WEB_SOCKET_CLIENT -> configuration.getWebSocketClients();
        };
    }
}
