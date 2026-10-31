/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.supervisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.Test;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.ExitCodes;
import com.codepilot1c.cli.discovery.EdtInstallation;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;
import com.codepilot1c.cli.discovery.LauncherKind;
import com.codepilot1c.cli.platform.HostSystem;

/**
 * Launcher selection contract behind the WP-N0 {@code edt start} blocker.
 *
 * <p>{@code 1cedtcli} is 1C's own command-line front end, not the Eclipse RCP launcher. Its whole
 * option set is {@code -data -timeout -command -file -nl -v -ini-file -vmargs}: it has no
 * {@code -application} and no {@code -nosplash}, and rejects the latter outright. Because
 * {@code detectHome} probed {@code 1cedtcli} first and only ever looked inside {@code Contents/Eclipse},
 * every macOS installation resolved to that wrapper while the real RCP launcher in
 * {@code Contents/MacOS/1cedt} stayed invisible, so the headless application could never start.</p>
 */
public class EdtLauncherKindTest {
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test public void macBundlePrefersTheEclipseRcpLauncherOverTheOneCedtCliWrapper() {
        LauncherHost host = new LauncherHost("Mac OS X");
        String root = "/Applications/1C/1CE/components";
        String app = root + "/1c-edt-2025.2.app";
        String eclipse = app + "/Contents/Eclipse";
        host.directory(root, app);
        host.directory(eclipse);
        host.file(eclipse + "/1cedtcli");
        host.file(app + "/Contents/MacOS/1cedt");

        EdtInstallation installation = new EdtInstallationDiscovery(host).preferred().orElseThrow();
        assertEquals(eclipse, installation.home());
        assertEquals(app + "/Contents/MacOS/1cedt", installation.launcher());
        assertEquals(LauncherKind.ECLIPSE, installation.kind());
    }

    @Test public void anInstallationExposingOnlyTheWrapperIsReportedAsSuch() {
        LauncherHost host = new LauncherHost("Linux");
        String edt = "/opt/edt/eclipse";
        host.properties.put("edt.home", edt);
        host.directory(edt);
        host.file(edt + "/1cedtcli");

        EdtInstallation installation = new EdtInstallationDiscovery(host).preferred().orElseThrow();
        assertEquals(edt + "/1cedtcli", installation.launcher());
        assertEquals(LauncherKind.CLI_WRAPPER, installation.kind());
    }

    @Test public void linuxInstallationPrefersTheEclipseLauncherBesideTheWrapper() {
        LauncherHost host = new LauncherHost("Linux");
        String edt = "/opt/edt/eclipse";
        host.properties.put("edt.home", edt);
        host.directory(edt);
        host.file(edt + "/1cedtcli");
        host.file(edt + "/1cedt");

        EdtInstallation installation = new EdtInstallationDiscovery(host).preferred().orElseThrow();
        assertEquals(edt + "/1cedt", installation.launcher());
        assertEquals(LauncherKind.ECLIPSE, installation.kind());
    }

    @Test public void eclipseLauncherCommandKeepsTheHeadlessApplicationContract() {
        EdtInstallation installation = new EdtInstallation("/opt/edt/eclipse", "/opt/edt/eclipse/1cedt",
                "standard", LauncherKind.ECLIPSE);

        List<String> command = EdtSupervisor.buildCommand(installation, Path.of("/EDT Work"), 9123, ID,
                Path.of("/CodePilot Data/instances"));

        assertEquals("/opt/edt/eclipse/1cedt", command.get(0));
        assertEquals("-nosplash", command.get(1));
        assertEquals("-application", command.get(2));
        assertEquals("com.codepilot1c.core.headless", command.get(3));
        assertEquals("-data", command.get(4));
        assertEquals(Path.of("/EDT Work").toString(), command.get(5));
        assertTrue(command.contains("-Dcodepilot.mcp.host.http.port=9123"));
        assertTrue(command.contains("-Dcodepilot.instance.id=" + ID));
        assertTrue(command.contains("-Dcodepilot.instance.owner=cli"));
        assertTrue(command.contains("-Dcodepilot.instance.registryDir="
                + Path.of("/CodePilot Data/instances")));
        assertTrue(command.indexOf("-vmargs") < command.indexOf("-Dcodepilot.instance.id=" + ID));
    }

    @Test public void wrapperLauncherIsNeverHandedEclipseLauncherArguments() {
        EdtInstallation wrapper = new EdtInstallation("/opt/edt/eclipse", "/opt/edt/eclipse/1cedtcli",
                "standard", LauncherKind.CLI_WRAPPER);

        assertThrows(IllegalArgumentException.class,
                () -> EdtSupervisor.buildCommand(wrapper, Path.of("/ws"), 9123, ID, Path.of("/instances")));
    }

