/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.cli.discovery.EdtInstallation;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;

public class EdtInstallationDiscoveryTest {
    @Test public void discoversMacApplicationBundle() {
        FakeHostSystem host = new FakeHostSystem();
        host.os = "Mac OS X";
        String root = "/Applications/1C/1CE/components";
        String app = root + "/1c-edt-2025.2.app";
        String eclipse = app + "/Contents/Eclipse";
        host.directory(root, app);
        host.directory(eclipse);
        host.file(eclipse + "/1cedt");

        List<EdtInstallation> result = new EdtInstallationDiscovery(host).discover();
        assertEquals(1, result.size());
        assertEquals(eclipse, result.get(0).home());
    }

    @Test public void discoversLinuxExplicitHomeAndDeduplicatesPath() {
        FakeHostSystem host = new FakeHostSystem();
        String edt = "/opt/edt/eclipse";
        host.properties.put("edt.home", edt);
        host.environment.put("PATH", edt + ":/usr/bin");
        host.directory(edt);
        host.file(edt + "/1cedt");

        List<EdtInstallation> result = new EdtInstallationDiscovery(host).discover();
        assertEquals(1, result.size());
        assertEquals("system-property", result.get(0).source());
    }

    @Test public void discoversWindowsComponentInstallation() {
        FakeHostSystem host = new FakeHostSystem();
        host.os = "Windows 11";
        host.environment.put("ProgramFiles", "C:\\Program Files");
        String root = "C:\\Program Files\\1C\\1CE\\components";
        String edt = root + "\\1c-edt-2025.2\\eclipse";
        String product = root + "\\1c-edt-2025.2";
        host.directory(root, product);
        host.directory(product, edt);
        host.directory(edt);
        host.file(edt + "\\1cedt.exe");

        List<EdtInstallation> result = new EdtInstallationDiscovery(host).discover();
        assertEquals(1, result.size());
        assertEquals(edt, result.get(0).home());
    }

    @Test public void preferredHonorsSystemPropertyBeforeSortedStandardInstallations() {
        FakeHostSystem host = new FakeHostSystem();
        String configured = "/opt/edt-2025.2";
        String standardRoot = "/opt/1C/1CE/components";
        String older = standardRoot + "/edt-2025.1";
        host.properties.put("edt.home", configured);
        host.directory(configured);
        host.file(configured + "/1cedtcli");
        host.directory(standardRoot, older);
        host.directory(older);
        host.file(older + "/1cedtcli");

        EdtInstallation preferred = new EdtInstallationDiscovery(host).preferred().orElseThrow();
        assertEquals(configured, preferred.home());
        assertEquals("system-property", preferred.source());
    }
}
