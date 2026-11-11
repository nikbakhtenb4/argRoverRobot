package com.argsecure.rastagmobility;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.gson.*;
import com.argsecure.mobility.api.*;
import java.util.Locale;

/** Local operator control. A held button must renew permission on the UI thread. */
public final class MotionActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private EspLink controls;
    private TextView decision,detail;
    private CheckBox remote,verified;
    private EditText left,right,front,stop;
    private final EditText[] presets=new EditText[8];
    private SeekBar intensity;
    private long held;
    private boolean bound,resumed,loaded;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){controls=((MobilityService.LocalBinder)service).controls();render();}
        @Override public void onServiceDisconnected(ComponentName name){controls=null;held=0;render();}
    };
    private final Runnable renew=new Runnable(){
        @Override public void run(){if(resumed&&held!=0&&controls!=null){controls.renewManual(held);main.postDelayed(this,60);}}
    };
    private final Runnable refresh=new Runnable(){
        @Override public void run(){if(resumed){render();main.postDelayed(this,250);}}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xFF071821);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,20);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);scroll.addView(root);setContentView(scroll);
        text(root,"کنترل حرکت رستاگ",24);decision=text(root,"در انتظار کنترلر",20);decision.setTag("motion_decision");
        detail=text(root,"برای حرکت، دکمهٔ جهت را نگه دارید. رهاکردن دکمه فرمان را قطع می‌کند.",15);
        LinearLayout row=row(root);hold(row,"↑ جلو",1);hold(row,"↓ عقب",2);hold(row,"← چپ",3);hold(row,"→ راست",4);
        Button emergency=button(root,"■ توقف",v->{endHold();if(controls!=null)controls.stopMotion();});
        emergency.setTag("motion_stop");emergency.setTextColor(0xFFFF9B97);
        text(root,"شدت فرمان: 20٪",15).setTag("motion_intensity_label");
        intensity=new SeekBar(this);intensity.setMax(99);intensity.setProgress(19);root.addView(intensity);
        intensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean user){((TextView)root.findViewWithTag("motion_intensity_label")).setText(String.format(Locale.US,"شدت فرمان: %d٪",p+1));}
            public void onStartTrackingTouch(SeekBar s){endHold();}public void onStopTrackingTouch(SeekBar s){}
        });
        remote=new CheckBox(this);remote.setText("اجازهٔ فرمان کاربر و هوش مصنوعی برای 60 ثانیه");remote.setTextColor(0xFFE2F0F4);root.addView(remote);
        remote.setOnClickListener(v->{if(controls!=null)controls.allowRemote(remote.isChecked());});
        LinearLayout calibration=new LinearLayout(this);calibration.setOrientation(LinearLayout.VERTICAL);calibration.setVisibility(View.GONE);
        button(root,"تنظیم و کالیبراسیون جهت‌ها",v->{endHold();calibration.setVisibility(calibration.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});root.addView(calibration);
        text(calibration,"آزمایش زاویه‌ها بدون فعال‌کردن حرکت ربات",18);
        LinearLayout jog=row(calibration);left=number(jog,"100.00","زاویهٔ ۱");right=number(jog,"105.00","زاویهٔ ۲");
        Button test=button(calibration,"نگه‌دار: آزمایش زاویه‌ها",null);test.setTag("motion_calibration_hold");
        test.setOnTouchListener((v,e)->touch(v,e,()->{
            if(controls!=null)held=controls.beginManual("jog",angle(left),angle(right));
        }));
        text(calibration,"زاویهٔ آزمایش‌شده برای هر جهت را ثبت کنید؛ چهار جهت باید متمایز باشند.",15);
        String[] names={"جلو","عقب","چپ","راست"};
        for(int d=0;d<4;d++){
            text(calibration,names[d],16);LinearLayout pair=row(calibration);
            presets[2*d]=number(pair,"100.00","زاویهٔ ۱");presets[2*d+1]=number(pair,"105.00","زاویهٔ ۲");
        }
        LinearLayout geometry=row(calibration);front=number(geometry,"0.00","زاویهٔ جلوی لیدار");stop=number(geometry,"400","فاصلهٔ توقف، میلی‌متر");
        verified=new CheckBox(this);verified.setText("جهت‌ها، سیم‌کشی و قطع حرکت را آزمایش و تأیید کرده‌ام");verified.setTextColor(0xFFE2F0F4);calibration.addView(verified);
        button(calibration,"ذخیرهٔ کالیبراسیون",v->save());
        button(root,"بستن صفحه",v->{hideKeyboard();finish();});
        MobilityReceiver.start(this,new Intent(this,MobilityService.class));
    }
    private void hold(LinearLayout row,String label,int dir){
        Button b=button(row,label,null);b.setTag("motion_direction_"+dir);
        b.setOnTouchListener((v,e)->touch(v,e,()->{if(controls!=null)held=controls.beginManual("drive",dir,intensity.getProgress()+1);}));
    }
    private boolean touch(View view,MotionEvent event,Runnable begin){
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                endHold();hideKeyboard();try{begin.run();main.post(renew);view.setPressed(true);}catch(RuntimeException e){message("مقدار زاویه معتبر نیست");}return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:case MotionEvent.ACTION_POINTER_DOWN:
                endHold();view.setPressed(false);return true;
            default:return true;
        }
    }
    private void endHold(){main.removeCallbacks(renew);if(controls!=null&&held!=0)controls.releaseManual(held);held=0;}
    private int angle(EditText e){int value=Math.round(Float.parseFloat(e.getText().toString())*100);if(value<8000||value>12000)throw new IllegalArgumentException();return value;}
    private void save(){
        endHold();hideKeyboard();if(controls==null)return;
        try{
            if(!verified.isChecked())throw new IllegalArgumentException();
            JsonObject p=new JsonObject();String[] keys={"f_left","f_right","b_left","b_right","l_left","l_right","r_left","r_right"};
            int[][] a=new int[4][2];for(int i=0;i<8;i++){a[i/2][i%2]=angle(presets[i]);p.addProperty(keys[i],a[i/2][i%2]);}
            for(int d=0;d<4;d++){
                if(a[d][0]==10000&&a[d][1]==10500)throw new IllegalArgumentException();
                for(int j=0;j<d;j++)if(a[d][0]==a[j][0]&&a[d][1]==a[j][1])throw new IllegalArgumentException();
            }
            float degrees=Float.parseFloat(front.getText().toString());if(Float.isNaN(degrees)||Float.isInfinite(degrees))throw new IllegalArgumentException();
            int f=Math.round(degrees*100),mm=Integer.parseInt(stop.getText().toString());
            if(f<0||f>=36000||mm<300||mm>3000)throw new IllegalArgumentException();
            p.addProperty("front_cdeg",f);p.addProperty("stop_mm",mm);p.addProperty("verified",true);
            controls.saveMotionProfile(p);message("تنظیمات برای تأیید کنترلر فرستاده شد");
        }catch(RuntimeException e){message("زاویه‌ها باید بین 80 و 120، جهت‌ها متفاوت و تأیید آزمون فعال باشد");}
    }
    private void render(){
        if(decision==null)return;
        if(controls==null){decision.setText("ارتباط کنترل حرکت در دسترس نیست");return;}
        MotionSnapshot s=controls.motionSnapshot(SystemClock.elapsedRealtime());
        decision.setText("درخواست: "+MotionContract.label(s.requested)+"    تصمیم: "+MotionContract.label(s.decision)+"\n"+MotionContract.reason(s.reason));
        detail.setText(s.configured?"دکمه را نگه دارید؛ توقف با رهاکردن، مانع یا قطع ارتباط انجام می‌شود.":"کالیبراسیون جهت‌ها لازم است؛ حرکت فعال نشده است.");
        if(remote.isChecked()&&!controls.remoteAllowed())remote.setChecked(false);
        if(!loaded)try{
            JsonObject p=JsonParser.parseString(controls.motionRaw()).getAsJsonObject().getAsJsonObject("profile");
            String[] keys={"f_left","f_right","b_left","b_right","l_left","l_right","r_left","r_right"};
            for(int i=0;i<8;i++)presets[i].setText(String.format(Locale.US,"%.2f",p.get(keys[i]).getAsInt()/100.0));
            front.setText(String.format(Locale.US,"%.2f",p.get("front_cdeg").getAsInt()/100.0));stop.setText(p.get("stop_mm").getAsString());loaded=true;
        }catch(RuntimeException ignored){}
    }
    private LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);parent.addView(r);return r;}
    private TextView text(LinearLayout parent,String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(0xFFD6EEF4);v.setPadding(0,8,0,8);parent.addView(v);return v;}
    private Button button(LinearLayout parent,String value,View.OnClickListener click){Button b=new Button(this);b.setText(value);b.setOnClickListener(click);b.setFilterTouchesWhenObscured(true);
        parent.addView(b,parent.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,-2,1):new LinearLayout.LayoutParams(-1,-2));return b;}
    private EditText number(LinearLayout parent,String value,String hint){EditText e=new EditText(this);e.setHint(hint);e.setText(value);e.setTextColor(0xFFE2F0F4);e.setHintTextColor(0xFF9CB6BF);e.setSelectAllOnFocus(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);e.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);e.setSingleLine(true);parent.addView(e,new LinearLayout.LayoutParams(0,-2,1));return e;}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(decision.getWindowToken(),0);}
    private void message(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();resumed=true;bound=bindService(new Intent(this,MobilityService.class),connection,BIND_AUTO_CREATE);main.post(refresh);}
    @Override protected void onPause(){resumed=false;endHold();if(controls!=null)controls.stopMotion();main.removeCallbacks(refresh);if(bound){unbindService(connection);bound=false;}controls=null;super.onPause();}
}
