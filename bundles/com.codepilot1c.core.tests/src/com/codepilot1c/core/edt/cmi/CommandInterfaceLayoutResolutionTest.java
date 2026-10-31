package com.codepilot1c.core.edt.cmi;

import static org.junit.Assert.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.InternalEObject;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.StandardCommandSource;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class CommandInterfaceLayoutResolutionTest {
    @Test public void resolvesTheNamedExistingStandardCommandAndRejectsUnknownOwnerOrName() throws Exception {
        var command = MdClassFactory.eINSTANCE.createStandardCommand(); command.setName("List");
        var source = Proxy.newProxyInstance(StandardCommandSource.class.getClassLoader(), new Class<?>[]{StandardCommandSource.class},
                (proxy, method, args) -> method.getName().equals("getStandardCommands") ? new org.eclipse.emf.common.util.BasicEList<>(List.of(command)) : null);
        Map<String,Object> objects = Map.of("Catalog.Book", source);
        Function<String,Object> lookup = objects::get;
        var service = new EdtCommandInterfaceService();
        Method resolver = EdtCommandInterfaceService.class.getDeclaredMethod("resolveCommand", Function.class, String.class);
        resolver.setAccessible(true);
        assertSame(command, resolver.invoke(service, lookup, "Catalog.Book.StandardCommand.List"));
        for (String missing : List.of("Catalog.Missing.StandardCommand.List", "Catalog.Book.StandardCommand.Create", "0:11111111-1111-1111-1111-111111111111")) {
            var failure = assertThrows(InvocationTargetException.class, () -> resolver.invoke(service, lookup, missing));
            assertTrue(failure.getCause() instanceof MetadataOperationException);
            assertTrue(failure.getCause().getMessage().contains("Command not found in EDT model"));
        }
    }
    @Test public void resolvesCommonCommandButRejectsProxyWithoutInventingAReference() throws Exception {
        var command = MdClassFactory.eINSTANCE.createCommonCommand(); command.setName("OpenKnowledge");
        Function<String,Object> lookup = fqn -> fqn.equals("CommonCommand.OpenKnowledge") ? command : null;
        var service = new EdtCommandInterfaceService();
        Method resolver = EdtCommandInterfaceService.class.getDeclaredMethod("resolveCommand", Function.class, String.class);
        resolver.setAccessible(true);
        assertSame(command, resolver.invoke(service, lookup, "CommonCommand.OpenKnowledge"));
        ((InternalEObject) command).eSetProxyURI(URI.createURI("Missing"));
        var failure = assertThrows(InvocationTargetException.class, () -> resolver.invoke(service, lookup, "CommonCommand.OpenKnowledge"));
        assertTrue(failure.getCause() instanceof MetadataOperationException);
    }
}
