/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Documents and locks the compile-time dependency inventory of this slice. */
public class ProviderDependencyInventoryTest {

    @Test
    public void dependencyAllowlistContainsOnlyKernelAtCompileScope() throws Exception {
        Path pom = Path.of(System.getProperty("runtime.module.basedir"), "pom.xml"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> dependencies = dependencies(Files.readString(pom));

        assertEquals(List.of(
                "com.codepilot1c:codepilot-runtime-kernel:compile", //$NON-NLS-1$
                "junit:junit:test"), dependencies); //$NON-NLS-1$
    }

    private static List<String> dependencies(String pomSource) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(pomSource.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        NodeList dependencyNodes = document.getElementsByTagName("dependency"); //$NON-NLS-1$
        List<String> dependencies = new ArrayList<>();
        for (int index = 0; index < dependencyNodes.getLength(); index++) {
            Element dependency = (Element) dependencyNodes.item(index);
            String groupId = childText(dependency, "groupId"); //$NON-NLS-1$
            String artifactId = childText(dependency, "artifactId"); //$NON-NLS-1$
            String scope = childText(dependency, "scope"); //$NON-NLS-1$
            dependencies.add(groupId + ":" + artifactId + ":" //$NON-NLS-1$ //$NON-NLS-2$
                    + (scope == null ? "compile" : scope)); //$NON-NLS-1$
        }
        return dependencies;
    }

    private static String childText(Element parent, String name) {
        NodeList elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? null : elements.item(0).getTextContent().trim();
    }
}
