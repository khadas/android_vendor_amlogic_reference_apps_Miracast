/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.droidlogic.miracast;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.SharedPreferences;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.ChannelListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import android.os.UserHandle;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.FileObserver;
import android.os.FileUtils;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.Toast;
import android.media.AudioManager;
import android.media.MediaPlayer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Timer;
import java.util.TimerTask;
import java.util.StringTokenizer;
import java.util.Enumeration;
import java.net.NetworkInterface;
import java.net.InetAddress;

import com.droidlogic.app.SystemControlManager;
import com.droidlogic.miracast.WiFiDirectMainActivity;

public class SinkActivity extends Activity {
    public static final String TAG                  = "aml" + SinkActivity.class.getSimpleName();
    public static final String KEY_IP               = "ip";
    public static final String KEY_PORT             = "port";

    private String strSessionID = null;
    private String strIP = null;

    private String mIP;
    private String mPort;
    private boolean mMiracastRunning = false;
    private PowerManager.WakeLock mWakeLock;
    private MiracastThread mMiracastThread = null;
    private Handler mMiracastHandler = null;
    private boolean isHD = false;
    private SurfaceView mSurfaceView;
    protected Handler mSessionHandler;

    private View mRootView;

    private SystemControlManager mSystemControl = SystemControlManager.getInstance();

