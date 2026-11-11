package com.argsecure.rastagmobility;

import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.argsecure.mobility.api.*;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;

/** Hardware integration test. Requires the paired ESP and its continuously running LiDAR. */
@RunWith(AndroidJUnit4.class)
public class LidarLiveTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private LidarReading awaitLive(ActivityScenario<LidarActivity> scenario,long after) {
        AtomicReference<LidarReading> found=new AtomicReference<>();
        for(int i=0;i<450;i++){
            scenario.onActivity(a->{LidarReading r=a.displayedReading();if(r!=null&&r.live(SystemClock.elapsedRealtime())&&r.receivedElapsed>after)found.set(r);});
            if(found.get()!=null)return found.get();SystemClock.sleep(100);
        }
        fail("No fresh frame from paired hardware within 45 seconds");return null;
    }
    @Test public void actualScansRenderAndRecoverAfterServiceRestart() throws Exception {
        try(ActivityScenario<LidarActivity> scenario=ActivityScenario.launch(LidarActivity.class)){
            LidarReading first=awaitLive(scenario,0),last=first;long maxAge=0;int frames=1;
            assertTrue(first.frame.count>100);assertTrue(first.frame.validCount>0);
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(SystemClock.elapsedRealtime()<deadline){
                last=awaitLive(scenario,last.receivedElapsed);frames++;
                assertEquals(first.frame.boot,last.frame.boot);maxAge=Math.max(maxAge,last.ageMs(SystemClock.elapsedRealtime()));
            }
            assertTrue("At least one displayed scan per second",frames>=10);
            onView(withTagValue(is((Object)"lidar_live_status"))).check(matches(withText(containsString("دریافت زنده"))));
            onView(withTagValue(is((Object)"lidar_range"))).perform(click()).check(matches(withText(containsString("8 m"))));
            onView(withTagValue(is((Object)"lidar_range"))).perform(click()).perform(click());
            Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(screenshot);
            try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"lidar-live-verified.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
            JsonObject report=new JsonObject();report.addProperty("frames_in_10_seconds",frames);report.addProperty("first_sequence",first.frame.sequence);
            report.addProperty("last_sequence",last.frame.sequence);report.addProperty("points",last.frame.count);
            report.addProperty("valid_points",last.frame.validCount);report.addProperty("nearest_mm",last.frame.nearestMm);
            report.addProperty("maximum_observed_age_ms",maxAge);
            // A dead producer must clear the live state independently of the heartbeat's longer lease.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->context.stopService(new Intent(context,MobilityService.class)));
            SystemClock.sleep(2200);
            onView(withTagValue(is((Object)"lidar_live_status"))).check(matches(withText(containsString("در انتظار"))));
            onView(withTagValue(is((Object)"lidar_nearest"))).check(matches(withText("—")));
            long restart=SystemClock.elapsedRealtime();
            MobilityReceiver.start(context,new Intent(context,MobilityService.class));awaitLive(scenario,restart);
            report.addProperty("stale_display_cleared",true);report.addProperty("service_restart_recovered",true);
            scenario.recreate();awaitLive(scenario,restart);
            report.addProperty("activity_recreation_recovered",true);
            try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"lidar-live-test.json"))){out.write(report.toString().getBytes(StandardCharsets.UTF_8));}
        }finally{MobilityReceiver.start(context,new Intent(context,MobilityService.class));}
    }
    @Test public void mappingScreenOpensTheLiveViewer() {
        try(ActivityScenario<MappingActivity> scenario=ActivityScenario.launch(MappingActivity.class)){
            onView(withTagValue(is((Object)"mapping_open_lidar"))).perform(click());
            onView(withTagValue(is((Object)"lidar_plot"))).check(matches(isDisplayed()));
            onView(withTagValue(is((Object)"lidar_back"))).perform(click());
            onView(withTagValue(is((Object)"mapping_open_lidar"))).check(matches(isDisplayed()));
        }
    }
}
