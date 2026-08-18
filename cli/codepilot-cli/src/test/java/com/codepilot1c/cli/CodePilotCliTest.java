/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.Test;

import com.codepilot1c.cli.EndpointProbe.ProbeResult;
import com.codepilot1c.cli.discovery.EdtInstallationDiscovery;

public class CodePilotCliTest {
    @Test public void parsesVersionInTextAndJson() {
        Fixture fixture = new Fixture();
        assertEquals(ExitCodes.OK, fixture.execute("version"));
        assertEquals("codepilot 9.8.7\n", fixture.out());

        fixture.reset();
        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "version"));
        assertEquals("{\"command\":\"version\",\"version\":\"9.8.7\"}\n", fixture.out());
    }

    @Test public void returnsStableUsageAndLifecycleExitCodes() {
        Fixture fixture = new Fixture();
        assertEquals(ExitCodes.USAGE, fixture.execute("unknown"));
        assertTrue(fixture.err().startsWith("error[usage]:"));

        fixture.reset();
        assertEquals(ExitCodes.NOT_IMPLEMENTED, fixture.execute("--output", "json", "edt", "start"));
        assertEquals("{\"command\":\"edt start\",\"status\":\"not_implemented\","
                + "\"error\":\"supervisor_unavailable\","
                + "\"message\":\"EDT process supervision is not implemented in this build.\"}\n", fixture.out());
    }

    @Test public void groupHelpIsAvailable() {
        Fixture fixture = new Fixture();
        assertEquals(ExitCodes.OK, fixture.execute("edt", "--help"));
        assertTrue(fixture.out().contains("Commands:"));
        assertTrue(fixture.out().contains("installations"));
    }

    @Test public void doctorEmitsFourIndependentChecks() {
        Fixture fixture = new Fixture();
        fixture.host.properties.put("edt.home", "/opt/edt");
        fixture.host.directory("/opt/edt");
        fixture.host.file("/opt/edt/1cedt");
        fixture.probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");

        assertEquals(ExitCodes.OK, fixture.execute("--output", "json", "doctor"));
        assertTrue(fixture.out().contains("\"status\":\"ok\""));
        assertTrue(fixture.out().contains("\"name\":\"java\""));
        assertTrue(fixture.out().contains("\"name\":\"edt\""));
        assertTrue(fixture.out().contains("\"name\":\"config\""));
        assertTrue(fixture.out().contains("\"name\":\"endpoint\""));
    }

    @Test public void doctorReturnsUnavailableWhenPrerequisitesFail() {
        Fixture fixture = new Fixture();
        fixture.host.java = "11.0.24";
        fixture.probe = endpoint -> new ProbeResult(false, 0, "ConnectException");

        assertEquals(ExitCodes.UNAVAILABLE, fixture.execute("doctor"));
        assertTrue(fixture.out().contains("java FAIL java_too_old"));
        assertTrue(fixture.out().contains("edt FAIL edt_not_found"));
        assertTrue(fixture.out().contains("endpoint FAIL endpoint_unavailable"));
    }

    @Test public void statusDoesNotClaimRunningWhenEndpointIsDown() {
        Fixture fixture = new Fixture();
        fixture.probe = endpoint -> new ProbeResult(false, 503, "HTTP 503");
        assertEquals(ExitCodes.UNAVAILABLE, fixture.execute("edt", "status"));
        assertTrue(fixture.out().startsWith("unavailable:"));
    }

    @Test public void invalidEndpointIsUsageErrorWithoutProbe() {
        Fixture fixture = new Fixture();
        fixture.host.properties.put("codepilot.endpoint", "not a URI");
        fixture.probe = endpoint -> { throw new AssertionError("probe must not run"); };
        assertEquals(ExitCodes.USAGE, fixture.execute("edt", "status"));
        assertTrue(fixture.out().startsWith("error[invalid_endpoint]:"));
    }

    @Test public void endpointCredentialsAreRejectedAndNeverPrinted() {
        Fixture fixture = new Fixture();
        fixture.host.properties.put("codepilot.endpoint", "http://secret-value@localhost:8765");
        assertEquals(ExitCodes.USAGE, fixture.execute("--output", "json", "edt", "status"));
        assertTrue(fixture.out().contains("\"endpoint\":\"<invalid>\""));
        assertTrue(!fixture.out().contains("secret-value"));
    }

    private static final class Fixture {
        final FakeHostSystem host = new FakeHostSystem();
        EndpointProbe probe = endpoint -> new ProbeResult(true, 200, "HTTP 200");
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();

        int execute(String... args) {
            CliServices services = new CliServices(host, new EdtInstallationDiscovery(host), new CliConfiguration(host),
                    probe, new PrintWriter(output, true), new PrintWriter(errors, true), "9.8.7");
            return CodePilotCli.execute(services, args);
        }

        String out() { return output.toString().replace("\r\n", "\n"); }
        String err() { return errors.toString().replace("\r\n", "\n"); }
        void reset() { output = new StringWriter(); errors = new StringWriter(); }
    }
}
