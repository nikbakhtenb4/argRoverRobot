package com.argsecure.scada.rover;
import org.junit.Test;
import static org.junit.Assert.*;
public class RoverMotionIntentTest {
    @Test public void exactCurrentCommands(){assertEquals("forward",RoverMotionIntent.direction("رستاگ برو جلو"));assertEquals("back",RoverMotionIntent.direction("به عقب برو"));assertEquals("left",RoverMotionIntent.direction("به چپ بچرخ"));assertEquals("right",RoverMotionIntent.direction("برو راست"));assertEquals("stop",RoverMotionIntent.direction("توقف کن"));assertEquals("stop",RoverMotionIntent.direction("حرکت نکن"));}
    @Test public void questionsNegationsQuotesAndCodingCannotMove(){for(String s:new String[]{"برو جلو؟","جلو نرو","اگه گفتم برو جلو حرکت کن","کد برو جلو را درست کن","گفتم برو جلو ولی نرفتی","برو جلو و برگرد","چرا حرکت نمی کنی","«برو جلو»","بگو برو جلو","به چپ بچرخ؟"})assertNull(s,RoverMotionIntent.direction(s));}
}
