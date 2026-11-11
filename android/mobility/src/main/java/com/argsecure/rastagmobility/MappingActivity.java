package com.argsecure.rastagmobility;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.content.ContextCompat;
import com.argsecure.mobility.api.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;

/** Local mapping definitions. Drawing a plan never commands a motor or invents an observed map. */
public final class MappingActivity extends Activity implements MapPlanView.Editor {
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService io=Executors.newSingleThreadExecutor();
    private MapStore store;
    private MapCatalog catalog=MapCatalog.empty();
    private MapDefinition draft;
    private boolean dirty,busy=true,storageReady,alive=true,resumed;
    private int mode;
    private LinearLayout library,details;
    private TextView status,info,message,title,readiness,modeHint;
    private Button create,save;
    private MapPlanView plan;
    private MobilitySnapshot link;
    private String lidar="unavailable";
    private long healthAt;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){
            try {
                String raw=i.getStringExtra(MobilityContract.PAYLOAD);long now=SystemClock.elapsedRealtime();
                if(MobilityContract.STATUS.equals(i.getAction())){
                    MobilitySnapshot next=MobilitySnapshot.parse(raw,now);if(next.isNewerThan(link))link=next;
                }else{
                    if(raw==null||raw.length()>1024)throw new IllegalArgumentException();
                    JsonObject o=JsonParser.parseString(raw).getAsJsonObject();long published=o.get("published_elapsed_ms").getAsLong();
                    String state=MapDefinition.text(o.get("lidar_state"));
                    if(MapDefinition.integer(o.get("version"))!=1||published>now||now-published>=6000||published<healthAt
                            ||!Arrays.asList("unavailable","not_configured","unverified","no_data","ready").contains(state))throw new IllegalArgumentException();
                    lidar=state;healthAt=published;
                }
            }catch(RuntimeException ignored){}renderHealth();
        }
    };
    private final Runnable refresh=new Runnable(){public void run(){if(resumed){renderHealth();main.postDelayed(this,1000);}}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=new MapStore(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(0xFF08141D);getWindow().setNavigationBarColor(0xFF08141D);
        LinearLayout root=column();root.setPadding(dp(14),dp(6),dp(14),dp(8));root.setBackgroundColor(0xFF07121B);root.setFocusableInTouchMode(true);setContentView(root);
        LinearLayout header=row();root.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
        title=text("تعریف نقشه",22,0xFFE4F4FA);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        status=text("ارتباط: در حال بررسی",13,0xFF9FBBCB);header.addView(status);
        button(header,"لیدار زنده","mapping_open_lidar",()->startActivity(new Intent(this,LidarActivity.class)));
        button(header,"بازگشت","mapping_back",this::onBackPressed);
        LinearLayout body=row();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout listPanel=column();listPanel.setPadding(dp(10),dp(6),dp(10),0);listPanel.setBackground(panel());body.addView(listPanel,new LinearLayout.LayoutParams(dp(210),-1));
        listPanel.addView(text("نقشه‌های من",18,0xFFE4F4FA));create=button(listPanel,"+ نقشهٔ جدید","mapping_new",()->discardThen(()->definitionDialog(null)));
        ScrollView listScroll=new ScrollView(this);library=column();listScroll.addView(library);listPanel.addView(listScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout center=column();LinearLayout.LayoutParams centerParams=new LinearLayout.LayoutParams(0,-1,1);centerParams.setMargins(dp(10),0,dp(10),0);body.addView(center,centerParams);
        info=text("محدودهٔ تعریف‌شده • بدون دادهٔ برداشت",13,0xFFABC3D2);center.addView(info);
        plan=new MapPlanView(this);plan.setTag("mapping_plan");center.addView(plan,new LinearLayout.LayoutParams(-1,0,1));
        modeHint=text("نقطهٔ سبز: شروع پیشنهادی   •   قرمز: ناحیهٔ ممنوعه",12,0xFF93B2C3);center.addView(modeHint);
        ScrollView editScroll=new ScrollView(this);editScroll.setFillViewport(true);details=column();details.setPadding(dp(12),dp(6),dp(12),dp(6));details.setBackground(panel());editScroll.addView(details);body.addView(editScroll,new LinearLayout.LayoutParams(dp(258),-1));
        message=text("در حال خواندن نقشه‌ها…",13,0xFFFFCF78);message.setTag("mapping_message");root.addView(message);
        // On narrow displays stack panels inside a scroll area, preserving OS keyboard/navigation insets.
        if(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density<800){
            root.removeView(body);body.setOrientation(LinearLayout.VERTICAL);
            listPanel.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(180)));center.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(280)));editScroll.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(360)));
            ScrollView all=new ScrollView(this);all.addView(body);root.addView(all,1,new LinearLayout.LayoutParams(-1,0,1));
        }
        String restored=state==null?null:state.getString("draft");boolean restoredDirty=state!=null&&state.getBoolean("dirty");
        io.execute(()->{
            try{MapCatalog loaded=store.read();main.post(()->{if(!alive)return;catalog=loaded;draft=loaded.selected();
                if(restored!=null)try{draft=MapDefinition.parse(JsonParser.parseString(restored).getAsJsonObject());dirty=restoredDirty;}catch(RuntimeException ignored){}
                busy=false;storageReady=true;message.setText("");render();});}
            catch(Exception e){main.post(()->{if(!alive)return;busy=false;storageReady=false;message.setText("خواندن نقشه‌ها ممکن نشد؛ اطلاعات موجود حفظ شده است.");render();});}
        });
        MobilityReceiver.start(this,new Intent(this,MobilityService.class));render();
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);return v;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable panel(){GradientDrawable g=new GradientDrawable();g.setColor(0xFF10212D);g.setCornerRadius(dp(12));return g;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(3),dp(4),dp(3),dp(4));return t;}
    private Button button(LinearLayout parent,String label,String tag,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTag(tag);b.setMinHeight(dp(42));b.setMinimumHeight(dp(42));
        b.setPadding(dp(8),0,dp(8),0);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.VERTICAL?-1:-2,dp(44)));return b;
    }
    private void render(){
        if(plan==null)return;create.setEnabled(storageReady&&!busy);library.removeAllViews();
        if(catalog.maps.isEmpty())library.addView(text("هنوز نقشه‌ای ذخیره نشده",13,0xFF93B2C3));
        for(MapDefinition m:catalog.maps){Button b=button(library,(draft!=null&&draft.id.equals(m.id)?"✓ ":"")+m.name,"map_"+m.id,
                ()->discardThen(()->persist(catalog.select(m.id),"نقشه برای ویرایش انتخاب شد")));b.setEnabled(!busy);}
        plan.display(draft,busy?0:mode,this);details.removeAllViews();
        title.setText(draft==null?"تعریف نقشه":"تعریف نقشه · "+draft.name+(dirty?" *":""));title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(draft==null){details.addView(text("۱  تعریف محیط",18,0xFFE4F4FA));details.addView(text("نام، ابعاد و دقت نقشه را مشخص کنید. نقطهٔ شروع و نواحی ممنوعه قابل ویرایش‌اند.",14,0xFFABC3D2));}
        else{
            details.addView(text(String.format(Locale.US,"%.1f × %.1f m  ·  %d cm",draft.width,draft.height,draft.resolutionCm),17,0xFF63E0AD));
            details.addView(text("شروع: \u2066"+String.format(Locale.US,"X %.1f · Y %.1f m · %d°",draft.startX,draft.startY,draft.heading)+"\u2069",13,0xFFABC3D2));
            button(details,"تغییر نام","mapping_rename",()->definitionDialog(draft)).setEnabled(!busy);
            button(details,mode==1?"✓ تعیین نقطهٔ شروع":"تعیین نقطهٔ شروع","mapping_origin",()->{mode=mode==1?0:1;render();}).setEnabled(!busy);
            button(details,"چرخش جهت شروع 90°","mapping_rotate",()->change(draft.rotate())).setEnabled(!busy);
            button(details,mode==2?"✓ رسم ناحیهٔ ممنوعه":"رسم ناحیهٔ ممنوعه","mapping_zone",()->{mode=mode==2?0:2;render();}).setEnabled(!busy);
            button(details,"حذف آخرین ناحیه","mapping_undo_zone",()->change(draft.undoZone())).setEnabled(!busy&&!draft.zones.isEmpty());
            save=button(details,dirty?"ذخیرهٔ تعریف نقشه":"ذخیره شده","mapping_save",()->{try{persist(catalog.save(draft),"تعریف نقشه ذخیره شد");}catch(RuntimeException e){error(e);}});save.setEnabled(dirty&&!busy&&storageReady);
            button(details,"حذف نقشه","mapping_delete",this::delete).setEnabled(!busy);
        }
        details.addView(text("آمادگی برداشت",16,0xFFE4F4FA));readiness=text("",13,0xFFFFCF78);readiness.setTag("mapping_readiness");details.addView(readiness);
        Button capture=button(details,"شروع برداشت نقشه","mapping_capture",()->{});capture.setEnabled(false);
        modeHint.setText(mode==1?"برای تعیین نقطهٔ شروع، روی محدوده بزنید":mode==2?"برای رسم ناحیهٔ ممنوعه، روی محدوده بکشید":"این طرح هنوز به کنترل حرکت متصل نیست؛ نقطهٔ سبز، شروع پیشنهادی است.");
        renderHealth();
    }
    private void renderHealth(){
        if(status==null)return;long now=SystemClock.elapsedRealtime();LinkHealth.State state=link==null?LinkHealth.State.CONNECTING:link.state(now);
        status.setText("ارتباط: "+MobilityActivity.label(state));status.setTextColor(state==LinkHealth.State.CONNECTED?0xFF63E0AD:0xFFFFCF78);
        if(readiness==null)return;
        String sensor=now<healthAt||now-healthAt>=6000||state!=LinkHealth.State.CONNECTED?"unavailable":lidar;
        String value;
        switch(sensor){case "not_configured":value="لیدار هنوز راه‌اندازی نشده است.";break;case "unverified":value="لیدار نیاز به بررسی و کالیبراسیون دارد.";break;
            case "no_data":value="اسکن کامل و تازه از لیدار دریافت نمی‌شود.";break;case "ready":value="دادهٔ لیدار آماده است.";break;
            default:value="در انتظار وضعیت تازهٔ لیدار.";}
        readiness.setText(value+"\nبرداشت نقشه پس از راه‌اندازی موتور نقشه‌سازی فعال می‌شود.");
    }
    private void change(MapDefinition value){if(busy)return;draft=value;dirty=true;message.setText("تغییرات هنوز ذخیره نشده‌اند");render();}
    @Override public void origin(double x,double y){try{change(draft.origin(x,y));}catch(RuntimeException e){error(e);}}
    @Override public void zone(MapDefinition.Zone zone){try{change(draft.zone(zone));}catch(RuntimeException e){error(e);}}
    private void persist(MapCatalog next,String success){
        if(busy||!storageReady)return;busy=true;render();
        io.execute(()->{try{store.write(next);main.post(()->{if(!alive)return;catalog=next;draft=next.selected();dirty=false;busy=false;mode=0;message.setText(success);render();});}
            catch(Exception e){main.post(()->{if(!alive)return;busy=false;message.setText("ذخیره نشد؛ دوباره تلاش کنید. تغییرات روی صفحه حفظ شده‌اند.");render();});}});
    }
    private void delete(){
        if(draft==null)return;String id=draft.id;
        new AlertDialog.Builder(this).setTitle("حذف تعریف نقشه").setMessage("«"+draft.name+"» از فهرست حذف شود؟")
                .setNegativeButton("انصراف",null).setPositiveButton("حذف",(d,w)->persist(catalog.delete(id),"تعریف نقشه حذف شد")).show();
    }
    private void discardThen(Runnable next){if(busy)return;if(!dirty){next.run();return;}
        new AlertDialog.Builder(this).setTitle("تغییرات ذخیره نشده‌اند").setMessage("از تغییرات این نقشه صرف‌نظر شود؟")
                .setNegativeButton("ادامهٔ ویرایش",null).setPositiveButton("صرف‌نظر",(d,w)->next.run()).show();}
    private void definitionDialog(MapDefinition current){
        LinearLayout form=column();form.setPadding(dp(16),dp(4),dp(16),dp(8));
        form.addView(text("نام نقشه",13,0xFFC9DBE5));EditText name=input(form,"mapping_name",current==null?"":current.name,false);name.setHint("مثلاً طبقهٔ همکف");
        EditText width=null,height=null;Spinner resolution=null;
        if(current==null){
            LinearLayout dims=row();form.addView(dims);LinearLayout a=column(),b=column();dims.addView(a,new LinearLayout.LayoutParams(0,-2,1));dims.addView(b,new LinearLayout.LayoutParams(0,-2,1));
            a.addView(text("عرض محیط (متر)",13,0xFFC9DBE5));width=input(a,"mapping_width","10",true);
            b.addView(text("طول محیط (متر)",13,0xFFC9DBE5));height=input(b,"mapping_height","10",true);
            form.addView(text("اندازهٔ هر خانهٔ شبکه",13,0xFFC9DBE5));resolution=new Spinner(this);resolution.setTag("mapping_resolution");
            resolution.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"5 cm · دقیق","10 cm · متعادل","20 cm · سبک"}));resolution.setSelection(1);form.addView(resolution);
        }
        TextView error=text("",13,0xFFFF9299);error.setTag("mapping_form_error");error.setVisibility(View.GONE);form.addView(error);ScrollView scroll=new ScrollView(this);scroll.addView(form);
        LinearLayout dialogHeader=row();dialogHeader.setPadding(dp(16),dp(6),dp(16),dp(6));
        dialogHeader.addView(text(current==null?"تعریف نقشهٔ جدید":"تغییر نام نقشه",19,0xFFE4F4FA),new LinearLayout.LayoutParams(0,-2,1));
        button(dialogHeader,"بستن کیبورد","mapping_hide_keyboard",()->hideKeyboard(name));
        AlertDialog dialog=new AlertDialog.Builder(this).setCustomTitle(dialogHeader).setView(scroll)
                .setNegativeButton("انصراف",null).setPositiveButton(current==null?"ایجاد پیش‌نویس":"اعمال",null).create();
        EditText w=width,h=height;Spinner res=resolution;
        dialog.setOnShowListener(ignored->{dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            dialog.getWindow().setLayout(Math.min(dp(620),getResources().getDisplayMetrics().widthPixels-dp(32)),Math.min(dp(370),getResources().getDisplayMetrics().heightPixels-dp(48)));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{
                MapDefinition value=current==null?MapDefinition.create(name.getText().toString(),MapDefinition.inputNumber(w.getText().toString()),MapDefinition.inputNumber(h.getText().toString()),new int[]{5,10,20}[res.getSelectedItemPosition()]):current.rename(name.getText().toString());
                catalog.save(value);hideKeyboard(name);dialog.dismiss();mode=0;change(value);
            }catch(RuntimeException e){hideKeyboard(name);error.setVisibility(View.VISIBLE);error.setText(errorMessage(e));scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));}});});dialog.show();
    }
    private EditText input(LinearLayout parent,String tag,String value,boolean number){EditText e=new EditText(this);e.setTag(tag);e.setText(value);e.setTextSize(16);e.setSingleLine(true);
        e.setInputType(number?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);e.setSelectAllOnFocus(true);
        if(number){e.setTextDirection(View.TEXT_DIRECTION_LTR);e.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);}
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setOnEditorActionListener((v,action,event)->{hideKeyboard(e);return true;});parent.addView(e,new LinearLayout.LayoutParams(-1,dp(46)));return e;}
    private void hideKeyboard(View v){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);v.clearFocus();}
    private void error(RuntimeException e){message.setText(errorMessage(e));}
    private String errorMessage(RuntimeException e){String code=e.getMessage();if(code==null)code="";
        switch(code){case "invalid_name":return "نام نقشه باید بین 1 تا 60 کاراکتر باشد.";case "duplicate_name":return "این نام قبلاً استفاده شده است.";
            case "invalid_dimensions":return "ابعاد باید عددی بین 2 تا 100 متر باشند.";case "grid_too_large":return "برای این ابعاد، خانهٔ شبکهٔ بزرگ‌تری انتخاب کنید.";
            case "origin_in_zone":return "نقطهٔ شروع نمی‌تواند داخل ناحیهٔ ممنوعه باشد.";case "too_many_maps":return "حداکثر 32 نقشه قابل نگهداری است.";
            case "too_many_zones":return "حداکثر 32 ناحیه برای هر نقشه قابل تعریف است.";case "invalid_zone":return "ناحیه باید داخل محدوده و دست‌کم 10 سانتی‌متر باشد.";
            default:return "مقدار واردشده معتبر نیست؛ نام و اعداد را بررسی کنید.";}}
    @Override protected void onResume(){super.onResume();resumed=true;IntentFilter f=new IntentFilter(MobilityContract.STATUS);f.addAction(EspSensorStatus.ACTION);
        ContextCompat.registerReceiver(this,receiver,f,MobilityContract.PERMISSION,null,ContextCompat.RECEIVER_EXPORTED);
        MobilityReceiver.start(this,new Intent(this,MobilityService.class).setAction(MobilityContract.QUERY).putExtra(MobilityContract.REPLY_PACKAGE,getPackageName()));main.post(refresh);}
    @Override protected void onPause(){resumed=false;main.removeCallbacks(refresh);unregisterReceiver(receiver);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(draft!=null)out.putString("draft",draft.json().toString());out.putBoolean("dirty",dirty);}
    @Override public void onBackPressed(){discardThen(()->super.onBackPressed());}
    @Override protected void onDestroy(){alive=false;super.onDestroy();}
}
