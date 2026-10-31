/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Locks the standalone module away from Eclipse/core and undeclared architecture drift. */
public class StandaloneBoundaryTest {
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "org.eclipse.", "org.osgi.", "com._1c.", "com.e1c.", "com.codepilot1c.core."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    @Test
    public void productionSourcesDoNotImportPlatformOrCoreApis() throws Exception {
        Path sourceRoot = moduleRoot().resolve("src/main/java"); //$NON-NLS-1$
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) { //$NON-NLS-1$
                int lineNumber = 0;
                for (String line : Files.readAllLines(source)) {
                    lineNumber++;
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) continue; //$NON-NLS-1$
                    String imported = trimmed.substring(7).replace(";", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                    for (String prefix : FORBIDDEN_IMPORTS) {
                        if (imported.startsWith(prefix)) {
                            violations.add(sourceRoot.relativize(source) + ":" + lineNumber + ": " + imported); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }
            }
        }
        assertTrue("Forbidden standalone imports:\n" + String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void compileDependencyAllowlistContainsOnlyRuntimeSlicesAndGson() throws Exception {
        String source = Files.readString(moduleRoot().resolve("pom.xml")); //$NON-NLS-1$
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        NodeList nodes = document.getElementsByTagName("dependency"); //$NON-NLS-1$
        List<String> dependencies = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element dependency = (Element) nodes.item(index);
            String group = child(dependency, "groupId"); //$NON-NLS-1$
            String artifact = child(dependency, "artifactId"); //$NON-NLS-1$
            String scope = child(dependency, "scope"); //$NON-NLS-1$
            dependencies.add(group + ":" + artifact + ":" + (scope == null ? "compile" : scope)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        assertEquals(List.of(
                "com.codepilot1c:codepilot-runtime-kernel:compile", //$NON-NLS-1$
                "com.codepilot1c:codepilot-runtime-provider:compile", //$NON-NLS-1$
                "com.codepilot1c:codepilot-runtime-mcp-client:compile", //$NON-NLS-1$
                "com.google.code.gson:gson:compile", //$NON-NLS-1$
                "junit:junit:test"), dependencies); //$NON-NLS-1$
    }

    private static Path moduleRoot() {
        return Path.of(System.getProperty("runtime.module.basedir")); //$NON-NLS-1$
    }

    private static String child(Element parent, String name) {
        NodeList values = parent.getElementsByTagName(name);
        return values.getLength() == 0 ? null : values.item(0).getTextContent().trim();
    }
}
