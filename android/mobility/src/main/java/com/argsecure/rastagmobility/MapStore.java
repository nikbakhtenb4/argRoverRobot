package com.argsecure.rastagmobility;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class MapStore {
    private final AtomicFile file;
    MapStore(Context context){this(new File(context.getNoBackupFilesDir(),"mapping_definitions_v1.json"));}
    MapStore(File path){file=new AtomicFile(path);}
    MapCatalog read() throws IOException {
        try(InputStream stream=file.openRead()){
            try{return MapCatalog.parse(new String(EspPairing.read(stream,262144),StandardCharsets.UTF_8));}
            catch(RuntimeException e){throw new IOException("Invalid map storage",e);}
        }catch(FileNotFoundException e){if(file.getBaseFile().exists())throw e;return MapCatalog.empty();}
    }
    void write(MapCatalog catalog) throws IOException {
        byte[] bytes=catalog.json().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>262144)throw new IOException("Catalog size");
        FileOutputStream stream=null;
        try{stream=file.startWrite();stream.write(bytes);file.finishWrite(stream);}
        catch(IOException e){if(stream!=null)file.failWrite(stream);throw e;}
    }
}
