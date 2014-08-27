package com.straphq.glass_sdk;

import android.app.Activity;
import android.content.res.Resources;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.Sensor;
import android.os.Bundle;
import android.os.Looper;
import android.text.format.Time;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import android.util.Log;

//System data
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.provider.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Date;
import java.util.ArrayList;

import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.view.Display;
import android.graphics.Point;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class Strap {


    //members
    private SensorManager mSensorManager = null;
    private Sensor mAccelerometer = null;
    private String mStrapAppID = null;
    private Point mDisplayResolution = null;
    private static JSONArray mAccelDataList = null;

    private static Strap strapManager = null;

    //stub object for locking accelerometer data;
    private Object lock = new Object();

    //constants
    private int kMaxAccelLength = 100;
    private int kAccelerometerFrequencyInMS = 200;

    private int kSystemDataFrequencyInMS = 1000 * 60 * 5;

    private static final String kLogEventType = "logEvent";
    private static final String kAcclType = "logAccl";
    private static final String kDiagnosticType = "logDiagnostic";
    private static final String kUserAgent = "GLASS/1.0";
    private static final float kConversionFactor = 101.971621298f;
    private String strapURL = "https://api.straphq.com/create/visit/with/";
    private float[] lastAccelData;

    Calendar mCalendar = new GregorianCalendar();
    TimeZone mTimeZone = mCalendar.getTimeZone();
    int mGMTOffset = mTimeZone.getRawOffset();
    long tz_offset = TimeUnit.HOURS.convert(mGMTOffset, TimeUnit.MILLISECONDS);


    //TODO finish singleton implementation
    /*public static Strap getInstance() {
        return strapManager;
    }*/

    /**
     * Starts StrapMetrics on the device
     * @param applicationContext The context of your application.
     * @param strapAppID The Strap application ID to be used
     */
    public Strap(Context applicationContext, String strapAppID) {

        //Singleton reference TODO
        strapManager = this;

        //Initialize members
        mSensorManager = (SensorManager) applicationContext.getSystemService(applicationContext.SENSOR_SERVICE);
        mStrapAppID = strapAppID;
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        if(mAccelDataList == null) {
            mAccelDataList = new JSONArray();
        }

        //Grab screen data
        WindowManager windowManager = (WindowManager) applicationContext.getSystemService(applicationContext.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        mDisplayResolution = new Point();
        display.getSize(mDisplayResolution);

        //Setup accelerometer pinging.
        mSensorManager.registerListener(new SensorEventListener() {
            @Override
            public void  onSensorChanged(SensorEvent sensorEvent) {
                setLastAccelData(sensorEvent.values);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) {

            }
        }, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);

        Timer accelTimer = new Timer();
        Timer systemTimer = new Timer();
        RecordAccelerometerTask recordTask = new RecordAccelerometerTask();
        SystemInfoTask systemTask = new SystemInfoTask();
        systemTask.setApplicationContext(applicationContext);

        accelTimer.scheduleAtFixedRate(recordTask, new Date(), kAccelerometerFrequencyInMS);
        systemTimer.scheduleAtFixedRate(systemTask, new Date(), kSystemDataFrequencyInMS);
    }


    private String getBaseQuery() {

        String resolution = mDisplayResolution.x + "x" + mDisplayResolution.y;

        String serial = "";
        Class<?> c = null;
        try {
            c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            serial = (String) get.invoke(c, "ro.serialno");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }


        return "app_id=" + mStrapAppID
                + "&resolution=" + resolution
                + "&useragent=" + kUserAgent
                + "&visitor_id=" + serial
                + "&visitor_timeoffset=" + tz_offset;
    }



    /**
     * Logs the specified event with Strap Metrics.
     * <p>
     * This method always returns immediately, whether or not the
     * message was immediately sent to the phone.
     *
     * @param  eventName  The name of the Strap event being logged.
     */
    public void logEvent(String eventName) {


        //create a new data map entry for this event and load it with data
        JSONObject mapToBuild = new JSONObject();



        String query = getBaseQuery();

        query = query +
                "&action_url=" + eventName;

        try {
            Runnable r = new PostLog(strapURL,query);
            new Thread(r).start();
        } catch (Exception e) {
            Log.e("POST_ERROR","ERROR with PostLog Thread: " + e.toString());
            e.printStackTrace();
        }
    }

    private void logSystemData(int battery, int brightness, long time) throws JSONException, IOException {

        String query = getBaseQuery();

        JSONObject diagnostics = new JSONObject();


        diagnostics.put("brightness", brightness);
        diagnostics.put("battery", battery);
        diagnostics.put("time", time);


        query = query
                + "&action_url=" + "STRAP_DIAG"
                + "&cvar=" + URLEncoder.encode(diagnostics.toString(), "UTF-8");

        try {
            Runnable r = new PostLog(strapURL,query);
            new Thread(r).start();
        } catch (Exception e) {
            Log.e("POST_ERROR","ERROR with PostLog Thread: " + e.toString());
            e.printStackTrace();
        }

    }

    private void logAccelData() throws IOException {
        String query = getBaseQuery();

        query = query
                + "&action_url=" + "STRAP_API_ACCL"
                + "&accl=" + URLEncoder.encode(mAccelDataList.toString(),"UTF-8")
                + "&act=UNKNOWN";

        try {
            Runnable r = new PostLog(strapURL,query);
            new Thread(r).start();
        } catch (Exception e) {
            Log.e("POST_ERROR","ERROR with PostLog Thread: " + e.toString());
            e.printStackTrace();
        }

        mAccelDataList = new JSONArray();
    }

    //Setter for
    public void  setLastAccelData(float[] accelData) {
        synchronized(lock) {
            lastAccelData = accelData;
        }
    }

    public float[] getLastAccelData() {
        synchronized(lock) {
            return lastAccelData;
        }
    }

    //Basic reading of the accelerometer. Just updates the member variables to the new values.
    //If collecting a trend, something like a list/vector could be used to get deltas.


    private void addAccelData(float [] coords, long ts) throws JSONException {
        // append the values in tmp to the result JSONArray

        JSONObject currentData = new JSONObject();
        float x = coords[0] * kConversionFactor;
        float y = coords[1] * kConversionFactor;
        float z = coords[2] * kConversionFactor;

        currentData.put("x", x);
        currentData.put("y", y);
        currentData.put("z", z);
        currentData.put("ts", ts);

        mAccelDataList.put(currentData);

    }


    //Small task implementation for periodically recording accel data.
    class RecordAccelerometerTask extends TimerTask {
        public void run() {

            long time = System.currentTimeMillis();
            float[] lastAccelData = getLastAccelData();

            if(lastAccelData != null) {

                lastAccelData = lastAccelData.clone();

                try {

                    addAccelData(lastAccelData, System.currentTimeMillis());
                } catch (Exception e) {
                    Log.e("addAccelData", e.getMessage());
                }

                if(mAccelDataList.length() >= kMaxAccelLength) {

                    try {
                        logAccelData();
                    } catch(Exception e) {
                        Log.e("REC_ACCEL", e.getMessage());
                    }
                }

            }

        }
    }

    class SystemInfoTask extends TimerTask {
        Context context = null;

        public void setApplicationContext(Context applicationContext) {
            context = applicationContext;
        }

        public void run() {


            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int brightness = -1;


            try {

                brightness = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            } catch (Settings.SettingNotFoundException e) {
                Log.d("SettingsNotFound",e.getMessage());
            }

            try {

                logSystemData(level, brightness, System.currentTimeMillis());
            } catch (JSONException e) {
                Log.e("logSystemData", "JSON exception thrown");
            } catch (IOException e) {
                Log.e("logSystemData", "IO exception thrown");
            }



        }
    }
}
