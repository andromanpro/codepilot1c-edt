package com.codepilot1c.core.edt.dcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;

/**
 * Guards the DCS dialect contract.
 *
 * <p>A schema produced by Designer or imported from a configuration is written
 * in the platform dialect: namespace
 * {@code http://v8.1c.ru/8.1/data-composition-system/schema}, collections named
 * in the singular, values carried in child elements. The service used to read
 * only its own design-time dialect — plural names with values in attributes —
 * and therefore reported an empty schema for every real report, which in turn
 * made an upsert add a second data set next to the one it failed to see.</p>
 *
 * <p>The private DOM layer is reached by reflection on purpose: the production
 * code is kept identical to the upstream proposal, so no test-only seam is
 * carved into it.</p>
 */
public class EdtDcsPlatformDialectTest {

    private static final String NS_PLATFORM = "http://v8.1c.ru/8.1/data-composition-system/schema"; //$NON-NLS-1$
    private static final String NS_EDT_DT = "http://g5.1c.ru/v8/dt/data-composition-system/schema"; //$NON-NLS-1$
    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    /** Platform dialect, shaped like a real Template.dcs: one set, one parameter, two variants. */
    private static final String NS_SETTINGS = "http://v8.1c.ru/8.1/data-composition-system/settings"; //$NON-NLS-1$

    private static final String PLATFORM_SCHEMA = ""
            + "<DataCompositionSchema xmlns=\"" + NS_PLATFORM + "\" xmlns:xsi=\"" + XSI
            + "\" xmlns:dcsset=\"" + NS_SETTINGS + "\">"
            + "  <dataSource><name>DataSource1</name><dataSourceType>Local</dataSourceType></dataSource>"
            + "  <dataSet xsi:type=\"DataSetQuery\">"
            + "    <name>DataSet1</name>"
            + "    <field xsi:type=\"DataSetFieldField\"><dataPath>Quantity</dataPath></field>"
            + "    <dataSource>DataSource1</dataSource>"
            + "    <query>SELECT 1</query>"
            + "  </dataSet>"
            + "  <calculatedField><dataPath>Surplus</dataPath><expression>1</expression></calculatedField>"
            + "  <parameter><name>Period</name></parameter>"
            + "  <settingsVariant><dcsset:name>Main</dcsset:name><dcsset:settings/></settingsVariant>"
            + "  <settingsVariant><dcsset:name>ByZones</dcsset:name></settingsVariant>"
            + "</DataCompositionSchema>"; //$NON-NLS-1$

    /** Design-time dialect emitted by earlier builds; still on disk, still has to be readable. */
    private static final String LEGACY_SCHEMA = ""
            + "<DataCompositionSchema xmlns=\"" + NS_EDT_DT + "\" xmlns:schema=\"" + NS_EDT_DT
            + "\" xmlns:xsi=\"" + XSI + "\">"
            + "  <dataSets xsi:type=\"schema:DataCompositionSchemaDataSetQuery\""
            + " name=\"LegacySet\" query=\"SELECT 1\"/>"
            + "  <parameters name=\"LegacyParam\"/>"
            + "  <settingsVariants name=\"LegacyVariant\"/>"
            + "</DataCompositionSchema>"; //$NON-NLS-1$

