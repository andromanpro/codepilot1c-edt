/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Locks the module to its explicit Java-only import and dependency allowlists. */
public class StandaloneBoundaryTest {
    @Test
    public void productionImportsAreOnlyJavaOrThisModule() throws Exception {
        Path root = moduleRoot().resolve("src/main/java"); //$NON-NLS-1$
        List<String> violations = new ArrayList<>();
        try (var sources = Files.walk(root)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) { //$NON-NLS-1$
                int line = 0;
                for (String text : Files.readAllLines(source)) {
                    line++;
                    String trimmed = text.trim();
                    if (!trimmed.startsWith("import ")) continue; //$NON-NLS-1$
                    String imported = trimmed.substring(7).replace(";", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                    if (!imported.startsWith("java.") && !imported.startsWith("com.codepilot1c.runtime.config.")) { //$NON-NLS-1$ //$NON-NLS-2$
                        violations.add(source + ":" + line + " " + imported); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
        }
        assertTrue("Unexpected production imports:\n" + String.join("\n", violations), violations.isEmpty()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void dependencyAllowlistIsJunitTestOnly() throws Exception {
        String source = Files.readString(moduleRoot().resolve("pom.xml")); //$NON-NLS-1$
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        NodeList nodes = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getElementsByTagName("dependency"); //$NON-NLS-1$
        List<String> dependencies = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element dependency = (Element) nodes.item(index);
            dependencies.add(child(dependency, "groupId") + ":" + child(dependency, "artifactId") + ":" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + child(dependency, "scope")); //$NON-NLS-1$
        }
        assertEquals(List.of("junit:junit:test"), dependencies); //$NON-NLS-1$
    }

    private static Path moduleRoot() {
        return Path.of(System.getProperty("runtime.module.basedir")); //$NON-NLS-1$
    }

    private static String child(Element parent, String name) {
        NodeList values = parent.getElementsByTagName(name);
        return values.getLength() == 0 ? "compile" : values.item(0).getTextContent().trim(); //$NON-NLS-1$
    }
}
