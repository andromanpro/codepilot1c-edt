package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;

import com.codepilot1c.core.edt.forms.EventHandlerTargetResolver;

/**
 * Paired eval of the content file {@code add_metadata_child} creates for a template.
 *
 * <p>Drives the real {@code EdtMetadataService.ensureTemplateArtifact}, {@code resolveTemplateType}
 * and {@link AddMetadataChildRequest#validate()} of whichever build is on the classpath, over a
 * workspace double that records what gets created. Nothing here references a class added by the
 * fix, so the same compiled test runs against the installed build and the fixed one: before the fix
 * every template got {@code Template.mxl}, whatever its type (reproduced 2026-09-16 on build
 * 1.3.9.20260915-0710: a TextDocument template next to a 13-byte MOXCEL file).</p>
 *
 * <p>Runs outside OSGi against the EDT jars: see {@code scripts/run-template-artifact-eval.sh}.</p>
 */
public class EdtMetadataServiceTemplateArtifactTest {

    private static final String OWNER_FQN = "DataProcessor.КИИ_ДашбордОшибок";
    private static final String TEMPLATES = "src/DataProcessors/КИИ_ДашбордОшибок/Templates/";

    private final EdtMetadataService service = new EdtMetadataService(
            new EdtMetadataGateway(), new EventHandlerTargetResolver(target -> List.of()));

    // ------------------------------------------------------------------ artifact by type

    @Test
    public void textTemplateGetsEmptyTemplateTxt() throws Exception {
        Workspace workspace = new Workspace();

        String path = ensureArtifact(workspace, "ШаблонСтраницы", TemplateType.TEXT_DOCUMENT);

        assertEquals(TEMPLATES + "ШаблонСтраницы/Template.txt", path);
        assertEquals(Map.of(TEMPLATES + "ШаблонСтраницы/Template.txt", ""), workspace.createdFiles());
    }

    @Test
    public void binaryTemplateGetsEmptyTemplateBin() throws Exception {
        Workspace workspace = new Workspace();

        String path = ensureArtifact(workspace, "echarts_js", TemplateType.BINARY_DATA);

        assertEquals(TEMPLATES + "echarts_js/Template.bin", path);
        assertEquals(Map.of(TEMPLATES + "echarts_js/Template.bin", ""), workspace.createdFiles());
    }

    @Test
    public void htmlTemplateGetsHtmlDocumentDescriptorWithoutPages() throws Exception {
        Workspace workspace = new Workspace();

        String path = ensureArtifact(workspace, "Справка", TemplateType.HTML_DOCUMENT);

        String expected = TEMPLATES + "Справка/Template.htmldoc";
        assertEquals(expected, path);
        assertEquals(List.of(expected), List.copyOf(workspace.createdFiles().keySet()));
        String content = workspace.createdFiles().get(expected);
        assertTrue(content, content.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(content, content.contains(
                "<htmldoc:HtmlDocument xmlns:htmldoc=\"http://g5.1c.ru/v8/dt/html-document\"/>"));
    }

    @Test
    public void spreadsheetTemplateKeepsTemplateMxl() throws Exception {
        Workspace workspace = new Workspace();

        String path = ensureArtifact(workspace, "ПечатнаяФорма", TemplateType.SPREADSHEET_DOCUMENT);

        // Unchanged by the fix: render_template and inspect_template work with Template.mxl.
        assertEquals(TEMPLATES + "ПечатнаяФорма/Template.mxl", path);
        for (String created : workspace.createdFiles().keySet()) {
            assertEquals(TEMPLATES + "ПечатнаяФорма/Template.mxl", created);
        }
    }

    @Test
    public void dcsTemplateGetsNoArtifactHere() throws Exception {
        Workspace workspace = new Workspace();

        String path = ensureArtifact(workspace, "ОсновнаяСхема", TemplateType.DATA_COMPOSITION_SCHEMA);

        assertNull(path);
        assertEquals(Map.of(), workspace.createdFiles());
    }

    @Test
    public void activeDocumentTemplateIsNotGivenAMoxcelFile() throws Exception {
        Workspace workspace = new Workspace();

        String path = ensureArtifact(workspace, "Документ", TemplateType.ACTIVE_DOCUMENT);

        assertNull(path);
        assertEquals(Map.of(), workspace.createdFiles());
    }

    // ------------------------------------------------------------------ template_type parsing

    @Test
    public void edtTemplateTypeNamesResolveToTheirTypesNotToSpreadsheet() throws Exception {
        assertEquals(TemplateType.TEXT_DOCUMENT, resolveType("TextDocument"));
        assertEquals(TemplateType.BINARY_DATA, resolveType("BinaryData"));
        assertEquals(TemplateType.HTML_DOCUMENT, resolveType("HTMLDocument"));
        assertEquals(TemplateType.TEXT_DOCUMENT, resolveType("text"));
        assertEquals(TemplateType.SPREADSHEET_DOCUMENT, resolveType("spreadsheet"));
    }

    // ------------------------------------------------------------------ refusal before BM mutation

    @Test
    public void everyAdvertisedTemplateTypeIsAccepted() {
        for (String type : List.of("spreadsheet", "text", "binary", "html", "dcs")) {
            template("Макет", Map.of("template_type", type)).validate();
        }
        template("Макет", Map.of()).validate();
    }

    @Test
    public void activeDocumentIsRefusedBeforeTheTemplateIsCreated() {
        assertRefused(template("Документ", Map.of("template_type", "active_document")), "Template.axdt");
    }

    @Test
    public void unknownTemplateTypeIsRefusedInsteadOfBecomingSpreadsheet() {
        assertRefused(template("Макет", Map.of("template_type", "presentation")), "presentation");
    }

    @Test
    public void unsupportedTypeOfABatchChildIsRefused() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("children", List.of(
                Map.of("name", "Текст", "template_type", "text"),
                Map.of("name", "Компонента", "template_type", "addin")));

        assertRefused(template(null, properties), "Template.addin");
    }

