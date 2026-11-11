package com.argsecure.rastagmobility;

import com.google.gson.*;
import java.util.*;

/** Small transactional catalog of user definitions; no synthetic scan data. */
final class MapCatalog {
    static final int MAX_MAPS=32;
    final List<MapDefinition> maps;
    final String selectedId;
    MapCatalog(List<MapDefinition> values,String selected) {
        if(values.size()>MAX_MAPS)throw new IllegalArgumentException("too_many_maps");
        Set<String> ids=new HashSet<>(), names=new HashSet<>();
        for(MapDefinition m:values){m.validate();if(!ids.add(m.id)||!names.add(m.name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("duplicate_name");}
        if(!selected.isEmpty()&&!ids.contains(selected))throw new IllegalArgumentException("invalid_selection");
        maps=Collections.unmodifiableList(new ArrayList<>(values));selectedId=selected;
    }
    static MapCatalog empty(){return new MapCatalog(Collections.emptyList(),"");}
    MapDefinition selected(){for(MapDefinition m:maps)if(m.id.equals(selectedId))return m;return null;}
    MapCatalog save(MapDefinition map){List<MapDefinition> list=new ArrayList<>();boolean replaced=false;
        for(MapDefinition m:maps){if(m.id.equals(map.id)){list.add(map);replaced=true;}else list.add(m);}if(!replaced)list.add(map);return new MapCatalog(list,map.id);}
    MapCatalog select(String id){return new MapCatalog(maps,id);}
    MapCatalog delete(String id){List<MapDefinition> list=new ArrayList<>();for(MapDefinition m:maps)if(!m.id.equals(id))list.add(m);
        return new MapCatalog(list,selectedId.equals(id)?(list.isEmpty()?"":list.get(0).id):selectedId);}
    String json(){JsonObject o=new JsonObject();o.addProperty("version",1);o.addProperty("selected_id",selectedId);JsonArray a=new JsonArray();
        for(MapDefinition m:maps)a.add(m.json());o.add("maps",a);return o.toString();}
    static MapCatalog parse(String raw){
        if(raw==null||raw.length()>262144)throw new IllegalArgumentException("catalog_size");
        JsonObject o=JsonParser.parseString(raw).getAsJsonObject();
        if(o.size()!=3||MapDefinition.integer(o.get("version"))!=1)throw new IllegalArgumentException("catalog_version");
        List<MapDefinition> list=new ArrayList<>();JsonArray a=o.getAsJsonArray("maps");if(a.size()>MAX_MAPS)throw new IllegalArgumentException("too_many_maps");
        for(JsonElement m:a)list.add(MapDefinition.parse(m.getAsJsonObject()));return new MapCatalog(list,MapDefinition.text(o.get("selected_id")));
    }
}
