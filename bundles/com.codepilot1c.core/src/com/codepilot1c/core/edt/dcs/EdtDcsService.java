package com.codepilot1c.core.edt.dcs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmNamespace;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaCalculatedField;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.schema.DataSet;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsVariant;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalDataProcessor;
import com._1c.g5.v8.dt.metadata.mdclass.ExternalReport;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Report;
import com._1c.g5.v8.dt.metadata.mdclass.Template;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataProjectReadinessChecker;

/**
 * DCS projections and mutations over EDT metadata model.
 */
public class EdtDcsService {

    private static final String DCS_SCHEMA_NS = "http://g5.1c.ru/v8/dt/data-composition-system/schema"; //$NON-NLS-1$
    private static final String XSI_NS = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;
    private static final String TEMPLATE_DCS_FILE = "Template.dcs"; //$NON-NLS-1$
    private static final String EMPTY_DCS_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <DataCompositionSchema xmlns="http://g5.1c.ru/v8/dt/data-composition-system/schema" xmlns:schema="http://g5.1c.ru/v8/dt/data-composition-system/schema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
            """; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;
    private final MetadataProjectReadinessChecker readinessChecker;

    public EdtDcsService() {
        this(new EdtMetadataGateway());
    }

    EdtDcsService(EdtMetadataGateway gateway) {
        this.gateway = gateway;
        this.readinessChecker = new MetadataProjectReadinessChecker(gateway);
    }

    public DcsSummaryResult getSummary(DcsGetSummaryRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();
        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(project, owner);
        DataCompositionSchema schema = schemaResolution.schema();
        int templateCount = countDcsTemplates(owner);

        return new DcsSummaryResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                owner.eClass().getName(),
                schemaResolution.schemaPresent(),
                schemaResolution.source(),
                schemaResolution.dataSetsCount(),
                schemaResolution.parametersCount(),
                schemaResolution.calculatedFieldsCount(),
                schema != null ? schema.getSettingsVariants().size() : schemaResolution.settingsVariantsCount(),
                templateCount);
    }

    public DcsListNodesResult listNodes(DcsListNodesRequest request) {
        request.validate();
        gateway.ensureValidationRuntimeAvailable();
        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);

        MdObject owner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution schemaResolution = resolveSchema(project, owner);
        DataCompositionSchema schema = schemaResolution.schema();
        if (!schemaResolution.schemaPresent()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + request.normalizedOwnerFqn(),
                    false); //$NON-NLS-1$
        }

        String nodeKind = request.normalizedNodeKind();
        String nameFilter = request.normalizedNameContains();
        List<DcsNodeItem> all = new ArrayList<>();
        if (schema == null && schemaResolution.externalSchema() != null) {
            all.addAll(schemaResolution.externalSchema().nodes(nodeKind));
            return pageNodes(request, nodeKind, nameFilter, all);
        }
        if ("all".equals(nodeKind) || "dataset".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataSet dataSet : schema.getDataSets()) {
                if (dataSet == null) {
                    continue;
                }
                String name = safe(dataSet.getName());
                String details = dataSet.eClass().getName();
                if (dataSet instanceof DataCompositionSchemaDataSetQuery queryDataSet) {
                    details = details + " query=" + compact(queryDataSet.getQuery(), 120); //$NON-NLS-1$
                }
                all.add(new DcsNodeItem("dataset", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "parameter".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataCompositionSchemaParameter parameter : schema.getParameters()) {
                if (parameter == null) {
                    continue;
                }
                String name = safe(parameter.getName());
                String details = "expression=" + compact(parameter.getExpression(), 100); //$NON-NLS-1$
                all.add(new DcsNodeItem("parameter", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "calculated".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields()) {
                if (field == null) {
                    continue;
                }
                String name = safe(field.getDataPath());
                String details = "expression=" + compact(field.getExpression(), 100); //$NON-NLS-1$
                all.add(new DcsNodeItem("calculated", name, details)); //$NON-NLS-1$
            }
        }
        if ("all".equals(nodeKind) || "variant".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
            for (SettingsVariant variant : schema.getSettingsVariants()) {
                if (variant == null) {
                    continue;
                }
                String name = safe(variant.getName());
                String details = variant.getSettings() != null ? "has_settings=true" : "has_settings=false"; //$NON-NLS-1$ //$NON-NLS-2$
                all.add(new DcsNodeItem("variant", name, details)); //$NON-NLS-1$
            }
        }

        return pageNodes(request, nodeKind, nameFilter, all);
    }

    private DcsListNodesResult pageNodes(
            DcsListNodesRequest request,
            String nodeKind,
            String nameFilter,
            List<DcsNodeItem> all
    ) {
        if (nameFilter != null) {
            all = all.stream()
                    .filter(item -> normalize(item.name()).contains(nameFilter))
                    .toList();
        }
        all.sort(Comparator
                .comparing(DcsNodeItem::nodeKind, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DcsNodeItem::name, String.CASE_INSENSITIVE_ORDER));

        int total = all.size();
        int offset = request.effectiveOffset();
        int limit = request.effectiveLimit();
        int start = Math.min(offset, total);
        int end = Math.min(start + limit, total);
        List<DcsNodeItem> page = start >= end ? List.of() : new ArrayList<>(all.subList(start, end));

        return new DcsListNodesResult(
                request.normalizedProjectName(),
                request.normalizedOwnerFqn(),
                nodeKind,
                total,
                page.size(),
                start,
                limit,
                end < total,
                page);
    }

    public DcsCreateMainSchemaResult createMainSchema(DcsCreateMainSchemaRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);
        MdObject ownerForPath = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution existingBefore = resolveSchema(project, ownerForPath);
        Holder<DcsCreateMainSchemaResult> holder = new Holder<>();
        if (existingBefore.schemaPresent() && !request.shouldForceReplace()) {
            OwnerTemplates existingTemplates = resolveOwnerTemplates(ownerForPath);
            holder.value = new DcsCreateMainSchemaResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    ownerForPath.eClass().getName(),
                    existingBefore.templateName() != null
                            ? existingBefore.templateName()
                            : firstDcsTemplateName(existingTemplates),
                    false,
                    false,
                    false,
                    existingBefore.source());
            return holder.value;
        }

        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn());
            OwnerTemplates templates = resolveOwnerTemplates(owner);
            if (templates == null) {
                throw new MetadataOperationException(
                        MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                        "Owner does not support DCS templates: " + owner.eClass().getName(),
                        false); //$NON-NLS-1$
            }

            Template template = findDcsTemplateByName(templates.templates(), request.effectiveTemplateName());
            boolean templateCreated = false;
            if (template == null && request.shouldForceReplace()) {
                template = firstDcsTemplate(templates.templates());
            }
            if (template == null) {
                template = MdClassFactory.eINSTANCE.createTemplate();
                template.setName(request.effectiveTemplateName());
                template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
                templates.templates().add(template);
                templateCreated = true;
            } else if (template.getTemplateType() != TemplateType.DATA_COMPOSITION_SCHEMA) {
                template.setTemplateType(TemplateType.DATA_COMPOSITION_SCHEMA);
            }

            boolean mainBindingUpdated = false;
            if (owner instanceof Report report) {
                report.setMainDataCompositionSchema(template);
                mainBindingUpdated = true;
            } else if (owner instanceof ExternalReport report) {
                report.setMainDataCompositionSchema(template);
                mainBindingUpdated = true;
            }

            holder.value = new DcsCreateMainSchemaResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    owner.eClass().getName(),
                    safe(template.getName()),
                    true,
                    templateCreated,
                    mainBindingUpdated,
                    mainBindingUpdated ? "main" : "templates"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to create DCS schema",
                    false); //$NON-NLS-1$
        }
        ensureExternalSchemaFile(project, ownerForPath, holder.value.templateName(), request.shouldForceReplace());
        return holder.value;
    }

    public DcsUpsertQueryDatasetResult upsertQueryDataset(DcsUpsertQueryDatasetRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);
        MdObject readOwner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution readResolution = resolveSchema(project, readOwner);
        if (readResolution.schema() == null && readResolution.externalSchema() != null) {
            return upsertExternalQueryDataset(request, readResolution.externalSchema());
        }

        Holder<DcsUpsertQueryDatasetResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(project, owner, request.normalizedOwnerFqn());

            DataCompositionSchemaDataSetQuery dataset = findQueryDataset(schema, request.normalizedDatasetName());
            boolean created = false;
            if (dataset == null) {
                dataset = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
                dataset.setName(request.normalizedDatasetName());
                schema.getDataSets().add(dataset);
                created = true;
            }
            if (request.normalizedQuery() != null) {
                dataset.setQuery(request.normalizedQuery());
            }
            if (request.normalizedDataSource() != null) {
                dataset.setDataSource(request.normalizedDataSource());
            }
            if (request.autoFillAvailableFields() != null) {
                dataset.setAutoFillAvailableFields(request.autoFillAvailableFields().booleanValue());
            }
            if (request.useQueryGroupIfPossible() != null) {
                dataset.setUseQueryGroupIfPossible(request.useQueryGroupIfPossible().booleanValue());
            }

            holder.value = new DcsUpsertQueryDatasetResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(dataset.getName()),
                    created,
                    safe(dataset.getQuery()),
                    safe(dataset.getDataSource()),
                    dataset.isAutoFillAvailableFields(),
                    dataset.isUseQueryGroupIfPossible());
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS query dataset",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    public DcsUpsertParameterResult upsertParameter(DcsUpsertParameterRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);
        MdObject readOwner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution readResolution = resolveSchema(project, readOwner);
        if (readResolution.schema() == null && readResolution.externalSchema() != null) {
            return upsertExternalParameter(request, readResolution.externalSchema());
        }

        Holder<DcsUpsertParameterResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(project, owner, request.normalizedOwnerFqn());

            DataCompositionSchemaParameter parameter = findParameter(schema, request.normalizedParameterName());
            boolean created = false;
            if (parameter == null) {
                parameter = DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
                parameter.setName(request.normalizedParameterName());
                schema.getParameters().add(parameter);
                created = true;
            }
            if (request.normalizedExpression() != null) {
                parameter.setExpression(request.normalizedExpression());
            }
            if (request.availableAsField() != null) {
                parameter.setAvailableAsField(request.availableAsField().booleanValue());
            }
            if (request.valueListAllowed() != null) {
                parameter.setValueListAllowed(request.valueListAllowed().booleanValue());
            }
            if (request.denyIncompleteValues() != null) {
                parameter.setDenyIncompleteValues(request.denyIncompleteValues().booleanValue());
            }
            if (request.useRestriction() != null) {
                parameter.setUseRestriction(request.useRestriction().booleanValue());
            }

            holder.value = new DcsUpsertParameterResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(parameter.getName()),
                    created,
                    safe(parameter.getExpression()),
                    parameter.isAvailableAsField(),
                    parameter.isValueListAllowed(),
                    parameter.isDenyIncompleteValues(),
                    parameter.isUseRestriction());
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS parameter",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    public DcsUpsertCalculatedFieldResult upsertCalculatedField(DcsUpsertCalculatedFieldRequest request) {
        request.validate();
        gateway.ensureMutationRuntimeAvailable();

        IProject project = resolveProject(request.normalizedProjectName());
        readinessChecker.ensureReady(project);
        MdObject readOwner = resolveOwner(request.normalizedProjectName(), request.normalizedOwnerFqn());
        SchemaResolution readResolution = resolveSchema(project, readOwner);
        if (readResolution.schema() == null && readResolution.externalSchema() != null) {
            return upsertExternalCalculatedField(request, readResolution.externalSchema());
        }

        Holder<DcsUpsertCalculatedFieldResult> holder = new Holder<>();
        executeWrite(project, transaction -> {
            MdObject owner = resolveOwnerInTransaction(
                    transaction,
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn());
            DataCompositionSchema schema = requireSchema(project, owner, request.normalizedOwnerFqn());

            DataCompositionSchemaCalculatedField field = findCalculatedField(schema, request.normalizedDataPath());
            boolean created = false;
            if (field == null) {
                field = DcsFactory.eINSTANCE.createDataCompositionSchemaCalculatedField();
                field.setDataPath(request.normalizedDataPath());
                schema.getCalculatedFields().add(field);
                created = true;
            }
            if (request.normalizedExpression() != null) {
                field.setExpression(request.normalizedExpression());
            }
            if (request.normalizedPresentationExpression() != null) {
                field.setPresentationExpression(request.normalizedPresentationExpression());
            }

            holder.value = new DcsUpsertCalculatedFieldResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    safe(field.getDataPath()),
                    created,
                    safe(field.getExpression()),
                    safe(field.getPresentationExpression()));
            return null;
        });

        if (holder.value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to upsert DCS calculated field",
                    false); //$NON-NLS-1$
        }
        return holder.value;
    }

    private IProject resolveProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "Project not found: " + projectName,
                    false); //$NON-NLS-1$
        }
        return project;
    }

    private MdObject resolveOwnerInTransaction(
            IBmPlatformTransaction transaction,
            String projectName,
            String ownerFqn
    ) {
        IProject project = resolveProject(projectName);
        IBmNamespace namespace = gateway.getBmModelManager().getBmNamespace(project);
        MdObject txOwnerByFqn = castMdObject(transaction.getTopObjectByFqn(namespace, ownerFqn));
        if (txOwnerByFqn != null) {
            return txOwnerByFqn;
        }
        MdObject txOwnerFromConfiguration = resolveOwnerFromTransactionConfiguration(transaction, projectName, ownerFqn);
        if (txOwnerFromConfiguration != null) {
            return txOwnerFromConfiguration;
        }

        MdObject owner = resolveOwner(projectName, ownerFqn);
        MdObject txOwner = castMdObject(transaction.toTransactionObject(owner));
        if (txOwner == null) {
            txOwner = resolveOwnerByUri(transaction, owner);
        }
        if (txOwner == null && isExternalOwner(owner)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                    "External owner is not attached to BM transaction context: "
                            + owner.eClass().getName()
                            + "." + safe(owner.getName())
                            + " bmObject=" + (owner instanceof IBmObject), //$NON-NLS-1$
                    false);
        }
        return txOwner != null ? txOwner : owner;
    }

    private MdObject resolveOwnerFromTransactionConfiguration(
            IBmPlatformTransaction transaction,
            String projectName,
            String ownerFqn
    ) {
        IProject project = resolveProject(projectName);
        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        if (configuration == null) {
            return null;
        }
        EObject txConfiguration = transaction.toTransactionObject(configuration);
        if (txConfiguration instanceof Configuration configurationInTransaction) {
            return findInConfiguration(configurationInTransaction, ownerFqn);
        }
        return null;
    }

    private MdObject resolveOwnerByUri(IBmPlatformTransaction transaction, MdObject owner) {
        if (transaction == null || owner == null) {
            return null;
        }
        try {
            EObject byUri = transaction.getObjectByUri(EcoreUtil.getURI(owner));
            return castMdObject(byUri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MdObject castMdObject(EObject object) {
        return object instanceof MdObject mdObject ? mdObject : null;
    }

    private MdObject resolveOwner(String projectName, String ownerFqn) {
        IProject project = resolveProject(projectName);

        IExternalObjectProject externalProject = asExternalProject(project);
        if (externalProject != null) {
            MdObject external = findInExternalProject(externalProject, ownerFqn);
            if (external != null) {
                return external;
            }
        }

        Configuration configuration = gateway.getConfigurationProvider().getConfiguration(project);
        if (configuration == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Configuration is unavailable for project: " + projectName,
                    false); //$NON-NLS-1$
        }
        MdObject object = findInConfiguration(configuration, ownerFqn);
        if (object == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "Owner object not found: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return object;
    }

    private IExternalObjectProject asExternalProject(IProject project) {
        try {
            var v8Project = gateway.getV8ProjectManager().getProject(project);
            if (v8Project instanceof IExternalObjectProject externalProject) {
                return externalProject;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MdObject findInExternalProject(IExternalObjectProject project, String ownerFqn) {
        String normalizedRef = normalize(ownerFqn);
        for (MdObject object : project.getExternalObjects(MdObject.class)) {
            if (object == null) {
                continue;
            }
            String shortRef = object.eClass().getName() + "." + safe(object.getName()); //$NON-NLS-1$
            if (normalize(shortRef).equals(normalizedRef) || normalize(object.getName()).equals(normalizedRef)) {
                return object;
            }
        }
        return null;
    }

    private MdObject findInConfiguration(Configuration configuration, String ownerFqn) {
        MdObject topLevel = findTopLevelOwner(configuration, ownerFqn);
        if (topLevel != null) {
            return topLevel;
        }

        String normalizedRef = normalize(ownerFqn);
        TreeIterator<EObject> it = configuration.eAllContents();
        while (it.hasNext()) {
            EObject next = it.next();
            if (!(next instanceof MdObject mdObject)) {
                continue;
            }
            String shortRef = mdObject.eClass().getName() + "." + safe(mdObject.getName()); //$NON-NLS-1$
            if (normalize(shortRef).equals(normalizedRef) || normalize(mdObject.getName()).equals(normalizedRef)) {
                return mdObject;
            }
        }
        return null;
    }

    private MdObject findTopLevelOwner(Configuration configuration, String ownerFqn) {
        if (configuration == null || ownerFqn == null || ownerFqn.isBlank()) {
            return null;
        }
        String[] parts = ownerFqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2) {
            return null;
        }
        String type = normalizeToken(parts[0]);
        String name = parts[1];
        List<? extends MdObject> candidates = switch (type) {
            case "report", "отчет", "отчёт" -> configuration.getReports(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dataprocessor", "обработка" -> configuration.getDataProcessors(); //$NON-NLS-1$ //$NON-NLS-2$
            default -> List.of();
        };
        for (MdObject candidate : candidates) {
            if (candidate != null && name.equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private SchemaResolution resolveSchema(IProject project, MdObject owner) {
        if (owner instanceof Report report) {
            Template mainTemplate = asTemplate(report.getMainDataCompositionSchema());
            ExternalDcsSchema external = readExternalSchema(project, owner, mainTemplate);
            if (external != null) {
                return new SchemaResolution(null, "main", external.templateName(), external); //$NON-NLS-1$
            }
            DataCompositionSchema schema = extractSchema(mainTemplate);
            if (schema != null) {
                return new SchemaResolution(schema, "main", safe(mainTemplate.getName()), null); //$NON-NLS-1$
            }
            return resolveFromTemplateList(project, owner, report.getTemplates());
        }
        if (owner instanceof ExternalReport report) {
            Template mainTemplate = asTemplate(report.getMainDataCompositionSchema());
            ExternalDcsSchema external = readExternalSchema(project, owner, mainTemplate);
            if (external != null) {
                return new SchemaResolution(null, "main", external.templateName(), external); //$NON-NLS-1$
            }
            DataCompositionSchema schema = extractSchema(mainTemplate);
            if (schema != null) {
                return new SchemaResolution(schema, "main", safe(mainTemplate.getName()), null); //$NON-NLS-1$
            }
            return resolveFromTemplateList(project, owner, report.getTemplates());
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return resolveFromTemplateList(project, owner, dataProcessor.getTemplates());
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return resolveFromTemplateList(project, owner, dataProcessor.getTemplates());
        }
        return new SchemaResolution(null, "none", null, null); //$NON-NLS-1$
    }

    private SchemaResolution resolveFromTemplateList(
            IProject project,
            MdObject owner,
            List<? extends Template> templates
    ) {
        ExternalDcsSchema external = findExternalInTemplates(project, owner, templates);
        if (external != null) {
            return new SchemaResolution(null, "templates", external.templateName(), external); //$NON-NLS-1$
        }
        DataCompositionSchema schema = findInTemplates(templates);
        if (schema != null) {
            return new SchemaResolution(schema, "templates", findTemplateName(schema, templates), null); //$NON-NLS-1$
        }
        return new SchemaResolution(null, "templates", firstDcsTemplateName(templates), null); //$NON-NLS-1$
    }

    private OwnerTemplates resolveOwnerTemplates(MdObject owner) {
        if (owner instanceof Report report) {
            return new OwnerTemplates(report.getTemplates());
        }
        if (owner instanceof ExternalReport report) {
            return new OwnerTemplates(report.getTemplates());
        }
        if (owner instanceof DataProcessor dataProcessor) {
            return new OwnerTemplates(dataProcessor.getTemplates());
        }
        if (owner instanceof ExternalDataProcessor dataProcessor) {
            return new OwnerTemplates(dataProcessor.getTemplates());
        }
        return null;
    }

    private int countDcsTemplates(MdObject owner) {
        OwnerTemplates templates = resolveOwnerTemplates(owner);
        return templates == null ? 0 : countInTemplates(templates.templates());
    }

    private int countInTemplates(List<? extends Template> templates) {
        int count = 0;
        for (Template template : templates) {
            if (isDcsTemplate(template)) {
                count++;
            }
        }
        return count;
    }

    private DataCompositionSchema requireSchema(IProject project, MdObject owner, String ownerFqn) {
        SchemaResolution resolution = resolveSchema(project, owner);
        if (resolution.schema() == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_SCHEMA_NOT_FOUND,
                    "DCS schema is not configured for owner: " + ownerFqn,
                    false); //$NON-NLS-1$
        }
        return resolution.schema();
    }

    private String findTemplateName(DataCompositionSchema schema, List<? extends Template> templates) {
        for (Template template : templates) {
            if (template != null && template.getTemplate() == schema) {
                return safe(template.getName());
            }
        }
        return ""; //$NON-NLS-1$
    }

    private String firstDcsTemplateName(OwnerTemplates templates) {
        return templates == null ? "" : firstDcsTemplateName(templates.templates()); //$NON-NLS-1$
    }

    private String firstDcsTemplateName(List<? extends Template> templates) {
        Template template = firstDcsTemplate(templates);
        return template == null ? "" : safe(template.getName()); //$NON-NLS-1$
    }

    private Template firstDcsTemplate(List<? extends Template> templates) {
        if (templates == null) {
            return null;
        }
        for (Template template : templates) {
            if (isDcsTemplate(template)) {
                return template;
            }
        }
        return null;
    }

    private Template findDcsTemplateByName(List<? extends Template> templates, String name) {
        if (templates == null) {
            return null;
        }
        String token = normalize(name);
        for (Template template : templates) {
            if (isDcsTemplate(template) && normalize(template.getName()).equals(token)) {
                return template;
            }
        }
        return null;
    }

    private boolean isDcsTemplate(BasicTemplate template) {
        return template != null && template.getTemplateType() == TemplateType.DATA_COMPOSITION_SCHEMA;
    }

    private Template asTemplate(BasicTemplate template) {
        return template instanceof Template concrete ? concrete : null;
    }

    private DataCompositionSchemaDataSetQuery findQueryDataset(DataCompositionSchema schema, String name) {
        String token = normalize(name);
        for (DataSet dataSet : schema.getDataSets()) {
            if (dataSet instanceof DataCompositionSchemaDataSetQuery query
                    && normalize(query.getName()).equals(token)) {
                return query;
            }
        }
        return null;
    }

    private DataCompositionSchemaParameter findParameter(DataCompositionSchema schema, String name) {
        String token = normalize(name);
        for (DataCompositionSchemaParameter parameter : schema.getParameters()) {
            if (parameter != null && normalize(parameter.getName()).equals(token)) {
                return parameter;
            }
        }
        return null;
    }

    private DataCompositionSchemaCalculatedField findCalculatedField(DataCompositionSchema schema, String dataPath) {
        String token = normalize(dataPath);
        for (DataCompositionSchemaCalculatedField field : schema.getCalculatedFields()) {
            if (field != null && normalize(field.getDataPath()).equals(token)) {
                return field;
            }
        }
        return null;
    }

    private DataCompositionSchema findInTemplates(List<? extends Template> templates) {
        if (templates == null) {
            return null;
        }
        for (Template template : templates) {
            DataCompositionSchema schema = extractSchema(template);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private ExternalDcsSchema findExternalInTemplates(
            IProject project,
            MdObject owner,
            List<? extends Template> templates
    ) {
        if (templates == null) {
            return null;
        }
        for (Template template : templates) {
            ExternalDcsSchema schema = readExternalSchema(project, owner, template);
            if (schema != null) {
                return schema;
            }
        }
        return null;
    }

    private ExternalDcsSchema readExternalSchema(IProject project, MdObject owner, Template template) {
        if (project == null || owner == null || !isDcsTemplate(template)) {
            return null;
        }
        IFile file = resolveExternalSchemaFile(project, owner, safe(template.getName()));
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            Document document = readDcsDocument(file);
            Element root = document.getDocumentElement();
            if (root == null || !"DataCompositionSchema".equals(localName(root))) { //$NON-NLS-1$
                return null;
            }
            return new ExternalDcsSchema(file, safe(template.getName()), nodesFrom(root, "dataSets"), //$NON-NLS-1$
                    nodesFrom(root, "parameters"), nodesFrom(root, "calculatedFields"), //$NON-NLS-1$ //$NON-NLS-2$
                    nodesFrom(root, "settingsVariants").size()); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to read external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private List<ExternalDcsNode> nodesFrom(Element root, String elementName) {
        List<ExternalDcsNode> result = new ArrayList<>();
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && elementName.equals(localName(element))) {
                result.add(new ExternalDcsNode(
                        elementName,
                        attr(element, "name"), //$NON-NLS-1$
                        attr(element, "dataPath"), //$NON-NLS-1$
                        attr(element, "expression"), //$NON-NLS-1$
                        attr(element, "query"), //$NON-NLS-1$
                        attr(element, "dataSource"), //$NON-NLS-1$
                        attr(element, "presentationExpression"), //$NON-NLS-1$
                        attr(element, "autoFillAvailableFields"), //$NON-NLS-1$
                        attr(element, "useQueryGroupIfPossible"), //$NON-NLS-1$
                        attr(element, "availableAsField"), //$NON-NLS-1$
                        attr(element, "valueListAllowed"), //$NON-NLS-1$
                        attr(element, "denyIncompleteValues"), //$NON-NLS-1$
                        attr(element, "useRestriction"), //$NON-NLS-1$
                        attr(element, "type"))); //$NON-NLS-1$
            }
        }
        return result;
    }

    private DcsUpsertQueryDatasetResult upsertExternalQueryDataset(
            DcsUpsertQueryDatasetRequest request,
            ExternalDcsSchema externalSchema
    ) {
        try {
            Document document = readDcsDocument(externalSchema.file());
            Element root = document.getDocumentElement();
            Element dataset = findChildByKey(root, "dataSets", "name", request.normalizedDatasetName()); //$NON-NLS-1$ //$NON-NLS-2$
            boolean created = false;
            if (dataset == null) {
                dataset = document.createElementNS(DCS_SCHEMA_NS, "dataSets"); //$NON-NLS-1$
                dataset.setAttributeNS(XSI_NS, "xsi:type", "schema:DataCompositionSchemaDataSetQuery"); //$NON-NLS-1$ //$NON-NLS-2$
                dataset.setAttribute("name", request.normalizedDatasetName()); //$NON-NLS-1$
                root.appendChild(dataset);
                created = true;
            }
            setOptional(dataset, "query", request.normalizedQuery()); //$NON-NLS-1$
            setOptional(dataset, "dataSource", request.normalizedDataSource()); //$NON-NLS-1$
            setOptional(dataset, "autoFillAvailableFields", request.autoFillAvailableFields()); //$NON-NLS-1$
            setOptional(dataset, "useQueryGroupIfPossible", request.useQueryGroupIfPossible()); //$NON-NLS-1$
            writeDcsDocument(externalSchema.file(), document);
            return new DcsUpsertQueryDatasetResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    attr(dataset, "name"), //$NON-NLS-1$
                    created,
                    attr(dataset, "query"), //$NON-NLS-1$
                    attr(dataset, "dataSource"), //$NON-NLS-1$
                    boolAttr(dataset, "autoFillAvailableFields", true), //$NON-NLS-1$
                    boolAttr(dataset, "useQueryGroupIfPossible", true)); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw externalDcsMutationFailed(externalSchema.file(), e);
        }
    }

    private DcsUpsertParameterResult upsertExternalParameter(
            DcsUpsertParameterRequest request,
            ExternalDcsSchema externalSchema
    ) {
        try {
            Document document = readDcsDocument(externalSchema.file());
            Element root = document.getDocumentElement();
            Element parameter = findChildByKey(root, "parameters", "name", request.normalizedParameterName()); //$NON-NLS-1$ //$NON-NLS-2$
            boolean created = false;
            if (parameter == null) {
                parameter = document.createElementNS(DCS_SCHEMA_NS, "parameters"); //$NON-NLS-1$
                parameter.setAttribute("name", request.normalizedParameterName()); //$NON-NLS-1$
                root.appendChild(parameter);
                created = true;
            }
            setOptional(parameter, "expression", request.normalizedExpression()); //$NON-NLS-1$
            setOptional(parameter, "availableAsField", request.availableAsField()); //$NON-NLS-1$
            setOptional(parameter, "valueListAllowed", request.valueListAllowed()); //$NON-NLS-1$
            setOptional(parameter, "denyIncompleteValues", request.denyIncompleteValues()); //$NON-NLS-1$
            setOptional(parameter, "useRestriction", request.useRestriction()); //$NON-NLS-1$
            writeDcsDocument(externalSchema.file(), document);
            return new DcsUpsertParameterResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    attr(parameter, "name"), //$NON-NLS-1$
                    created,
                    attr(parameter, "expression"), //$NON-NLS-1$
                    boolAttr(parameter, "availableAsField", true), //$NON-NLS-1$
                    boolAttr(parameter, "valueListAllowed", false), //$NON-NLS-1$
                    boolAttr(parameter, "denyIncompleteValues", false), //$NON-NLS-1$
                    boolAttr(parameter, "useRestriction", false)); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw externalDcsMutationFailed(externalSchema.file(), e);
        }
    }

    private DcsUpsertCalculatedFieldResult upsertExternalCalculatedField(
            DcsUpsertCalculatedFieldRequest request,
            ExternalDcsSchema externalSchema
    ) {
        try {
            Document document = readDcsDocument(externalSchema.file());
            Element root = document.getDocumentElement();
            Element field = findChildByKey(root, "calculatedFields", "dataPath", request.normalizedDataPath()); //$NON-NLS-1$ //$NON-NLS-2$
            boolean created = false;
            if (field == null) {
                field = document.createElementNS(DCS_SCHEMA_NS, "calculatedFields"); //$NON-NLS-1$
                field.setAttribute("dataPath", request.normalizedDataPath()); //$NON-NLS-1$
                root.appendChild(field);
                created = true;
            }
            setOptional(field, "expression", request.normalizedExpression()); //$NON-NLS-1$
            setOptional(field, "presentationExpression", request.normalizedPresentationExpression()); //$NON-NLS-1$
            writeDcsDocument(externalSchema.file(), document);
            return new DcsUpsertCalculatedFieldResult(
                    request.normalizedProjectName(),
                    request.normalizedOwnerFqn(),
                    attr(field, "dataPath"), //$NON-NLS-1$
                    created,
                    attr(field, "expression"), //$NON-NLS-1$
                    attr(field, "presentationExpression")); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw externalDcsMutationFailed(externalSchema.file(), e);
        }
    }

    private MetadataOperationException externalDcsMutationFailed(IFile file, RuntimeException e) {
        return new MetadataOperationException(
                MetadataOperationCode.EDT_TRANSACTION_FAILED,
                "Failed to update external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                false,
                e);
    }

    private void ensureExternalSchemaFile(IProject project, MdObject owner, String templateName, boolean forceReplace) {
        IFile file = resolveExternalSchemaFile(project, owner, templateName);
        if (file == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.DCS_OWNER_KIND_UNSUPPORTED,
                    "Cannot resolve external DCS path for owner: " + owner.eClass().getName(), //$NON-NLS-1$
                    false);
        }
        if (file.exists() && !forceReplace) {
            return;
        }
        writeDcsText(file, EMPTY_DCS_XML);
    }

    private IFile resolveExternalSchemaFile(IProject project, MdObject owner, String templateName) {
        String folder = topFolderForOwner(owner);
        if (folder == null || templateName == null || templateName.isBlank()) {
            return null;
        }
        String path = "src/" + folder + "/" + safe(owner.getName()) //$NON-NLS-1$ //$NON-NLS-2$
                + "/Templates/" + templateName + "/" + TEMPLATE_DCS_FILE; //$NON-NLS-1$ //$NON-NLS-2$
        return project.getFile(path);
    }

    private String topFolderForOwner(MdObject owner) {
        if (owner instanceof Report || owner instanceof ExternalReport) {
            return "Reports"; //$NON-NLS-1$
        }
        if (owner instanceof DataProcessor || owner instanceof ExternalDataProcessor) {
            return "DataProcessors"; //$NON-NLS-1$
        }
        return null;
    }

    private Document readDcsDocument(IFile file) {
        try (InputStream input = file.getContents()) {
            DocumentBuilderFactory factory = newDocumentBuilderFactory();
            return factory.newDocumentBuilder().parse(input);
        } catch (IOException | CoreException | ParserConfigurationException | SAXException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to parse external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory;
    }

    private void writeDcsDocument(IFile file, Document document) {
        try {
            Element root = document.getDocumentElement();
            if (root != null) {
                root.setAttribute("xmlns:schema", DCS_SCHEMA_NS); //$NON-NLS-1$
                root.setAttribute("xmlns:xsi", XSI_NS); //$NON-NLS-1$
            }
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.INDENT, "yes"); //$NON-NLS-1$
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            writeDcsText(file, writer.toString());
        } catch (TransformerException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to serialize external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private void writeDcsText(IFile file, String content) {
        try {
            createParentsIfMissing(file);
            try (ByteArrayInputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                if (file.exists()) {
                    file.setContents(input, IResource.FORCE, null);
                } else {
                    file.create(input, IResource.FORCE, null);
                }
            }
            file.refreshLocal(IResource.DEPTH_ZERO, null);
        } catch (IOException | CoreException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "Failed to write external DCS schema: " + file.getProjectRelativePath(), //$NON-NLS-1$
                    false,
                    e);
        }
    }

    private void createParentsIfMissing(IFile file) throws CoreException {
        IContainer parent = file.getParent();
        if (parent instanceof org.eclipse.core.resources.IFolder folder && !folder.exists()) {
            createFolderIfMissing(folder);
        }
    }

    private void createFolderIfMissing(org.eclipse.core.resources.IFolder folder) throws CoreException {
        IContainer parent = folder.getParent();
        if (parent instanceof org.eclipse.core.resources.IFolder parentFolder && !parentFolder.exists()) {
            createFolderIfMissing(parentFolder);
        }
        if (!folder.exists()) {
            folder.create(true, true, null);
        }
    }

    private Element findChildByKey(Element root, String elementName, String key, String value) {
        String token = normalize(value);
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element
                    && elementName.equals(localName(element))
                    && normalize(attr(element, key)).equals(token)) {
                return element;
            }
        }
        return null;
    }

    private void setOptional(Element element, String name, String value) {
        if (value != null) {
            element.setAttribute(name, value);
        }
    }

    private void setOptional(Element element, String name, Boolean value) {
        if (value != null) {
            element.setAttribute(name, Boolean.toString(value.booleanValue()));
        }
    }

    private String attr(Element element, String name) {
        if ("type".equals(name) && element.hasAttributeNS(XSI_NS, "type")) { //$NON-NLS-1$ //$NON-NLS-2$
            return element.getAttributeNS(XSI_NS, "type"); //$NON-NLS-1$
        }
        return element.hasAttribute(name) ? element.getAttribute(name) : ""; //$NON-NLS-1$
    }

    private boolean boolAttr(Element element, String name, boolean defaultValue) {
        return element.hasAttribute(name) ? Boolean.parseBoolean(element.getAttribute(name)) : defaultValue;
    }

    private String localName(Node node) {
        String local = node.getLocalName();
        return local != null ? local : node.getNodeName();
    }

    private DataCompositionSchema extractSchema(BasicTemplate template) {
        if (template == null) {
            return null;
        }
        TemplateType templateType = template.getTemplateType();
        if (templateType != TemplateType.DATA_COMPOSITION_SCHEMA) {
            return null;
        }
        EObject templateObject = template.getTemplate();
        if (templateObject instanceof DataCompositionSchema schema) {
            return schema;
        }
        return null;
    }

    private <T> T executeWrite(IProject project, PlatformTransactionTask<T> task) {
        try {
            return gateway.getGlobalEditingContext().execute(
                    "CodePilot1C.DcsWrite", //$NON-NLS-1$
                    project,
                    this,
                    task::execute);
        } catch (MetadataOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EDT_TRANSACTION_FAILED,
                    "DCS transaction failed: " + e.getMessage(),
                    false,
                    e); //$NON-NLS-1$
        }
    }

    private String compact(String value, int max) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "..."; //$NON-NLS-1$
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim()
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }

    private boolean isExternalOwner(MdObject owner) {
        return owner instanceof ExternalReport || owner instanceof ExternalDataProcessor;
    }

    private record SchemaResolution(
            DataCompositionSchema schema,
            String source,
            String templateName,
            ExternalDcsSchema externalSchema
    ) {
        private boolean schemaPresent() {
            return schema != null || externalSchema != null;
        }

        private int dataSetsCount() {
            return schema != null ? schema.getDataSets().size() : externalSchemaCount(NodeKind.DATASET);
        }

        private int parametersCount() {
            return schema != null ? schema.getParameters().size() : externalSchemaCount(NodeKind.PARAMETER);
        }

        private int calculatedFieldsCount() {
            return schema != null ? schema.getCalculatedFields().size() : externalSchemaCount(NodeKind.CALCULATED);
        }

        private int settingsVariantsCount() {
            return externalSchema == null ? 0 : externalSchema.settingsVariantsCount();
        }

        private int externalSchemaCount(NodeKind kind) {
            if (externalSchema == null) {
                return 0;
            }
            return switch (kind) {
                case DATASET -> externalSchema.dataSets().size();
                case PARAMETER -> externalSchema.parameters().size();
                case CALCULATED -> externalSchema.calculatedFields().size();
            };
        }
    }

    private record ExternalDcsSchema(
            IFile file,
            String templateName,
            List<ExternalDcsNode> dataSets,
            List<ExternalDcsNode> parameters,
            List<ExternalDcsNode> calculatedFields,
            int settingsVariantsCount
    ) {
        private List<DcsNodeItem> nodes(String nodeKind) {
            List<DcsNodeItem> result = new ArrayList<>();
            if ("all".equals(nodeKind) || "dataset".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
                dataSets.forEach(node -> result.add(node.toNodeItem()));
            }
            if ("all".equals(nodeKind) || "parameter".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
                parameters.forEach(node -> result.add(node.toNodeItem()));
            }
            if ("all".equals(nodeKind) || "calculated".equals(nodeKind)) { //$NON-NLS-1$ //$NON-NLS-2$
                calculatedFields.forEach(node -> result.add(node.toNodeItem()));
            }
            return result;
        }
    }

    private record ExternalDcsNode(
            String elementName,
            String name,
            String dataPath,
            String expression,
            String query,
            String dataSource,
            String presentationExpression,
            String autoFillAvailableFields,
            String useQueryGroupIfPossible,
            String availableAsField,
            String valueListAllowed,
            String denyIncompleteValues,
            String useRestriction,
            String xsiType
    ) {
        private DcsNodeItem toNodeItem() {
            return switch (elementName) {
                case "dataSets" -> new DcsNodeItem("dataset", name, //$NON-NLS-1$ //$NON-NLS-2$
                        (xsiType.isBlank() ? "DataCompositionSchemaDataSetQuery" : xsiType) //$NON-NLS-1$
                                + " query=" + query); //$NON-NLS-1$
                case "parameters" -> new DcsNodeItem("parameter", name, "expression=" + expression); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                case "calculatedFields" -> new DcsNodeItem("calculated", dataPath, "expression=" + expression); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                default -> new DcsNodeItem(elementName, name, ""); //$NON-NLS-1$
            };
        }
    }

    private enum NodeKind {
        DATASET,
        PARAMETER,
        CALCULATED
    }

    private record OwnerTemplates(List<Template> templates) {
    }

    @FunctionalInterface
    private interface PlatformTransactionTask<T> {
        T execute(IBmPlatformTransaction transaction);
    }

    private static final class Holder<T> {
        private T value;
    }
}
