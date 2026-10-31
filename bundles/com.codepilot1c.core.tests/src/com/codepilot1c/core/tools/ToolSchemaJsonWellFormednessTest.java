package com.codepilot1c.core.tools;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Test;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Every published tool schema must be strictly valid JSON.
 *
 * <p>A live {@code tools/list} against a candidate build failed with
 * {@code MalformedJsonException: Unterminated object} at
 * {@code $.properties.template.description} for {@code add_metadata_child}: a Java text block wrote
 * {@code \"} for its examples, which is an <em>escape</em> producing a bare {@code "} in the emitted
 * JSON rather than the escaped {@code \"} the JSON grammar needs. The string therefore terminated
 * early and the whole schema became unparseable, taking the entire tool list down with it.</p>
 *
 * <p>Per-tool schema tests all used substring checks, so none of them noticed. This parses every
 * schema the bundle publishes, with Gson in strict (non-lenient) mode - the same failure mode the
 * MCP client hit - so any tool that breaks its JSON fails here first.</p>
 */
public class ToolSchemaJsonWellFormednessTest {

    /**
     * The bundle jar is handed to Surefire by the module POM; enumerating it is how this test stays
     * general instead of listing tools by hand and silently missing new ones.
     */
    private static final String CORE_BUNDLE_PATH = "core.bundle.path"; //$NON-NLS-1$

    @Test
    public void everyPublishedToolSchemaParsesAsStrictJson() throws Exception {
        List<Class<? extends ITool>> toolClasses = discoverToolClasses();
        assertTrue("no tool classes were discovered; the guard would pass vacuously", //$NON-NLS-1$
                toolClasses.size() >= 50);

        TreeMap<String, String> broken = new TreeMap<>();
        TreeMap<String, String> uninstantiable = new TreeMap<>();
        int parsed = 0;

        for (Class<? extends ITool> toolClass : toolClasses) {
            ITool tool;
            try {
                Constructor<? extends ITool> constructor = toolClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                tool = constructor.newInstance();
            } catch (ReflectiveOperationException | LinkageError | RuntimeException cannotConstruct) {
                // Some tools need OSGi services to construct; their schemas are covered by the
                // dedicated per-tool tests. Recorded so this never silently shrinks to nothing.
                uninstantiable.put(toolClass.getName(), String.valueOf(cannotConstruct));
                continue;
            }

            String schema = tool.getParameterSchema();
            if (schema == null || schema.isBlank()) {
                continue;
            }
            try {
                parseStrict(schema);
                parsed++;
            } catch (Exception malformed) {
                broken.put(toolClass.getSimpleName(), String.valueOf(malformed));
            }
        }

        assertTrue("too few schemas were actually parsed (" + parsed + "); skipped: " + uninstantiable, //$NON-NLS-1$ //$NON-NLS-2$
                parsed >= 50);
        if (!broken.isEmpty()) {
            StringBuilder report = new StringBuilder("Tool schemas that are not valid JSON:"); //$NON-NLS-1$
            broken.forEach((name, error) -> report.append(System.lineSeparator())
                    .append("  ").append(name).append(": ").append(error)); //$NON-NLS-1$ //$NON-NLS-2$
            fail(report.toString());
        }
    }

    @Test
    public void theGuardRejectsTheExactTextBlockEscapingMistake() {
        // What an unescaped quote inside a description actually produces.
        String schema = "{\"properties\":{\"template\":{\"description\":\"path \"/state\" here\"}}}"; //$NON-NLS-1$

        try {
            parseStrict(schema);
            fail("a bare quote inside a JSON string must not parse"); //$NON-NLS-1$
        } catch (Exception expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void theGuardAcceptsAProperlyEscapedQuote() throws Exception {
        parseStrict("{\"properties\":{\"template\":{\"description\":\"path \\\"/state\\\" here\"}}}"); //$NON-NLS-1$
    }

    /**
     * Parses one complete JSON document in strict mode.
     *
     * <p>Gson's {@code JsonParser}/{@code Gson.fromJson} force leniency on the reader, which hides
     * exactly this class of defect, so the document is walked through a non-lenient
     * {@link JsonReader} directly and the stream must end cleanly afterwards.</p>
     */
    private static void parseStrict(String json) throws Exception {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            reader.skipValue();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalStateException("trailing content after the JSON document"); //$NON-NLS-1$
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends ITool>> discoverToolClasses() throws Exception {
        String bundlePath = System.getProperty(CORE_BUNDLE_PATH);
        assertNotNull("core.bundle.path must be provided by the build", bundlePath); //$NON-NLS-1$
        File bundle = new File(bundlePath);
        assertTrue("core bundle jar is missing: " + bundlePath, bundle.isFile()); //$NON-NLS-1$

        List<Class<? extends ITool>> found = new ArrayList<>();
        try (JarFile jar = new JarFile(bundle)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class") || name.contains("$")) { //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length()).replace('/', '.'); //$NON-NLS-1$
                if (!className.startsWith("com.codepilot1c.core.tools.")) { //$NON-NLS-1$
                    continue;
                }
                Class<?> candidate;
                try {
                    candidate = Class.forName(className, false,
                            ToolSchemaJsonWellFormednessTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError absent) {
                    continue;
                }
                if (ITool.class.isAssignableFrom(candidate)
                        && !candidate.isInterface()
                        && !Modifier.isAbstract(candidate.getModifiers())) {
                    found.add((Class<? extends ITool>) candidate);
                }
            }
        }
        return found;
    }
}
