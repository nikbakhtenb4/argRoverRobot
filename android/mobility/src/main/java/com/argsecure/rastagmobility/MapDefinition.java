package com.argsecure.rastagmobility;

import com.google.gson.*;
import java.text.Normalizer;
import java.util.*;

/** A user's mapping plan, never a measured occupancy map or an instruction to move. */
final class MapDefinition {
    static final int MAX_ZONES = 32;
    final String id, name;
    final double width, height, startX, startY;
    final int resolutionCm, heading;
    final List<Zone> zones;
    static final class Zone {
        final double x, y, width, height;
        Zone(double x,double y,double w,double h) { this.x=x;this.y=y;width=w;height=h; }
        boolean contains(double px,double py) { return px>=x && px<=x+width && py>=y && py<=y+height; }
    }
    MapDefinition(String id,String name,double width,double height,int resolution,double x,double y,int heading,List<Zone> zones) {
        this.id=id;this.name=Normalizer.normalize(name == null ? "" : name.trim(),Normalizer.Form.NFKC);
        this.width=width;this.height=height;resolutionCm=resolution;startX=x;startY=y;this.heading=heading;
        this.zones=Collections.unmodifiableList(new ArrayList<>(zones)); validate();
    }
    static MapDefinition create(String name,double w,double h,int resolution) {
        return new MapDefinition(UUID.randomUUID().toString().replace("-",""),name,w,h,resolution,w/2,h/2,0,Collections.emptyList());
    }
    void validate() {
        if(id==null || !id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("invalid_id");
        if(name.isEmpty() || name.length()>60) throw new IllegalArgumentException("invalid_name");
        for(char c:name.toCharArray())if(Character.isISOControl(c))throw new IllegalArgumentException("invalid_name");
        if(!finite(width)||!finite(height)||width<2||height<2||width>100||height>100) throw new IllegalArgumentException("invalid_dimensions");
        if(resolutionCm!=5 && resolutionCm!=10 && resolutionCm!=20) throw new IllegalArgumentException("invalid_resolution");
        if(Math.ceil(width*100/resolutionCm)*Math.ceil(height*100/resolutionCm)>1000000) throw new IllegalArgumentException("grid_too_large");
        if(!finite(startX)||!finite(startY)||startX<0||startY<0||startX>width||startY>height) throw new IllegalArgumentException("invalid_origin");
        if(heading<0||heading>=360||heading%90!=0) throw new IllegalArgumentException("invalid_heading");
        if(zones.size()>MAX_ZONES) throw new IllegalArgumentException("too_many_zones");
        for(Zone z:zones) {
            if(z==null||!finite(z.x)||!finite(z.y)||!finite(z.width)||!finite(z.height)
                    ||z.x<0||z.y<0||z.width<.1||z.height<.1||z.x+z.width>width+.00001||z.y+z.height>height+.00001)
                throw new IllegalArgumentException("invalid_zone");
            if(z.contains(startX,startY)) throw new IllegalArgumentException("origin_in_zone");
        }
    }
    private static boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v);}
    MapDefinition origin(double x,double y) { return new MapDefinition(id,name,width,height,resolutionCm,x,y,heading,zones); }
    MapDefinition rotate() { return new MapDefinition(id,name,width,height,resolutionCm,startX,startY,(heading+90)%360,zones); }
    MapDefinition rename(String value) { return new MapDefinition(id,value,width,height,resolutionCm,startX,startY,heading,zones); }
    MapDefinition zone(Zone zone) { List<Zone> list=new ArrayList<>(zones);list.add(zone);return withZones(list); }
    MapDefinition undoZone() { List<Zone> list=new ArrayList<>(zones);if(!list.isEmpty())list.remove(list.size()-1);return withZones(list); }
    private MapDefinition withZones(List<Zone> values) { return new MapDefinition(id,name,width,height,resolutionCm,startX,startY,heading,values); }
    JsonObject json() {
        JsonObject o=new JsonObject();o.addProperty("id",id);o.addProperty("name",name);o.addProperty("width_m",width);o.addProperty("height_m",height);
        o.addProperty("resolution_cm",resolutionCm);o.addProperty("start_x_m",startX);o.addProperty("start_y_m",startY);o.addProperty("heading_deg",heading);
        JsonArray array=new JsonArray();for(Zone z:zones){JsonArray a=new JsonArray();a.add(z.x);a.add(z.y);a.add(z.width);a.add(z.height);array.add(a);}o.add("zones",array);
        return o;
    }
    static MapDefinition parse(JsonObject o) {
        if(o.size()!=9)throw new IllegalArgumentException("invalid_map");
        List<Zone> zones=new ArrayList<>();JsonArray a=o.getAsJsonArray("zones");
        if(a.size()>MAX_ZONES)throw new IllegalArgumentException("too_many_zones");
        for(JsonElement value:a){JsonArray z=value.getAsJsonArray();if(z.size()!=4)throw new IllegalArgumentException("invalid_zone");
            zones.add(new Zone(decimal(z.get(0)),decimal(z.get(1)),decimal(z.get(2)),decimal(z.get(3))));}
        return new MapDefinition(text(o.get("id")),text(o.get("name")),decimal(o.get("width_m")),decimal(o.get("height_m")),
                integer(o.get("resolution_cm")),decimal(o.get("start_x_m")),decimal(o.get("start_y_m")),integer(o.get("heading_deg")),zones);
    }
    static String text(JsonElement e){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException();return e.getAsString();}
    static double decimal(JsonElement e){if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();return e.getAsDouble();}
    static int integer(JsonElement e){decimal(e);return e.getAsBigDecimal().intValueExact();}
    static double inputNumber(String raw) {
        StringBuilder b=new StringBuilder();
        for(char c:raw.trim().toCharArray()) {
            if(c>='۰'&&c<='۹')b.append((char)('0'+c-'۰'));else if(c>='٠'&&c<='٩')b.append((char)('0'+c-'٠'));
            else b.append(c=='٫'?'.':c);
        }
        return Double.parseDouble(b.toString());
    }
}
