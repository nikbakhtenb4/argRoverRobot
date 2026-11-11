package com.argsecure.rastagmobility;

import com.google.gson.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MappingDefinitionTest {
    private MapDefinition map(){return MapDefinition.create("Ground floor",12,8,10);}
    private void rejects(Runnable r){try{r.run();fail("Expected validation rejection");}catch(IllegalArgumentException|ArithmeticException expected){}}
    @Test public void roundTripPreservesMetricPlanAndExclusions(){
        MapDefinition m=map().origin(2,3).rotate().zone(new MapDefinition.Zone(7,1,2,2));
        MapCatalog c=MapCatalog.empty().save(m);MapCatalog copy=MapCatalog.parse(c.json());
        assertEquals(m.id,copy.selectedId);MapDefinition n=copy.selected();assertEquals("Ground floor",n.name);
        assertEquals(2,n.startX,0);assertEquals(3,n.startY,0);assertEquals(90,n.heading);assertEquals(10,n.resolutionCm);
        assertEquals(7,n.zones.get(0).x,0);assertEquals(2,n.zones.get(0).height,0);
        assertFalse(c.json().contains("occupancy"));assertFalse(c.json().contains("scan"));
    }
    @Test public void editsAreImmutableAndDoNotChangeSavedPlan(){MapDefinition m=map();MapCatalog c=MapCatalog.empty().save(m);
        MapDefinition edited=m.rename("Office").rotate();assertEquals("Ground floor",c.selected().name);assertEquals(0,c.selected().heading);
        assertEquals(1,c.save(edited).maps.size());assertEquals("Office",c.save(edited).selected().name);}
    @Test public void duplicateNamesRejectedButSameMapCanBeUpdated(){MapCatalog c=MapCatalog.empty().save(map());rejects(()->c.save(MapDefinition.create(" ground FLOOR ",8,8,10)));
        assertEquals(1,c.save(c.selected().rotate()).maps.size());}
    @Test public void deletingSelectedMapSelectsSurvivorAndThenEmpty(){MapDefinition a=map(),b=MapDefinition.create("Upstairs",4,4,20);
        MapCatalog c=MapCatalog.empty().save(a).save(b);c=c.delete(b.id);assertEquals(a.id,c.selectedId);c=c.delete(a.id);assertNull(c.selected());assertTrue(c.maps.isEmpty());}
    @Test public void selectionMustReferToExistingMap(){rejects(()->MapCatalog.empty().select("f".repeat(32)));}
    @Test public void dimensionsAndMemoryBudgetAreBounded(){rejects(()->MapDefinition.create("a",1,8,10));rejects(()->MapDefinition.create("a",101,8,10));
        rejects(()->MapDefinition.create("a",100,100,5));assertNotNull(MapDefinition.create("a",100,100,10));
        rejects(()->MapDefinition.create("a",Double.NaN,8,10));rejects(()->MapDefinition.create("a",8,Double.POSITIVE_INFINITY,10));}
    @Test public void resolutionAndNameValidated(){rejects(()->MapDefinition.create(" ",8,8,10));rejects(()->MapDefinition.create("a\nb",8,8,10));rejects(()->MapDefinition.create("a",8,8,7));}
    @Test public void originCannotEnterExclusionAndZoneCannotCoverOrigin(){MapDefinition m=map().zone(new MapDefinition.Zone(1,1,2,2));
        rejects(()->m.origin(2,2));rejects(()->m.zone(new MapDefinition.Zone(5,3,2,2)));rejects(()->m.origin(-1,2));assertEquals(6,m.startX,0);}
    @Test public void zonesMustHaveAreaAndStayInsideBounds(){rejects(()->map().zone(new MapDefinition.Zone(1,1,0,2)));
        rejects(()->map().zone(new MapDefinition.Zone(11,1,2,2)));rejects(()->map().zone(new MapDefinition.Zone(1,1,Double.NaN,2)));}
    @Test public void undoOnlyRemovesLastZone(){MapDefinition m=map().zone(new MapDefinition.Zone(0,0,1,1)).zone(new MapDefinition.Zone(10,6,1,1));
        assertEquals(1,m.undoZone().zones.size());assertEquals(2,m.zones.size());assertEquals(0,m.undoZone().undoZone().undoZone().zones.size());}
    @Test public void persianArabicAndLatinNumbersAccepted(){assertEquals(12.5,MapDefinition.inputNumber("۱۲٫۵"),0);assertEquals(12.5,MapDefinition.inputNumber("١٢.٥"),0);assertEquals(12.5,MapDefinition.inputNumber("12.5"),0);}
    @Test public void catalogCapacityAndVersionBounded(){MapCatalog c=MapCatalog.empty();for(int i=0;i<32;i++)c=c.save(MapDefinition.create("Map "+i,4,4,10));
        MapCatalog full=c;rejects(()->full.save(MapDefinition.create("Overflow",4,4,10)));rejects(()->MapCatalog.parse("{\"version\":2,\"selected_id\":\"\",\"maps\":[]}"));}
    @Test public void fractionalResolutionAndMalformedZoneRejected(){JsonObject o=map().json();o.addProperty("resolution_cm",10.5);rejects(()->MapDefinition.parse(o));
        JsonObject p=map().json();p.getAsJsonArray("zones").add(new JsonArray());rejects(()->MapDefinition.parse(p));}
    @Test public void invalidStoredSelectionRejected(){MapDefinition m=map();String raw=MapCatalog.empty().save(m).json().replace("\"selected_id\":\""+m.id,"\"selected_id\":\"00000000000000000000000000000000");rejects(()->MapCatalog.parse(raw));}
}
