package com.codepilot1c.core.edt.metadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

/**
 * The content file EDT keeps next to a template's metadata, {@code Templates/<Name>/Template.<ext>},
 * chosen by the template type.
 *
 * <p>Every EDT content importer reads only its own extension. EDT 2025.2.3 resolves a template's
 * content file through {@code com._1c.g5.v8.dt.ide.QualifiedNameFilePathConverter} (field
 * {@code TEMPLATE_EXTENSIONS}): text document {@code txt}, binary data {@code bin}, HTML document
 * {@code htmldoc}, data composition schema {@code dcs}, spreadsheet {@code mxlx}, active document
 * {@code axdt}, geographical schema {@code geos}, graphical schema {@code scheme}, add-in
 * {@code addin}. {@code add_metadata_child} used to write {@code Template.mxl} for every type, so a
 * text or binary template got a file no importer reads while the tool reported success.</p>
 *
 * <ul>
 *   <li>Spreadsheet templates keep the MOXCEL {@code Template.mxl} that {@code EdtMetadataService}
 *       builds: {@code render_template} and {@code inspect_template} work with that file. EDT's own
 *       spreadsheet importer reads {@code mxlx}; that divergence is androman/codepilot1c#122.</li>
 *   <li>Text and binary templates get an empty file, an HTML template gets a document without
 *       pages in the dialect EDT serializes ({@code htmldoc:HtmlDocument}).</li>
 *   <li>DCS templates get no file here: {@code Template.dcs} belongs to {@code dcs_manage}.</li>
 *   <li>Active document, geographical and graphical schema and add-in templates are refused before
 *       anything is created: codepilot has no verified empty content for them.</li>
 * </ul>
 */
public enum TemplateArtifact {

    SPREADSHEET("Template.mxl", null), //$NON-NLS-1$
    TEXT("Template.txt", ""), //$NON-NLS-1$ //$NON-NLS-2$
    BINARY("Template.bin", ""), //$NON-NLS-1$ //$NON-NLS-2$
    HTML("Template.htmldoc", //$NON-NLS-1$
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" //$NON-NLS-1$
                    + "<htmldoc:HtmlDocument xmlns:htmldoc=\"http://g5.1c.ru/v8/dt/html-document\"/>\n"), //$NON-NLS-1$
    /** No content file is created by {@code add_metadata_child}: the DCS schema file belongs to {@code dcs_manage}. */
    NONE(null, null);

    /** Property of {@code add_metadata_child} (top level or a batch child) that selects the template type. */
    public static final String PROPERTY = "template_type"; //$NON-NLS-1$

    private static final String ACCEPTED_VALUES =
            "spreadsheet, text, binary, html, dcs (EDT names are accepted too: SpreadsheetDocument, TextDocument, " //$NON-NLS-1$
                    + "BinaryData, HTMLDocument, DataCompositionSchema)"; //$NON-NLS-1$

    private final String fileName;
    private final byte[] initialContent;

    TemplateArtifact(String fileName, String initialContent) {
        this.fileName = fileName;
        this.initialContent = initialContent == null ? null : initialContent.getBytes(StandardCharsets.UTF_8);
    }

    /** File name inside {@code Templates/<Name>/}, or {@code null} when no file is created here. */
    public String fileName() {
        return fileName;
    }

    public boolean hasFile() {
        return fileName != null;
    }

    /**
     * Bytes of a new, empty artifact; {@code null} for {@link #SPREADSHEET} (MOXCEL is serialized by
     * {@code EdtMetadataService}) and {@link #NONE}. A fresh copy on every call.
     */
    public byte[] initialContent() {
        return initialContent == null ? null : initialContent.clone();
    }