    private int certBtnState                         = 0; // 0: none oper, 1:play, 2:pause
    private boolean mEnterStandby                    = false;
    private static final int CMD_MIRACAST_FINISHVIEW = 1;
    private static final int CMD_MIRACAST_EXIT       = 2;
    private MediaPlayer mMediaPlayer;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                NetworkInfo networkInfo = (NetworkInfo) intent
                        .getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                Log.d(TAG, "P2P connection changed isConnected:" + networkInfo.isConnected() + " mMiracastRunning:" + mMiracastRunning);
                if (!networkInfo.isConnected() && mMiracastRunning) {
                    stopMiracast(true);
                    finishView();
                }
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                Log.d(TAG, "ACTION_SCREEN_OFF");
                mEnterStandby = true;
            } else if (action.equals(Intent.ACTION_SCREEN_ON)) {
                Log.e(TAG, "ACTION_SCREEN_ON");
                if (mEnterStandby) {
                    if (mMiracastRunning) {
                        stopMiracast(true);
                        finishView();
                    }
                    mEnterStandby = false;
                }
            }
        }
    };

    private void finishView() {
        Log.e(TAG, "finishView");
        Message msg = Message.obtain();
        msg.what = CMD_MIRACAST_FINISHVIEW;
        mSessionHandler.sendMessage(msg);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.sink);
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mSurfaceView = (SurfaceView) findViewById(R.id.wifiDisplaySurface);
        mRootView = (View) findViewById(R.id.rootView);
        mSurfaceView.getHolder().addCallback(new SurfaceCallback());
        mSurfaceView.getHolder().setKeepScreenOn(true);
        mSurfaceView.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        mPort = bundle.getString(KEY_PORT);
        mIP = bundle.getString(KEY_IP);
        isHD = bundle.getBoolean(WiFiDirectMainActivity.HRESOLUTION_DISPLAY);
        MiracastThread mMiracastThread = new MiracastThread();
        new Thread(mMiracastThread).start();
        synchronized (mMiracastThread) {
            while (null == mMiracastHandler) {
                try {
                    mMiracastThread.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, TAG);
        mWakeLock.acquire();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mReceiver, intentFilter);

        mSessionHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                Log.d(TAG, "handleMessage, msg.what=" + msg.what);
                switch (msg.what) {
                    case CMD_MIRACAST_FINISHVIEW:
                        Window window = getWindow();
                        WindowManager.LayoutParams wl = window.getAttributes();
                        wl.alpha = 0.0f;
                        window.setAttributes(wl);
                        Intent homeIntent = new Intent(SinkActivity.this, WiFiDirectMainActivity.class);
                        homeIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                        SinkActivity.this.startActivity(homeIntent);
                        SinkActivity.this.finish();
                        break;
                    case CMD_MIRACAST_EXIT:
                        certBtnState = 0;
                        unregisterReceiver(mReceiver);
                        stopMiracast(true);
                        mWakeLock.release();
                        quitLoop();
                        break;
                }
            }
        };
    }

    protected void onDestroy() {
        super.onDestroy();
        if (mSessionHandler != null) {
            mSessionHandler.removeCallbacksAndMessages(null);
        }
        mSessionHandler = null;
        Log.d(TAG, "Sink Activity destory");
    }

    /**
     * register the BroadcastReceiver with the intent values to be matched
     */
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "sink activity onResume");
    }

    private String getlocalip() {
        StringBuilder IFCONFIG = new StringBuilder();
        String ipAddr = "192.168.43.1";
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface
                    .getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf
                        .getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()
                            && !inetAddress.isLinkLocalAddress()
                            && inetAddress.isSiteLocalAddress()) {
                        IFCONFIG.append(inetAddress.getHostAddress().toString());
                        ipAddr = IFCONFIG.toString();
                    }
                }
            }
        } catch (Exception ex) {
        }
        return ipAddr;
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mSessionHandler != null) {
            Message msg = Message.obtain();
            msg.what = CMD_MIRACAST_EXIT;
            mSessionHandler.sendMessage(msg);
        }
        Log.d(TAG, " end sink activity onPause");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown miracast running:" + mMiracastRunning + " keyCode:" + keyCode + " event:" + event);
        if (mMiracastRunning) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    break;
                case KeyEvent.KEYCODE_BACK:
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyUp miracast running:" + mMiracastRunning + " keyCode:" + keyCode + " event:" + event);
        if (mMiracastRunning) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    break;
                case KeyEvent.KEYCODE_HOME:
                    stopMiracast(true);
                    break;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_BACK:
                    exitMiracastDialog();
                    return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
    }


    private void exitMiracastDialog() {
        new AlertDialog.Builder(this).setTitle(R.string.dlg_title)
                .setItems(new String[]{getString(R.string.disconnect_sink), getString(R.string.play_sink), getString(R.string.pause_sink)},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface arg0, int arg1) {
                                switch (arg1) {
                                    case 0:
                                        try {
                                            stopMiracast(true);
                                            finishView();
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        break;
                                    case 1:
                                        if (certBtnState == 2) {
                                            try {
                                                mMediaPlayer.start();
                                                certBtnState = 1;
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        break;
                                    case 2:
                                        if (certBtnState != 2) {
                                            try {
                                                mMediaPlayer.pause();
                                                certBtnState = 2;
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            }
                        })
                .setNegativeButton("cancel", null).show();
    }

    public void startMiracast(String ip, String port) {
        Log.d(TAG, "start miracast isRunning:" + mMiracastRunning + " IP:" + ip + ":" + port);
        mMiracastRunning = true;
        certBtnState = 1;
        Message msg = Message.obtain();
        msg.what = CMD_MIRACAST_START;
        Bundle data = msg.getData();
        data.putString(KEY_IP, ip);
        data.putString(KEY_PORT, port);
        if (mMiracastHandler != null) {
            mMiracastHandler.sendMessage(msg);
        }
    }

    /**
     * client or owner stop miracast
     * client stop miracast, only need open graphic layer
     */
    public void stopMiracast(boolean owner) {
        Log.d(TAG, "stop miracast running:" + mMiracastRunning + ",owner:" + owner);
        if (mMiracastRunning) {
            try {
                Message msg = Message.obtain();
                msg.what = CMD_MIRACAST_STOP;
                if (mMiracastHandler != null)
                    mMiracastHandler.sendMessage(msg);
                mMiracastRunning = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        mIP = null;
        mPort = null;
    }

    private final int CMD_MIRACAST_START = 10;
    private final int CMD_MIRACAST_STOP = 11;

    class MiracastThread implements Runnable {
        public void run() {
            Looper.prepare();
            Log.v(TAG, "miracast thread run");
            mMiracastHandler = new Handler() {
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case CMD_MIRACAST_START: {
                            try {
                                Bundle data = msg.getData();
                                String ip = data.getString(KEY_IP);
                                String port = data.getString(KEY_PORT);
                                String wfdUrl = "wfd://" + ip + ":" + port;
                                mMediaPlayer = new MediaPlayer();
                                mMediaPlayer.setDataSource(wfdUrl);
                                mMediaPlayer.prepareAsync();
                                mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                                    public boolean onError(MediaPlayer mp, int what, int extra) {
                                        Log.e(TAG, "Receive a error mp is" + mp + " what is " + what + " extra is " + extra);
                                        stopMiracast(true);
                                        finishView();
                                        return true;
                                    }
                                });
                                mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                    public void onPrepared(MediaPlayer mp) {
                                        mp.start();
                                        mp.setDisplay(mSurfaceView.getHolder());
                                    }
                                });
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            break;
                        }
                        case CMD_MIRACAST_STOP: {
                            mMediaPlayer.stop();
                            mMediaPlayer.release();
                            break;
                        }
                        default:
                            break;
                    }
                }
            };
            synchronized (this) {
                notifyAll();
            }
            Looper.loop();
        }
    };

    public void quitLoop() {
        if (mMiracastHandler != null && mMiracastHandler.getLooper() != null) {
            Log.v(TAG, "miracast thread quit");
            mMiracastHandler.removeCallbacksAndMessages(null);
            mMiracastHandler.getLooper().quit();
            mMiracastHandler = null;
        }
    }

    private class SurfaceCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // TODO Auto-generated method stub
            Log.v(TAG, "surfaceChanged");
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.v(TAG, "surfaceCreated");
            if (mIP == null) {
                finishView();
                return;
            }
            if (mMiracastRunning == false && mEnterStandby == false) {
                startMiracast(mIP, mPort);
                strIP = getlocalip();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // TODO Auto-generated method stub
            Log.v(TAG, "surfaceDestroyed");
            if (mMiracastRunning)
                stopMiracast(true);
        }
    }
}
