package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.permissions.PermissionRule;

public class ToolSurfaceContextTest {

    private static final AgentProfile STUB_PROFILE = new AgentProfile() {
        @Override public String getId() { return "test"; } //$NON-NLS-1$
        @Override public String getName() { return "Test"; } //$NON-NLS-1$
        @Override public String getDescription() { return ""; } //$NON-NLS-1$
        @Override public Set<String> getAllowedTools() { return Collections.emptySet(); }
        @Override public List<PermissionRule> getDefaultPermissions() { return Collections.emptyList(); }
        @Override public String getSystemPromptAddition() { return null; }
        @Override public int getMaxSteps() { return 10; }
        @Override public long getTimeoutMs() { return 60_000L; }
        @Override public boolean isReadOnly() { return true; }
        @Override public boolean canExecuteShell() { return false; }
    };

    @Test
    public void contextContainsOnlyProviderNeutralInstanceState() {
        Set<String> instanceFields = Arrays.stream(ToolSurfaceContext.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("profile", "category", "builtIn"), instanceFields); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void toBuilderPreservesProfileCategoryAndProvenance() {
        ToolSurfaceContext copy = ToolSurfaceContext.builder()
                .profile(STUB_PROFILE)
                .category(ToolCategory.FILES_READ_SEARCH)
                .builtIn(true)
                .build()
                .toBuilder()
                .build();

        assertSame(STUB_PROFILE, copy.getProfile());
        assertEquals(ToolCategory.FILES_READ_SEARCH, copy.getCategory());
        assertTrue(copy.isBuiltIn());
    }
}
