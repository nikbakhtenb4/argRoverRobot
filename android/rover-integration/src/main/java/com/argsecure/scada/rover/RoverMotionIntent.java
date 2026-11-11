package com.argsecure.scada.rover;

/** Exact current-user imperatives only; questions, quotes and coding requests cannot actuate. */
final class RoverMotionIntent {
    static String direction(String raw){
        if(raw==null||raw.length()>120||raw.contains("?")||raw.contains("؟")||raw.contains("\"")||raw.contains("«"))return null;
        String s=raw.replace('ي','ی').replace('ك','ک').replace('\u200c',' ').trim().replaceAll("[.!،]$","").trim();
        s=s.replaceFirst("^رستاگ[، ]+","").replaceFirst("^لطفا[ً ]*","").trim().replaceAll("\\s+"," ");
        if(s.matches("توقف|توقف کن|وایسا|بایست|حرکت نکن|بایست رستاگ"))return "stop";
        if(s.matches("برو جلو|بیا جلو|به جلو برو|جلو برو|به سمت جلو حرکت کن"))return "forward";
        if(s.matches("برو عقب|به عقب برو|عقب برو|به سمت عقب حرکت کن"))return "back";
        if(s.matches("برو چپ|به چپ بچرخ|به سمت چپ بچرخ|به سمت چپ حرکت کن"))return "left";
        if(s.matches("برو راست|به راست بچرخ|به سمت راست بچرخ|به سمت راست حرکت کن"))return "right";
        return null;
    }
}
