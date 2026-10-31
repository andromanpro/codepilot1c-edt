package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;

import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

/**
 * Unit tests of {@link TemplateArtifact}: which content file {@code add_metadata_child} creates for a
 * template and which requests it refuses.
 *
 * <p>The extensions are checked against EDT itself, not against a copy of its table: EDT 2025.2.3
 * resolves a template's content file through {@code QualifiedNameFilePathConverter.TEMPLATE_EXTENSIONS}
 * of the {@code com._1c.g5.v8.dt.ide} plug-in, and a new EDT line that renames an extension turns
 * these tests red instead of leaving the tool writing files nobody reads.</p>
 *
 * <p>Runs outside OSGi against the EDT jars: see {@code scripts/run-template-artifact-eval.sh}.</p>
 */
public class TemplateArtifactTest {

    private static final String EDT_PATH_CONVERTER = "com._1c.g5.v8.dt.ide.QualifiedNameFilePathConverter";

    // ------------------------------------------------------------------ EDT is the source of truth

    @Test
    public void createdFilesUseTheExtensionsEdtReadsTemplateContentFrom() throws Exception {
        Map<String, String> edt = edtTemplateExtensions();

        assertEquals("Template." + edt.get("FileAwareTextDocument"), TemplateArtifact.TEXT.fileName());
        assertEquals("Template." + edt.get("BinaryData"), TemplateArtifact.BINARY.fileName());
        assertEquals("Template." + edt.get("HtmlDocument"), TemplateArtifact.HTML.fileName());
    }

    @Test
    public void refusalNamesTheFileEdtWouldExpect() throws Exception {
        Map<String, String> edt = edtTemplateExtensions();
        Map<TemplateType, String> edtKinds = new LinkedHashMap<>();
        edtKinds.put(TemplateType.ACTIVE_DOCUMENT, "ActiveDocument");
        edtKinds.put(TemplateType.GEOGRAPHICAL_SCHEMA, "GeographicalSchema");
        edtKinds.put(TemplateType.GRAPHICAL_SCHEMA, "GraphicalScheme");
        edtKinds.put(TemplateType.ADD_IN, "AddIn");

        for (Map.Entry<TemplateType, String> kind : edtKinds.entrySet()) {
            try {
                TemplateArtifact.forType(kind.getKey());
                fail(kind.getKey() + " must be refused: codepilot has no verified empty content for it");
            } catch (MetadataOperationException e) {
                assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
                String expectedFile = "Template." + edt.get(kind.getValue());
                assertTrue(e.getMessage(), e.getMessage().contains(expectedFile));
            }
        }
    }

    @Test
    public void spreadsheetKeepsMxlAlthoughEdtImportsSpreadsheetsFromMxlx() throws Exception {
        // Known divergence, deliberately out of #121 and tracked in androman/codepilot1c#122:
        // render_template and inspect_template work with Template.mxl. When that issue is decided,
        // this test changes together with the spreadsheet artifact, not on its own.
        assertEquals("mxlx", edtTemplateExtensions().get("SpreadsheetDocument"));
        assertEquals("Template.mxl", TemplateArtifact.SPREADSHEET.fileName());
        assertNull(TemplateArtifact.SPREADSHEET.initialContent());
    }

    // ------------------------------------------------------------------ artifact per type

    @Test
    public void supportedTypesMapToTheirArtifacts() {
        assertEquals(TemplateArtifact.SPREADSHEET, TemplateArtifact.forType(TemplateType.SPREADSHEET_DOCUMENT));
        assertEquals(TemplateArtifact.TEXT, TemplateArtifact.forType(TemplateType.TEXT_DOCUMENT));
        assertEquals(TemplateArtifact.BINARY, TemplateArtifact.forType(TemplateType.BINARY_DATA));
        assertEquals(TemplateArtifact.HTML, TemplateArtifact.forType(TemplateType.HTML_DOCUMENT));
        assertEquals(TemplateArtifact.NONE, TemplateArtifact.forType(TemplateType.DATA_COMPOSITION_SCHEMA));
        assertEquals(TemplateArtifact.NONE,
                TemplateArtifact.forType(TemplateType.DATA_COMPOSITION_APPEARANCE_TEMPLATE));
    }

    @Test
    public void onlySpreadsheetIsNamedTemplateMxl() {
        for (TemplateArtifact artifact : TemplateArtifact.values()) {
            if (artifact != TemplateArtifact.SPREADSHEET && artifact.hasFile()) {
                assertFalse(artifact.name(), "Template.mxl".equals(artifact.fileName()));
            }
        }
    }