    /** Project-relative path of the artifact, e.g. {@code src/DataProcessors/Отчёт/Templates/Макет/Template.txt}. */
    public String relativePath(String topFolder, String topName, String templateName) {
        if (!hasFile()) {
            return null;
        }
        return "src/" + topFolder + "/" + topName + "/Templates/" + templateName + "/" + fileName; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Artifact for a template type.
     *
     * @throws MetadataOperationException {@link MetadataOperationCode#INVALID_PROPERTY_VALUE} for a type
     *         whose content codepilot cannot create
     */
    public static TemplateArtifact forType(TemplateType type) {
        if (type == null) {
            return SPREADSHEET;
        }
        return switch (type) {
            case SPREADSHEET_DOCUMENT -> SPREADSHEET;
            case TEXT_DOCUMENT -> TEXT;
            case BINARY_DATA -> BINARY;
            case HTML_DOCUMENT -> HTML;
            case DATA_COMPOSITION_SCHEMA, DATA_COMPOSITION_APPEARANCE_TEMPLATE -> NONE;
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    PROPERTY + "=" + type.getLiteral() + ": add_metadata_child does not create " + type.getLiteral() //$NON-NLS-1$ //$NON-NLS-2$
                            + " templates. EDT keeps their content in " + edtFileName(type) //$NON-NLS-1$
                            + " and codepilot has no verified empty content for it, so the template would be left" //$NON-NLS-1$
                            + " with a file EDT does not read. Create the template in EDT, or use one of: " //$NON-NLS-1$
                            + ACCEPTED_VALUES,
                    false);
        };
    }

    /**
     * Template type requested by {@code properties.template_type}; absent or blank means a spreadsheet.
     *
     * @throws MetadataOperationException {@link MetadataOperationCode#INVALID_PROPERTY_VALUE} for a value
     *         that names no template type: silently making it a spreadsheet is what hid wrong requests
     */
    public static TemplateType resolveType(Map<String, Object> properties) {
        Object raw = properties == null ? null : properties.get(PROPERTY);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return TemplateType.SPREADSHEET_DOCUMENT;
        }
        String key = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return switch (key) {
            case "spreadsheet", "spreadsheetdocument", "mxl", "табличныйдокумент" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    TemplateType.SPREADSHEET_DOCUMENT;
            case "text", "textdocument", "текстовыйдокумент" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    TemplateType.TEXT_DOCUMENT;
            case "binary", "binarydata", "двоичныеданные" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    TemplateType.BINARY_DATA;
            case "html", "htmldocument", "htmlдокумент" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    TemplateType.HTML_DOCUMENT;
            case "dcs", "datacompositionschema", "скд" -> //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    TemplateType.DATA_COMPOSITION_SCHEMA;
            case "activedocument", "активныйдокумент" -> //$NON-NLS-1$ //$NON-NLS-2$
                    TemplateType.ACTIVE_DOCUMENT;
            case "geographicalschema", "географическаясхема" -> //$NON-NLS-1$ //$NON-NLS-2$
                    TemplateType.GEOGRAPHICAL_SCHEMA;
            case "graphicalschema", "графическаясхема" -> //$NON-NLS-1$ //$NON-NLS-2$
                    TemplateType.GRAPHICAL_SCHEMA;
            case "addin", "внешняякомпонента" -> //$NON-NLS-1$ //$NON-NLS-2$
                    TemplateType.ADD_IN;
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    PROPERTY + "=" + raw + " is not a template type. Use one of: " + ACCEPTED_VALUES, //$NON-NLS-1$ //$NON-NLS-2$
                    false);
        };
    }

    /** Template to create with its artifact: the single named child or one entry of a batch. */
    public record Requested(String name, TemplateType type, TemplateArtifact artifact) {
    }

    /**
     * Templates an {@code add_metadata_child} request creates, each with the artifact its own type
     * selects, in the order the BM mutation creates them: the named template by the request's
     * {@code template_type}, then every valid {@code properties.children} entry by its own.
     *
     * @throws MetadataOperationException when any requested type is unknown or unsupported
     */
    public static List<Requested> requestedTemplates(AddMetadataChildRequest request) {
        List<Requested> result = new ArrayList<>();
        if (request == null || request.childKind() != MetadataChildKind.TEMPLATE) {
            return result;
        }
        if (request.hasSingleName()) {
            result.add(requested(request.name(), request.properties()));
        }
        Object rawChildren = request.properties() == null ? null : request.properties().get("children"); //$NON-NLS-1$
        if (rawChildren instanceof List<?> children) {
            for (Object entry : children) {
                if (entry instanceof Map<?, ?> child) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> childProperties = (Map<String, Object>) child;
                    Object rawName = childProperties.get("name"); //$NON-NLS-1$
                    String childName = rawName == null ? null : String.valueOf(rawName);
                    if (MetadataNameValidator.isValidName(childName)) {
                        result.add(requested(childName, childProperties));
                    }
                }
            }
        }
        return result;
    }

    private static Requested requested(String name, Map<String, Object> properties) {
        TemplateType type = resolveType(properties);
        return new Requested(name, type, forType(type));
    }

    private static String edtFileName(TemplateType type) {
        return switch (type) {
            case ACTIVE_DOCUMENT -> "Template.axdt"; //$NON-NLS-1$
            case GEOGRAPHICAL_SCHEMA -> "Template.geos"; //$NON-NLS-1$
            case GRAPHICAL_SCHEMA -> "Template.scheme"; //$NON-NLS-1$
            case ADD_IN -> "Template.addin"; //$NON-NLS-1$
            default -> "Template.*"; //$NON-NLS-1$
        };
    }
}
