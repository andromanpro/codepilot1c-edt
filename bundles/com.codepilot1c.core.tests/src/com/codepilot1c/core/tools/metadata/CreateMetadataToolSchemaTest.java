package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

import com.codepilot1c.core.tools.surface.ToolSchemaJsonChecker;
import com.google.gson.JsonObject;

/**
 * The {@code create_metadata} schema must parse, or MCP clients lose every argument of the tool.
 *
 * <p>Build 1.3.9.20260924-1900 wrote the schedule example of {@code properties} as
 * {@code {\"repeatPeriodInDay\":300,...}} inside the text block. Java turned {@code \"} into a bare quote that ended
 * the JSON string, and the host published {@code "Schema parse error: ... Unterminated object at line 76 column 193
 * path $.properties.properties.description"} instead of the schema. A client that no longer saw the top-level
 * {@code comment} sent it inside {@code properties} and was refused with {@code INVALID_METADATA_CHANGE}.
 */
public class CreateMetadataToolSchemaTest {

    @Test
    public void schemaParsesAndPublishesTopLevelArguments() throws IOException {
        JsonObject properties = schema().getAsJsonObject("properties"); //$NON-NLS-1$
        for (String argument : new String[] { "project", "kind", "name", "synonym", "comment", "properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "validation_token" }) { //$NON-NLS-1$
            assertTrue("create_metadata schema does not publish " + argument, properties.has(argument)); //$NON-NLS-1$
        }
    }

    @Test
    public void propertiesDescriptionKeepsScheduleExampleAsJson() throws IOException {
        String description = schema().getAsJsonObject("properties").getAsJsonObject("properties") //$NON-NLS-1$ //$NON-NLS-2$
                .get("description").getAsString(); //$NON-NLS-1$
        assertTrue(description, description.contains("{\"repeatPeriodInDay\":300,\"daysRepeatPeriod\":1}")); //$NON-NLS-1$
    }

    private static JsonObject schema() throws IOException {
        return ToolSchemaJsonChecker.parse(new CreateMetadataTool().getParameterSchema()).getAsJsonObject();
    }
}
