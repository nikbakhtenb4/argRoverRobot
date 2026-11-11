package com.argsecure.mobility.api;

/** Signature-protected, bounded requests. Raw GPIO and credentials never cross IPC. */
public final class MotionContract {
    private MotionContract() {}
    public static final String REQUEST="com.argsecure.rastagmobility.MOTION_REQUEST";
    public static final String STATUS="com.argsecure.rastagmobility.MOTION_STATUS";
    public static final String RESULT="com.argsecure.rastagmobility.MOTION_RESULT";
    public static int direction(String s) {
        if("forward".equals(s))return 1;if("back".equals(s))return 2;
        if("left".equals(s))return 3;if("right".equals(s))return 4;if("stop".equals(s))return 0;
        throw new IllegalArgumentException("direction");
    }
    public static String direction(int d) { return new String[]{"stop","forward","back","left","right"}[d]; }
    public static String label(String d) {
        switch(d){case "forward":return "جلو";case "back":return "عقب";case "left":return "چپ";case "right":return "راست";default:return "توقف";}
    }
    public static String reason(String code) {
        switch(code){
            case "moving":return "مسیر بررسی شد؛ فرمان حرکت فعال است";
            case "preparing_home":return "آماده‌سازی حرکت";
            case "calibration_required":return "جهت‌های حرکت هنوز کالیبره نشده‌اند";
            case "obstacle":return "توقف به‌دلیل مانع";
            case "lidar_unknown":return "دادهٔ کافی برای اطمینان از مسیر ندارم";
            case "link_lost":case "unavailable":case "transport_error":return "ارتباط کنترل حرکت در دسترس نیست";
            case "command_expired":return "زمان فرمان تمام شد";
            case "servo_calibration":return "کالیبراسیون؛ حرکت ربات غیرفعال است";
            case "remote_control_locked":return "اجازهٔ فرمان صوتی و هوش مصنوعی فعال نیست";
            case "manual_priority":return "کنترل دستی اولویت دارد";
            case "ota_maintenance":return "در حال به‌روزرسانی کنترلر";
            case "output_error":case "control_loop_stalled":return "توقف به‌دلیل خطای کنترلر";
            case "invalid_command":case "rejected":return "فرمان حرکت پذیرفته نشد";
            case "queued":return "درخواست برای کنترلر ارسال می‌شود";
            default:return "فرمان توقف";
        }
    }
}
