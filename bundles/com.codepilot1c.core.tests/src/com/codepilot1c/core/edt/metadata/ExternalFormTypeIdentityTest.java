package com.codepilot1c.core.edt.metadata;
import static org.junit.Assert.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.form.model.*;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;

/** Calls the production form-attribute setter with a colliding namespace-local BM id. */
public class ExternalFormTypeIdentityTest {
    private TypeItem type(String name) {
        com._1c.g5.v8.dt.mcore.Type t=McoreFactory.eINSTANCE.createType();t.setName(name);return t;
    }
    private TypeDescription apply(String requested, String mapped, boolean column) throws Exception {
        TypeItem expected=type(requested), wrong=type(mapped);
        IBmPlatformTransaction tx=(IBmPlatformTransaction) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{IBmPlatformTransaction.class},(p,m,a)->{
                if(m.getName().equals("toTransactionObject"))return wrong;
                if(m.getName().contains("Iterator"))return Collections.emptyIterator();
                return null;
            });
        AbstractFormAttribute attr=column?FormFactory.eINSTANCE.createFormAttributeColumn():FormFactory.eINSTANCE.createFormAttribute();
        java.lang.reflect.Method method=EdtMetadataService.class.getDeclaredMethod("applyFormAttributeType",AbstractFormAttribute.class,
            Object.class,IBmPlatformTransaction.class,Map.class,Configuration.class);
        method.setAccessible(true);
        try{method.invoke(new EdtMetadataService(),attr,requested,tx,Map.of(requested,expected),null);}
        catch(InvocationTargetException e){throw new AssertionError(e.getCause());}
        TypeDescription result=attr instanceof FormAttribute f?f.getValueType():((FormAttributeColumn)attr).getValueType();
        assertEquals(1,result.getTypes().size());
        assertEquals(requested,result.getTypes().get(0).getName());
        return result;
    }
    @Test public void externalColumnRejectsUnrelatedCatalogWithCollidingId() throws Exception {
        apply("CatalogRef.ShippingTypes","CatalogRef.StorageStates",true);
    }
    @Test public void scalarAttributeRejectsUnrelatedDocumentWithCollidingId() throws Exception {
        apply("DocumentRef.Order","DocumentRef.Movement",false);
    }
    @Test public void sameNamespaceTypeIsPreserved() throws Exception {
        apply("CatalogRef.ShippingTypes","CatalogRef.ShippingTypes",true);
    }
    @Test public void similarNameIsNotTheRequestedType() throws Exception {
        apply("CatalogRef.ShippingTypes","CatalogRef.ShippingTypesExtra",true);
    }
    @SuppressWarnings("unchecked")
    private Set<String> collected(List<Map<String,Object>> attributes) throws Exception {
        java.lang.reflect.Method m=EdtMetadataService.class.getDeclaredMethod("collectFormAttributeTypeStrings",List.class);
        m.setAccessible(true);
        return (Set<String>)m.invoke(new EdtMetadataService(),attributes);
    }
    @Test public void columnOnlyUpdatePreResolvesItsReferenceType() throws Exception {
        assertTrue(collected(List.of(Map.of("name","Rows","columns",List.of(
            Map.of("name","ShippingType","type","CatalogRef.ShippingTypes")))))
            .contains("CatalogRef.ShippingTypes"));
    }
    @Test public void everyCompositeReferenceAndQualifierIsPreResolved() throws Exception {
        Set<String> types=collected(List.of(Map.of("name","Value","type",
            List.of("CatalogRef.ShippingTypes","String(20)","DocumentRef.Order"))));
        assertEquals(Set.of("CatalogRef.ShippingTypes","String","DocumentRef.Order"),types);
    }
}
