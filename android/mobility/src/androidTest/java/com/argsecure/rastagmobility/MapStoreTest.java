package com.argsecure.rastagmobility;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MapStoreTest {
    private File temporary(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();return new File(c.getCacheDir(),"map-test-"+UUID.randomUUID()+".json");}
    @Test public void definitionsSurviveStoreRecreationAndSelectionChanges() throws Exception {
        File path=temporary();try{MapStore s=new MapStore(path);assertTrue(s.read().maps.isEmpty());
            MapDefinition a=MapDefinition.create("First",10,8,10).origin(1,2).zone(new MapDefinition.Zone(6,5,1,1));
            MapDefinition b=MapDefinition.create("Second",8,8,20);s.write(MapCatalog.empty().save(a).save(b).select(a.id));
            MapCatalog loaded=new MapStore(path).read();assertEquals(a.id,loaded.selectedId);assertEquals(1,loaded.selected().zones.size());
            s.write(loaded.delete(a.id));assertEquals(b.id,new MapStore(path).read().selectedId);
        }finally{path.delete();new File(path.getPath()+".bak").delete();}}
    @Test public void corruptStorageIsNotSilentlyReplacedWithEmptyCatalog() throws Exception {
        File path=temporary();try{byte[] broken="not valid JSON".getBytes(StandardCharsets.UTF_8);try(FileOutputStream out=new FileOutputStream(path)){out.write(broken);}
            try{new MapStore(path).read();fail("Corrupt catalog must fail");}catch(IOException expected){}
            try(FileInputStream in=new FileInputStream(path)){assertArrayEquals(broken,EspPairing.read(in,100));}
        }finally{path.delete();}}
    @Test public void interruptedAtomicWriteRecoversLastGoodDefinition() throws Exception {
        File path=temporary();try{MapStore s=new MapStore(path);MapDefinition a=MapDefinition.create("Preserved",6,6,10);s.write(MapCatalog.empty().save(a));
            File backup=new File(path.getPath()+".bak");assertTrue(path.renameTo(backup));try(FileOutputStream out=new FileOutputStream(path)){out.write('{');}
            assertEquals(a.id,new MapStore(path).read().selectedId);
        }finally{path.delete();new File(path.getPath()+".bak").delete();}}
}
