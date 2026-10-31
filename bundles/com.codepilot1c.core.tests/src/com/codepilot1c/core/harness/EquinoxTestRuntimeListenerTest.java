package com.codepilot1c.core.harness;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.ConfigurationScope;
import org.junit.Test;

public class EquinoxTestRuntimeListenerTest {
    @Test public void suppliesRealConfigurationLocation() {
        assertNotNull(EquinoxTestRuntimeListener.root());
        assertNotNull(Platform.getConfigurationLocation());
        assertNotNull(Platform.getConfigurationLocation().getURL());
        assertTrue(Platform.getConfigurationLocation().getURL().getPath()
                .contains(EquinoxTestRuntimeListener.root().getFileName().toString()));
        assertNotNull(ConfigurationScope.INSTANCE.getLocation());
    }
}
