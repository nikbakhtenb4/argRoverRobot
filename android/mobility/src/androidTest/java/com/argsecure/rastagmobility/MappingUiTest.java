package com.argsecure.rastagmobility;

import android.content.Context;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.view.View;
import java.io.*;
import androidx.test.espresso.action.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MappingUiTest {
    @Test public void createPersistReopenAndDeleteDefinitionWithoutEnablingCapture() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();MapStore store=new MapStore(context);
        String original=store.read().selectedId,name="UI audit "+SystemClock.elapsedRealtime(),id="";
        ActivityScenario<MappingActivity> scenario=null;
        try{
            scenario=ActivityScenario.launch(MappingActivity.class);
            waitReady();
            onView(withTagValue(is((Object)"mapping_new"))).perform(click());
            onView(withTagValue(is((Object)"mapping_name"))).perform(scrollTo(),click(),replaceText(name));
            screenshot(context,"mapping-ui-keyboard.png");
            onView(withTagValue(is((Object)"mapping_hide_keyboard"))).perform(click());
            onView(withText("ایجاد پیش‌نویس")).perform(click());
            onView(withTagValue(is((Object)"mapping_origin"))).perform(scrollTo(),click());
            onView(withTagValue(is((Object)"mapping_plan"))).perform(new GeneralClickAction(Tap.SINGLE,point(.7f,.8f),Press.FINGER));
            onView(withTagValue(is((Object)"mapping_zone"))).perform(scrollTo(),click());
            onView(withTagValue(is((Object)"mapping_plan"))).perform(new GeneralSwipeAction(Swipe.SLOW,point(.1f,.1f),point(.3f,.3f),Press.FINGER));
            onView(withTagValue(is((Object)"mapping_rotate"))).perform(scrollTo(),click());
            onView(withTagValue(is((Object)"mapping_save"))).perform(scrollTo(),click());
            for(int i=0;i<50;i++){MapDefinition selected=store.read().selected();if(selected!=null&&selected.name.equals(name)){id=selected.id;break;}SystemClock.sleep(100);}
            assertFalse("Definition must reach durable storage",id.isEmpty());assertEquals(90,store.read().selected().heading);
            assertEquals(1,store.read().selected().zones.size());assertEquals(7,store.read().selected().startX,.2);assertEquals(2,store.read().selected().startY,.2);
            screenshot(context,"mapping-ui-verified.png");
            onView(withTagValue(is((Object)"mapping_capture"))).perform(scrollTo()).check(matches(not(isEnabled())));
            scenario.recreate();waitReady();assertEquals(id,store.read().selectedId);
            onView(withTagValue(is((Object)"mapping_delete"))).perform(scrollTo(),click());
            onView(withText("حذف")).perform(click());
            for(int i=0;i<50;i++){boolean present=false;for(MapDefinition m:store.read().maps)if(m.id.equals(id))present=true;if(!present)break;SystemClock.sleep(100);}
            for(MapDefinition m:store.read().maps)assertNotEquals(id,m.id);
        }finally{
            if(scenario!=null)scenario.close();
            MapCatalog c=store.read();for(MapDefinition m:c.maps)if(m.name.equals(name))c=c.delete(m.id);
            if(!original.isEmpty()){for(MapDefinition m:c.maps)if(m.id.equals(original)){c=c.select(original);break;}}
            store.write(c);
        }
    }
    private CoordinatesProvider point(float x,float y){return view->{int[] location=new int[2];view.getLocationOnScreen(location);
        float side=Math.min(view.getWidth(),view.getHeight())-64*view.getResources().getDisplayMetrics().density;
        return new float[]{location[0]+(view.getWidth()-side)/2+side*x,location[1]+(view.getHeight()-side)/2+side*y};};}
    private void screenshot(Context context,String name) throws IOException {Bitmap b=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(b);try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    private void waitReady(){for(int i=0;i<50;i++){try{onView(withTagValue(is((Object)"mapping_new"))).check(matches(isEnabled()));return;}catch(AssertionError ignored){SystemClock.sleep(100);}}fail("Mapping screen did not load");}
}