    @Test public void startFailsClosedWhenOnlyTheWrapperLauncherIsInstalled() {
        LauncherHost host = new LauncherHost("Linux");
        String edt = "/opt/edt/eclipse";
        host.directory(edt);
        host.file(edt + "/1cedtcli");
        host.directory("/workspace");

        Fixture fixture = new Fixture(host);
        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new EdtSupervisor.StartRequest("/workspace", edt, 9123,
                        Duration.ofSeconds(1))));

        assertEquals("edt_launcher_unsupported", failure.error());
        assertEquals(ExitCodes.EDT_UNAVAILABLE, failure.exitCode());
        assertEquals(LauncherKind.CLI_WRAPPER.token(), failure.details().get("launcherKind"));
        assertEquals(edt + "/1cedtcli", failure.details().get("launcher"));
        assertFalse("nothing may be launched when the launcher cannot host the application",
                fixture.launched);
    }

    @Test public void explicitVirtualMachineIsPassedBeforeVmargsSoEquinoxHonoursIt() {
        EdtInstallation installation = new EdtInstallation("/opt/edt/eclipse", "/opt/edt/eclipse/1cedt",
                "standard", LauncherKind.ECLIPSE);

        List<String> command = EdtSupervisor.buildCommand(installation, Path.of("/ws"), 9123, ID,
                Path.of("/instances"), "/jvm/lib/server/libjvm.dylib");

        int vm = command.indexOf("-vm");
        assertTrue("-vm must be present", vm > 0);
        assertEquals("/jvm/lib/server/libjvm.dylib", command.get(vm + 1));
        assertTrue("Equinox only honours -vm when it precedes -vmargs", vm < command.indexOf("-vmargs"));
        assertEquals("-data", command.get(4));
    }

    @Test public void omittedVirtualMachineLeavesTheLaunchUnchanged() {
        EdtInstallation installation = new EdtInstallation("/opt/edt/eclipse", "/opt/edt/eclipse/1cedt",
                "standard", LauncherKind.ECLIPSE);

        assertEquals(EdtSupervisor.buildCommand(installation, Path.of("/ws"), 9123, ID, Path.of("/instances")),
                EdtSupervisor.buildCommand(installation, Path.of("/ws"), 9123, ID, Path.of("/instances"), null));
    }

    @Test public void aMissingVirtualMachineFailsAsUsageBeforeAnythingIsLaunched() {
        LauncherHost host = new LauncherHost("Linux");
        String edt = "/opt/edt/eclipse";
        host.directory(edt);
        host.file(edt + "/1cedt");
        host.directory("/workspace");

        Fixture fixture = new Fixture(host);
        SupervisorException failure = assertThrows(SupervisorException.class,
                () -> fixture.supervisor().start(new EdtSupervisor.StartRequest("/workspace", edt, 9123,
                        Duration.ofSeconds(1), "/absent/libjvm.dylib")));

        assertEquals("invalid_vm", failure.error());
        assertEquals(ExitCodes.USAGE, failure.exitCode());
        assertFalse(fixture.launched);
    }

    private static final class Fixture {
        private final LauncherHost host;
        private final InstanceRegistryTest.MemoryFiles files = new InstanceRegistryTest.MemoryFiles();
        boolean launched;

        Fixture(LauncherHost host) { this.host = host; }

        EdtSupervisor supervisor() {
            ProcessLauncher launcher = (command, stdout, stderr) -> {
                launched = true;
                throw new IllegalStateException("must not launch");
            };
            return new EdtSupervisor(new EdtInstallationDiscovery(host), files,
                    new InstanceRegistry(files, Path.of("/registry")), launcher,
                    pid -> Optional.empty(), ignored -> true,
                    uri -> new ProbeResult(false, 0, "ConnectException"), uri -> { },
                    Clock.fixed(Instant.parse("2026-08-25T07:00:00Z"), ZoneOffset.UTC), duration -> { },
                    () -> UUID.fromString(ID), Path.of("/logs"));
        }
    }

    private static final class LauncherHost implements HostSystem {
        private final String os;
        final Map<String, String> properties = new HashMap<>();
        private final List<String> directories = new ArrayList<>();
        private final List<String> files = new ArrayList<>();
        private final Map<String, List<String>> children = new HashMap<>();

        LauncherHost(String os) { this.os = os; }

        void directory(String path, String... childPaths) {
            directories.add(path);
            children.put(path, List.of(childPaths));
            directories.addAll(List.of(childPaths));
        }

        void file(String path) { files.add(path); }

        @Override public String osName() { return os; }
        @Override public String javaVersion() { return "17"; }
        @Override public String userHome() { return "/synthetic-home"; }
        @Override public String environment(String name) { return null; }
        @Override public String systemProperty(String name) { return properties.get(name); }
        @Override public boolean isDirectory(String path) { return directories.contains(path); }
        @Override public boolean isRegularFile(String path) { return files.contains(path); }
        @Override public boolean isReadable(String path) { return files.contains(path); }
        @Override public List<String> children(String directory) {
            return new ArrayList<>(children.getOrDefault(directory, List.of()));
        }
    }
}
