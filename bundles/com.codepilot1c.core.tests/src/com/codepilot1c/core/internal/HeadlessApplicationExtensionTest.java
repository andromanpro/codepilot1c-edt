package com.codepilot1c.core.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class HeadlessApplicationExtensionTest {

    @Test
    public void declaresCanonicalApplicationUsingOfficialRunShape() throws Exception {
        Path pluginXml = Path.of("..", "com.codepilot1c.core", "plugin.xml").toAbsolutePath().normalize();
        assertTrue("core plugin.xml must be available from the bundle test module", Files.isRegularFile(pluginXml));
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pluginXml.toFile());

        NodeList extensions = document.getElementsByTagName("extension"); //$NON-NLS-1$
        Element headlessExtension = null;
        for (int i = 0; i < extensions.getLength(); i++) {
            Element extension = (Element) extensions.item(i);
            if ("org.eclipse.core.runtime.applications".equals(extension.getAttribute("point")) //$NON-NLS-1$
                    && "headless".equals(extension.getAttribute("id"))) { //$NON-NLS-1$
                headlessExtension = extension;
                break;
            }
        }
        assertNotNull(headlessExtension);

        Element application = (Element) headlessExtension.getElementsByTagName("application").item(0); //$NON-NLS-1$
        assertNotNull(application);
        assertFalse(application.hasAttribute("id")); //$NON-NLS-1$
        assertFalse(application.hasAttribute("class")); //$NON-NLS-1$
        assertEquals("singleton-global", application.getAttribute("cardinality")); //$NON-NLS-1$
        assertEquals("main", application.getAttribute("thread")); //$NON-NLS-1$
        assertEquals("true", application.getAttribute("visible")); //$NON-NLS-1$

        Element run = (Element) application.getElementsByTagName("run").item(0); //$NON-NLS-1$
        assertNotNull(run);
        assertEquals("com.codepilot1c.core.internal.HeadlessApplication", run.getAttribute("class")); //$NON-NLS-1$
    }
}
