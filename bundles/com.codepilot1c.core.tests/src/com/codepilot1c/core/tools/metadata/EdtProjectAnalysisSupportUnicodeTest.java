package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.Path;
import org.junit.Test;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class EdtProjectAnalysisSupportUnicodeTest {

    private static final String PROJECT = "ДО.Артель"; //$NON-NLS-1$
    private static final String FIRST_PATH = "Catalogs/Тест/Forms/Форма1/Module.bsl"; //$NON-NLS-1$
    private static final String SECOND_PATH = "Catalogs/Тест/Forms/Форма2/Module.bsl"; //$NON-NLS-1$
    private static final String DUPLICATE_METHOD_FQN = "Catalog.Тест.Module.Обработать"; //$NON-NLS-1$
    private static final String MODULE_TEXT = """
            #Область ПрограммныйИнтерфейс

            // Тестовый метод.
            Функция Обработать() Экспорт
                Возврат Помощник();
            КонецФункции

            #КонецОбласти

            #Область СлужебныеПроцедурыИФункции

            Процедура Помощник()
            КонецПроцедуры

            #КонецОбласти
            """;

    @Test
    public void moduleStructureParsesCapitalizedRussianDeclarationsAndReturnsStableShape() {
        EdtProjectAnalysisSupport support = support(Map.of(FIRST_PATH, MODULE_TEXT));

        JsonObject result = support.moduleStructure(PROJECT, FIRST_PATH, true);

        assertEquals("ok", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FIRST_PATH, result.getAsJsonObject("module").get("file_path").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, result.get("total_methods").getAsInt()); //$NON-NLS-1$
        assertEquals(1, result.get("total_exports").getAsInt()); //$NON-NLS-1$
        assertEquals(2, result.getAsJsonArray("sections").size()); //$NON-NLS-1$
        assertEquals(1, result.getAsJsonArray("calls").size()); //$NON-NLS-1$

        JsonObject method = result.getAsJsonArray("methods").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals(Set.of(
                "fqn", "name", "kind", "signature", "startLine", "endLine", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "exported", "documentation", "filePath", "fileUri"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                method.keySet());
        assertEquals("Обработать", method.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(method.get("exported").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Тестовый метод.", method.get("documentation").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void hierarchyUsesModulePathIdentityAndRejectsAmbiguousLegacyFqn() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(FIRST_PATH, MODULE_TEXT);
        files.put(SECOND_PATH, MODULE_TEXT);
        EdtProjectAnalysisSupport support = support(files);

        EdtToolException ambiguous = assertThrows(EdtToolException.class,
                () -> support.methodCallHierarchy(PROJECT, DUPLICATE_METHOD_FQN, "", "both", 1)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(EdtToolErrorCode.INVALID_ARGUMENT, ambiguous.getCode());
        assertTrue(ambiguous.getMessage().contains("modulePath")); //$NON-NLS-1$

        JsonObject result = support.methodCallHierarchy(
                PROJECT,
                DUPLICATE_METHOD_FQN,
                "src/" + FIRST_PATH, //$NON-NLS-1$
                "both", //$NON-NLS-1$
                1);

        JsonObject root = result.getAsJsonObject("root"); //$NON-NLS-1$
        assertEquals(FIRST_PATH, root.get("file_path").getAsString()); //$NON-NLS-1$
        assertEquals("Обработать", root.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray callees = result.getAsJsonArray("callees"); //$NON-NLS-1$
        assertEquals(1, callees.size());
        assertEquals(FIRST_PATH, callees.get(0).getAsJsonObject().get("file_path").getAsString()); //$NON-NLS-1$
        assertEquals("Помощник", callees.get(0).getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, result.getAsJsonArray("callers").size()); //$NON-NLS-1$

        JsonObject second = support.moduleStructure(PROJECT, SECOND_PATH, false);
        assertEquals(SECOND_PATH, second.getAsJsonObject("module").get("file_path").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void toolSchemasDocumentPathQualifiedSelectors() {
        JsonObject structureSchema = JsonParser.parseString(
                new GetModuleStructureTool().getParameterSchema()).getAsJsonObject();
        JsonObject hierarchySchema = JsonParser.parseString(
                new GetMethodCallHierarchyTool().getParameterSchema()).getAsJsonObject();

        assertTrue(structureSchema.getAsJsonObject("properties") //$NON-NLS-1$
                .getAsJsonObject("moduleFqn") //$NON-NLS-1$
                .get("description").getAsString().contains("file_path")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(hierarchySchema.getAsJsonObject("properties").has("modulePath")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, hierarchySchema.getAsJsonArray("required").size()); //$NON-NLS-1$
    }

    private EdtProjectAnalysisSupport support(Map<String, String> sourceFiles) {
        IProject project = project(sourceFiles);
        return new EdtProjectAnalysisSupport(new EdtMetadataGateway() {
            @Override
            public IProject resolveProject(String projectName) {
                return project;
            }
        });
    }

    private IProject project(Map<String, String> sourceFiles) {
        IProject[] projectRef = new IProject[1];
        Map<String, IFile> files = new LinkedHashMap<>();
        IProject project = (IProject) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IProject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> PROJECT; //$NON-NLS-1$
                    case "exists", "isOpen" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$
                    case "getType" -> Integer.valueOf(IResource.PROJECT); //$NON-NLS-1$
                    case "getFullPath" -> new Path("/" + PROJECT); //$NON-NLS-1$ //$NON-NLS-2$
                    case "getFile" -> files.get(normalizeRequestedPath(String.valueOf(args[0]))); //$NON-NLS-1$
                    case "accept" -> { //$NON-NLS-1$
                        if (args[0] instanceof IResourceVisitor visitor) {
                            for (IFile file : files.values()) {
                                visitor.visit(file);
                            }
                        }
                        yield null;
                    }
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> defaultValue(method.getReturnType());
                });
        projectRef[0] = project;
        sourceFiles.forEach((path, text) -> files.put(path, file(projectRef[0], path, text)));
        return project;
    }

    private IFile file(IProject project, String relativePath, String text) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        return (IFile) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {IFile.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name; //$NON-NLS-1$
                    case "getFileExtension" -> "bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                    case "getProject" -> project; //$NON-NLS-1$
                    case "getType" -> Integer.valueOf(IResource.FILE); //$NON-NLS-1$
                    case "exists" -> Boolean.TRUE; //$NON-NLS-1$
                    case "getFullPath" -> new Path("/" + PROJECT + "/src/" + relativePath); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    case "getLocation" -> new Path("/tmp/" + PROJECT + "/src/" + relativePath); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    case "getContents" -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); //$NON-NLS-1$
                    case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy)); //$NON-NLS-1$
                    case "equals" -> Boolean.valueOf(proxy == args[0]); //$NON-NLS-1$
                    default -> defaultValue(method.getReturnType());
                });
    }

    private String normalizeRequestedPath(String path) {
        return path.startsWith("src/") ? path.substring(4) : path; //$NON-NLS-1$
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        return null;
    }
}
