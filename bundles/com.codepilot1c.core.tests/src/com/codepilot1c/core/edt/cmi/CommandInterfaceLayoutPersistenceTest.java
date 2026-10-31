package com.codepilot1c.core.edt.cmi;

import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.codepilot1c.core.edt.cmi.CommandInterfaceFragments.GroupEntry;
import com.codepilot1c.core.edt.cmi.CommandInterfaceFragments.VisibilityEntry;
import com.codepilot1c.core.edt.cmi.EdtCommandInterfaceService.Snapshot;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class CommandInterfaceLayoutPersistenceTest {
    private static Method verifier() throws Exception {
        try {
            Method method=EdtCommandInterfaceService.class.getDeclaredMethod("verifyLayout",List.class,Snapshot.class);
            method.setAccessible(true);return method;
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("Missing persisted-layout verification",missing);
        }
    }
    private static Snapshot snapshot(String fqn,List<VisibilityEntry> visibility,List<GroupEntry> placement,List<GroupEntry> order) {
        return new Snapshot("Example","Subsystem.Example",fqn,"CommandInterface.cmi",true,true,0,0,0,visibility,placement,order,null,List.of());
    }
    @Test public void emptyUnattachedInterfaceCannotReportSuccessfulWrite() throws Exception {
        var operation=Map.<String,Object>of("op","place_commands","group","CommandGroup.Knowledge","commands",List.of("Catalog.Book.StandardCommand.OpenList"));
        var error=assertThrows(InvocationTargetException.class,()->verifier().invoke(null,List.of(operation),snapshot(null,List.of(),List.of(),List.of())));
        assertTrue(error.getCause() instanceof MetadataOperationException);
        assertTrue(error.getCause().getMessage().contains("layout was not persisted"));
    }
    @Test public void checksVisibilityPlacementAndExactRequestedOrder() throws Exception {
        String command="Catalog.Book.StandardCommand.OpenList", other="Catalog.Other.StandardCommand.OpenList",group="CommandGroup.Knowledge";
        var operations=List.of(
                Map.<String,Object>of("op","set_visibility","command",command,"visible",true),
                Map.<String,Object>of("op","place_commands","group",group,"commands",List.of(command,other)),
                Map.<String,Object>of("op","order_commands","group",group,"commands",List.of(command,other)));
        var visibility=List.of(new VisibilityEntry(1,command,"ok",true,1));
        var expected=List.of(new GroupEntry(group,List.of(command,other)));
        verifier().invoke(null,operations,snapshot("Subsystem.Example.CommandInterface",visibility,expected,expected));
        for (Snapshot wrong:List.of(
                snapshot("Subsystem.Example.CommandInterface",List.of(),expected,expected),
                snapshot("Subsystem.Example.CommandInterface",visibility,List.of(),expected),
                snapshot("Subsystem.Example.CommandInterface",visibility,expected,List.of(new GroupEntry(group,List.of(other,command)))))) {
            var error=assertThrows(InvocationTargetException.class,()->verifier().invoke(null,operations,wrong));
            assertTrue(error.getCause() instanceof MetadataOperationException);
        }
    }
}