    // ------------------------------------------------------------------ helpers

    private String ensureArtifact(Workspace workspace, String templateName, TemplateType type) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("ensureTemplateArtifact",
                IProject.class, String.class, String.class, TemplateType.class, String.class);
        method.setAccessible(true);
        return (String) invoke(method, service, workspace.project(), OWNER_FQN, templateName, type, "eval");
    }

    private TemplateType resolveType(String value) throws Exception {
        Method method = EdtMetadataService.class.getDeclaredMethod("resolveTemplateType", Map.class);
        method.setAccessible(true);
        return (TemplateType) invoke(method, service, Map.of("template_type", value));
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static AddMetadataChildRequest template(String name, Map<String, Object> properties) {
        return new AddMetadataChildRequest("CpEval", OWNER_FQN, MetadataChildKind.TEMPLATE, name, null, null,
                properties);
    }

    private static void assertRefused(AddMetadataChildRequest request, String expectedInMessage) {
        try {
            request.validate();
            fail("template_type must be refused before the metadata is created: " + request.properties());
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_PROPERTY_VALUE, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().contains(expectedInMessage));
        }
    }

    /** Workspace double: nothing exists up front, files and folders created through the API are recorded. */
    private static final class Workspace {

        private final Map<String, String> files = new LinkedHashMap<>();
        private final IProject project = proxy(IProject.class, (name, args) -> switch (name) {
            case "getName" -> "CpEval";
            case "getFile" -> file(String.valueOf(args[0]));
            case "getFolder" -> folder(String.valueOf(args[0]));
            default -> null;
        });

        IProject project() {
            return project;
        }

        Map<String, String> createdFiles() {
            return files;
        }

        private IFile file(String path) {
            return proxy(IFile.class, (name, args) -> switch (name) {
                case "exists" -> Boolean.valueOf(files.containsKey(path));
                case "getParent" -> folder(path.substring(0, path.lastIndexOf('/')));
                case "getName" -> path.substring(path.lastIndexOf('/') + 1);
                case "create", "setContents" -> {
                    files.put(path, contentOf(args[0]));
                    yield null;
                }
                default -> null;
            });
        }

        private IFolder folder(String path) {
            return proxy(IFolder.class, (name, args) -> switch (name) {
                case "exists" -> Boolean.TRUE;
                case "getParent" -> path.contains("/") ? folder(path.substring(0, path.lastIndexOf('/'))) : project;
                default -> null;
            });
        }

        private static String contentOf(Object source) {
            try {
                if (source instanceof InputStream input) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (source instanceof byte[] bytes) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
                throw new IllegalStateException("unexpected content source: " + source);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private interface Handler {
        Object handle(String methodName, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (self, method, args) -> {
                    Object[] actual = args == null ? new Object[0] : args;
                    switch (method.getName()) {
                        case "equals":
                            return Boolean.valueOf(self == actual[0]);
                        case "hashCode":
                            return Integer.valueOf(System.identityHashCode(self));
                        case "toString":
                            return type.getSimpleName() + " double";
                        default:
                            Object result = handler.handle(method.getName(), actual);
                            return result != null ? result : defaultValue(method.getReturnType());
                    }
                }));
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        if (returnType == int.class) {
            return Integer.valueOf(0);
        }
        if (returnType == long.class) {
            return Long.valueOf(0L);
        }
        return null;
    }
}