    @Test
    public void platformSchemaNodesAreRead() throws Exception {
        Element root = parse(PLATFORM_SCHEMA);

        assertEquals("PLATFORM", dialectOf(root).toString()); //$NON-NLS-1$
        assertEquals(List.of("DataSet1"), names(root, "DATA_SET")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Period"), names(root, "PARAMETER")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("Main", "ByZones"), names(root, "SETTINGS_VARIANT")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(1, nodes(root, "CALCULATED_FIELD").size()); //$NON-NLS-1$
    }

    /**
     * Negative control for the read above: the same document, read as the
     * design-time dialect, yields nothing. This is exactly what get_summary
     * reported before the fix, so a green {@link #platformSchemaNodesAreRead}
     * proves dialect selection and not a parser that matches anything.
     */
    @Test
    public void platformSchemaIsInvisibleToTheDesignTimeDialect() throws Exception {
        Element root = parse(PLATFORM_SCHEMA);
        Object legacyDialect = dialectConstant("EDT_DT"); //$NON-NLS-1$

        assertEquals(0, nodesWith(root, "DATA_SET", legacyDialect).size()); //$NON-NLS-1$
        assertEquals(0, nodesWith(root, "PARAMETER", legacyDialect).size()); //$NON-NLS-1$
        assertEquals(0, nodesWith(root, "SETTINGS_VARIANT", legacyDialect).size()); //$NON-NLS-1$
    }

    @Test
    public void legacyDesignTimeSchemaIsStillRead() throws Exception {
        Element root = parse(LEGACY_SCHEMA);

        assertEquals("EDT_DT", dialectOf(root).toString()); //$NON-NLS-1$
        assertEquals(List.of("LegacySet"), names(root, "DATA_SET")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("LegacyParam"), names(root, "PARAMETER")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of("LegacyVariant"), names(root, "SETTINGS_VARIANT")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * An upsert of a data set that already exists must find it. Failing to find
     * it is not a read-only annoyance: the create branch then appends a second
     * data set next to the one already in the schema.
     */
    @Test
    public void existingPlatformDataSetIsFoundSoUpsertDoesNotDuplicateIt() throws Exception {
        Element root = parse(PLATFORM_SCHEMA);

        assertNotNull(findNode(root, "DATA_SET", "name", "DataSet1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNull(findNode(root, "DATA_SET", "name", "AbsentSet")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(findNode(root, "PARAMETER", "name", "Period")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(findNode(root, "CALCULATED_FIELD", "dataPath", "Surplus")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * The platform validates the top-level element sequence, so a new node
     * cannot be appended at the end: a data set placed after a parameter makes
     * the whole file unreadable.
     */
    @Test
    public void newPlatformNodeIsInsertedInSchemaOrder() throws Exception {
        Element root = parse(""
                + "<DataCompositionSchema xmlns=\"" + NS_PLATFORM + "\" xmlns:xsi=\"" + XSI + "\">"
                + "  <dataSource><name>DataSource1</name></dataSource>"
                + "  <parameter><name>Period</name></parameter>"
                + "  <settingsVariant><name>Main</name></settingsVariant>"
                + "</DataCompositionSchema>"); //$NON-NLS-1$

        Element created = createNode(root.getOwnerDocument(), "DATA_SET"); //$NON-NLS-1$
        appendInOrder(root, created);

        assertEquals(List.of("dataSource", "dataSet", "parameter", "settingsVariant"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                topLevelElementNames(root));
        assertEquals(NS_PLATFORM, created.getNamespaceURI());
    }

    @Test
    public void newLegacyNodeKeepsTheDesignTimeShape() throws Exception {
        Element root = parse(LEGACY_SCHEMA);

        Element created = createNode(root.getOwnerDocument(), "DATA_SET"); //$NON-NLS-1$

        assertEquals("dataSets", created.getLocalName()); //$NON-NLS-1$
        assertEquals(NS_EDT_DT, created.getNamespaceURI());
    }

    /**
     * Варианты настроек считались в get_summary, но list_nodes их не отдавал:
     * ExternalDcsSchema хранил счётчик вместо списка, и node_kind:"variant"
     * молча возвращал пусто на схеме, где варианты есть. Тот же класс дефекта,
     * что и слепота к диалекту — «пусто» вместо «не умею», — только уже.
     */
    @Test
    public void settingsVariantsAreListedNotOnlyCounted() throws Exception {
        Element root = parse(PLATFORM_SCHEMA);
        Object schema = externalSchema(root);

        List<DcsNodeItem> variants = nodeItems(schema, "variant"); //$NON-NLS-1$
        assertEquals(List.of("Main", "ByZones"), //$NON-NLS-1$ //$NON-NLS-2$
                variants.stream().map(DcsNodeItem::name).toList());
        // Паритет с проекцией из памяти, которая отдаёт has_settings.
        assertEquals(List.of("has_settings=true", "has_settings=false"), //$NON-NLS-1$ //$NON-NLS-2$
                variants.stream().map(DcsNodeItem::details).toList());

        assertEquals(List.of("dataset", "parameter", "calculated", "variant", "variant"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                nodeItems(schema, "all").stream().map(DcsNodeItem::nodeKind).toList()); //$NON-NLS-1$
    }

    // ─── reflection over the private DOM layer ──────────────────────────────

    private final EdtDcsService service = new EdtDcsService((EdtMetadataGateway) null);

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return document.getDocumentElement();
    }

    private static Class<?> nested(String simpleName) throws Exception {
        return Class.forName(EdtDcsService.class.getName() + "$" + simpleName); //$NON-NLS-1$
    }

    private static Object constant(String nestedName, String name) throws Exception {
        for (Object candidate : nested(nestedName).getEnumConstants()) {
            if (candidate.toString().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(nestedName + " has no constant " + name); //$NON-NLS-1$
    }

    private static Object dialectConstant(String name) throws Exception {
        return constant("DcsDialect", name); //$NON-NLS-1$
    }

    private static Object kindConstant(String name) throws Exception {
        return constant("DcsNodeKind", name); //$NON-NLS-1$
    }

    private static Object dialectOf(Element root) throws Exception {
        Method of = nested("DcsDialect").getDeclaredMethod("of", Element.class); //$NON-NLS-1$ //$NON-NLS-2$
        of.setAccessible(true);
        return of.invoke(null, root);
    }

    private List<?> nodes(Element root, String kind) throws Exception {
        return nodesWith(root, kind, dialectOf(root));
    }

    private List<?> nodesWith(Element root, String kind, Object dialect) throws Exception {
        Method nodesFrom = EdtDcsService.class.getDeclaredMethod(
                "nodesFrom", Element.class, nested("DcsNodeKind"), nested("DcsDialect")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        nodesFrom.setAccessible(true);
        return (List<?>) nodesFrom.invoke(service, root, kindConstant(kind), dialect);
    }

    private List<String> names(Element root, String kind) throws Exception {
        List<String> result = new ArrayList<>();
        for (Object node : nodes(root, kind)) {
            Method name = node.getClass().getDeclaredMethod("name"); //$NON-NLS-1$
            name.setAccessible(true);
            result.add((String) name.invoke(node));
        }
        return result;
    }

    private Element findNode(Element root, String kind, String key, String wanted) throws Exception {
        Method findNode = EdtDcsService.class.getDeclaredMethod("findNode", //$NON-NLS-1$
                Element.class, nested("DcsNodeKind"), nested("DcsDialect"), //$NON-NLS-1$ //$NON-NLS-2$
                String.class, String.class);
        findNode.setAccessible(true);
        return (Element) findNode.invoke(service, root, kindConstant(kind), dialectOf(root), key, wanted);
    }

    private Element createNode(Document document, String kind) throws Exception {
        Method createNode = EdtDcsService.class.getDeclaredMethod("createNode", //$NON-NLS-1$
                Document.class, nested("DcsDialect"), nested("DcsNodeKind")); //$NON-NLS-1$ //$NON-NLS-2$
        createNode.setAccessible(true);
        return (Element) createNode.invoke(service, document,
                dialectOf(document.getDocumentElement()), kindConstant(kind));
    }

    private void appendInOrder(Element root, Element child) throws Exception {
        Method appendInOrder = EdtDcsService.class.getDeclaredMethod("appendInOrder", //$NON-NLS-1$
                Element.class, Element.class, nested("DcsDialect")); //$NON-NLS-1$
        appendInOrder.setAccessible(true);
        appendInOrder.invoke(service, root, child, dialectOf(root));
    }

    /** Собирает проекцию внешнего шаблона так же, как это делает readExternalSchema. */
    private Object externalSchema(Element root) throws Exception {
        Class<?> schemaClass = nested("ExternalDcsSchema"); //$NON-NLS-1$
        Constructor<?> constructor = schemaClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object dialect = dialectOf(root);
        return constructor.newInstance(null, "Template", dialect, //$NON-NLS-1$
                nodesWith(root, "DATA_SET", dialect), //$NON-NLS-1$
                nodesWith(root, "PARAMETER", dialect), //$NON-NLS-1$
                nodesWith(root, "CALCULATED_FIELD", dialect), //$NON-NLS-1$
                nodesWith(root, "SETTINGS_VARIANT", dialect)); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private List<DcsNodeItem> nodeItems(Object schema, String nodeKind) throws Exception {
        Method nodes = schema.getClass().getDeclaredMethod("nodes", String.class); //$NON-NLS-1$
        nodes.setAccessible(true);
        return (List<DcsNodeItem>) nodes.invoke(schema, nodeKind);
    }

    private static List<String> topLevelElementNames(Element root) {
        List<String> result = new ArrayList<>();
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                result.add(element.getLocalName());
            }
        }
        assertTrue("schema must keep its top-level elements", result.size() >= 2); //$NON-NLS-1$
        return result;
    }
}