    @Test
    public void dcsTemplateGetsNoFileBecauseDcsManageOwnsIt() {
        assertFalse(TemplateArtifact.NONE.hasFile());
        assertNull(TemplateArtifact.NONE.relativePath("Reports", "Отчёт", "ОсновнаяСхема"));
        assertNull(TemplateArtifact.NONE.initialContent());
    }

    @Test
    public void textAndBinaryStartEmpty() {
        assertEquals(0, TemplateArtifact.TEXT.initialContent().length);
        assertEquals(0, TemplateArtifact.BINARY.initialContent().length);
    }

    @Test
    public void htmlStartsAsAnHtmlDocumentWithoutPagesInEdtDialect() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element root = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(TemplateArtifact.HTML.initialContent()))
                .getDocumentElement();

        assertEquals("http://g5.1c.ru/v8/dt/html-document", root.getNamespaceURI());
        assertEquals("HtmlDocument", root.getLocalName());
        assertEquals("htmldoc", root.getPrefix());
        assertEquals(0, root.getElementsByTagName("*").getLength());
    }

    @Test
    public void initialContentIsACopy() {
        byte[] first = TemplateArtifact.HTML.initialContent();
        first[0] = 'X';

        assertEquals('<', TemplateArtifact.HTML.initialContent()[0]);
    }

    @Test
    public void relativePathFollowsTheEdtProjectLayout() {
        assertEquals("src/DataProcessors/КИИ_ДашбордОшибок/Templates/ШаблонСтраницы/Template.txt",
                TemplateArtifact.TEXT.relativePath("DataProcessors", "КИИ_ДашбордОшибок", "ШаблонСтраницы"));
        assertEquals("src/Documents/Заказ/Templates/echarts_js/Template.bin",
                TemplateArtifact.BINARY.relativePath("Documents", "Заказ", "echarts_js"));
    }

    // ------------------------------------------------------------------ template_type parsing

    @Test
    public void toolValuesLegacyAliasesAndEdtNamesAllResolve() {
        Map<String, TemplateType> cases = new LinkedHashMap<>();
        cases.put("spreadsheet", TemplateType.SPREADSHEET_DOCUMENT);
        cases.put("spreadsheet_document", TemplateType.SPREADSHEET_DOCUMENT);
        cases.put("mxl", TemplateType.SPREADSHEET_DOCUMENT);
        cases.put("SpreadsheetDocument", TemplateType.SPREADSHEET_DOCUMENT);
        cases.put("ТабличныйДокумент", TemplateType.SPREADSHEET_DOCUMENT);
        cases.put("text", TemplateType.TEXT_DOCUMENT);
        cases.put("text_document", TemplateType.TEXT_DOCUMENT);
        cases.put("TextDocument", TemplateType.TEXT_DOCUMENT);
        cases.put(" Text Document ", TemplateType.TEXT_DOCUMENT);
        cases.put("ТекстовыйДокумент", TemplateType.TEXT_DOCUMENT);
        cases.put("binary", TemplateType.BINARY_DATA);
        cases.put("binary_data", TemplateType.BINARY_DATA);
        cases.put("BinaryData", TemplateType.BINARY_DATA);
        cases.put("Двоичные данные", TemplateType.BINARY_DATA);
        cases.put("html", TemplateType.HTML_DOCUMENT);
        cases.put("html_document", TemplateType.HTML_DOCUMENT);
        cases.put("HTMLDocument", TemplateType.HTML_DOCUMENT);
        cases.put("HTMLДокумент", TemplateType.HTML_DOCUMENT);
        cases.put("dcs", TemplateType.DATA_COMPOSITION_SCHEMA);
        cases.put("data_composition_schema", TemplateType.DATA_COMPOSITION_SCHEMA);
        cases.put("DataCompositionSchema", TemplateType.DATA_COMPOSITION_SCHEMA);
        cases.put("СКД", TemplateType.DATA_COMPOSITION_SCHEMA);
        cases.put("active_document", TemplateType.ACTIVE_DOCUMENT);
        cases.put("geographical_schema", TemplateType.GEOGRAPHICAL_SCHEMA);
        cases.put("graphical_schema", TemplateType.GRAPHICAL_SCHEMA);
        cases.put("add_in", TemplateType.ADD_IN);
        cases.put("ВнешняяКомпонента", TemplateType.ADD_IN);

        for (Map.Entry<String, TemplateType> entry : cases.entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(),
                    TemplateArtifact.resolveType(Map.of(TemplateArtifact.PROPERTY, entry.getKey())));
        }
    }

    @Test
    public void absentOrBlankTypeMeansSpreadsheet() {
        assertEquals(TemplateType.SPREADSHEET_DOCUMENT, TemplateArtifact.resolveType(null));
        assertEquals(TemplateType.SPREADSHEET_DOCUMENT, TemplateArtifact.resolveType(Map.of()));
        assertEquals(TemplateType.SPREADSHEET_DOCUMENT, TemplateArtifact.resolveType(Map.of("synonym", "x")));
        assertEquals(TemplateType.SPREADSHEET_DOCUMENT,
                TemplateArtifact.resolveType(Map.of(TemplateArtifact.PROPERTY, "  ")));
    }

    @Test
    public void unknownTypeIsRefusedWithTheAcceptedValues() {
        for (String value : List.of("presentation", "txt", "spreadsheets", "табличный")) {
            try {
                TemplateArtifact.resolveType(Map.of(TemplateArtifact.PROPERTY, value));
                fail(value + " must be refused, not silently turned into a spreadsheet");
            } catch (MetadataOperationException e) {
                assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
                assertTrue(e.getMessage(), e.getMessage().contains(value));
                assertTrue(e.getMessage(), e.getMessage().contains("spreadsheet, text, binary, html, dcs"));
            }
        }
    }

    // ------------------------------------------------------------------ requested templates

    @Test
    public void singleTemplateUsesTheRequestType() {
        List<TemplateArtifact.Requested> requested = TemplateArtifact.requestedTemplates(
                request("ШаблонСтраницы", Map.of(TemplateArtifact.PROPERTY, "text")));

        assertEquals(List.of(new TemplateArtifact.Requested(
                "ШаблонСтраницы", TemplateType.TEXT_DOCUMENT, TemplateArtifact.TEXT)), requested);
    }

    @Test
    public void batchChildrenUseTheirOwnTypes() {
        Map<String, Object> properties = new HashMap<>();
        // The BM mutation initializes each batch child from its own map; a top-level template_type
        // does not reach the children, so the artifacts must not follow it either.
        properties.put(TemplateArtifact.PROPERTY, "binary");
        properties.put("children", List.of(
                Map.of("name", "ПакетТекст", TemplateArtifact.PROPERTY, "text"),
                Map.of("name", "ПакетHTML", TemplateArtifact.PROPERTY, "html"),
                Map.of("name", "ПакетТаблица")));

        List<TemplateArtifact.Requested> requested = TemplateArtifact.requestedTemplates(request(null, properties));

        assertEquals(List.of(
                new TemplateArtifact.Requested("ПакетТекст", TemplateType.TEXT_DOCUMENT, TemplateArtifact.TEXT),
                new TemplateArtifact.Requested("ПакетHTML", TemplateType.HTML_DOCUMENT, TemplateArtifact.HTML),
                new TemplateArtifact.Requested("ПакетТаблица", TemplateType.SPREADSHEET_DOCUMENT,
                        TemplateArtifact.SPREADSHEET)),
                requested);
    }

    @Test
    public void namedTemplateAndBatchChildrenAreBothCreatedLikeTheMutationDoes() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(TemplateArtifact.PROPERTY, "text");
        properties.put("children", List.of(Map.of("name", "Второй", TemplateArtifact.PROPERTY, "binary")));

        List<TemplateArtifact.Requested> requested = TemplateArtifact.requestedTemplates(request("Первый", properties));

        assertEquals(List.of("Первый", "Второй"), requested.stream().map(TemplateArtifact.Requested::name).toList());
        assertEquals(TemplateArtifact.TEXT, requested.get(0).artifact());
        assertEquals(TemplateArtifact.BINARY, requested.get(1).artifact());
    }

    @Test
    public void otherChildKindsRequestNoTemplatesAndAreNotParsed() {
        AddMetadataChildRequest attribute = new AddMetadataChildRequest("CpEval", "Catalog.Товары",
                MetadataChildKind.ATTRIBUTE, "Артикул", null, null, Map.of(TemplateArtifact.PROPERTY, "garbage"));

        assertEquals(List.of(), TemplateArtifact.requestedTemplates(attribute));
    }

    // ------------------------------------------------------------------ helpers

    private static AddMetadataChildRequest request(String name, Map<String, Object> properties) {
        return new AddMetadataChildRequest("CpEval", "DataProcessor.КИИ_ДашбордОшибок", MetadataChildKind.TEMPLATE,
                name, null, null, properties);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> edtTemplateExtensions() throws Exception {
        Class<?> converter = Class.forName(EDT_PATH_CONVERTER);
        Field field = converter.getDeclaredField("TEMPLATE_EXTENSIONS");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }
}
