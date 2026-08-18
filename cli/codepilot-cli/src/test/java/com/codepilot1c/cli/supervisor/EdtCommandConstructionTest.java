/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class EdtCommandConstructionTest {
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test public void macArgumentsPreserveSpacesWithoutShellQuoting() {
        assertCommand("/Applications/1C EDT.app/Contents/Eclipse/1cedtcli", Path.of("/Volumes/Project/EDT Work"),
                Path.of("/Volumes/Project/Application Support/codepilot/instances"));
    }

    @Test public void linuxArgumentsPreserveSpacesWithoutShellQuoting() {
        assertCommand("/opt/1C EDT/1cedtcli", Path.of("/srv/EDT Work"),
                Path.of("/var/lib/codepilot-test/instances"));
    }

    @Test public void windowsArgumentsRemainIndividualProcessBuilderValues() {
        assertCommand("C:\\Program Files\\1C EDT\\1cedtcli.exe", Path.of("C:\\EDT Work"),
                Path.of("C:\\CodePilot Data\\instances"));
    }

    private static void assertCommand(String launcher, Path workspace, Path registry) {
        List<String> command = EdtSupervisor.buildCommand(launcher, workspace, 9123, ID, registry);
        assertEquals(launcher, command.get(0));
        assertEquals("-application", command.get(2));
        assertEquals("com.codepilot1c.core.headless", command.get(3));
        assertEquals("-data", command.get(4));
        assertEquals(workspace.toString(), command.get(5));
        assertTrue(command.contains("-Dcodepilot.mcp.host.http.port=9123"));
        assertTrue(command.contains("-Dcodepilot.instance.id=" + ID));
        assertTrue(command.contains("-Dcodepilot.instance.owner=cli"));
        assertTrue(command.contains("-Dcodepilot.instance.registryDir=" + registry));
    }
}
