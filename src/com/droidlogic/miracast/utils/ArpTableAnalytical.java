package com.droidlogic.miracast.utils;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import android.util.Log;

public class ArpTableAnalytical {
    private static final String TAG = "ArpTable";
    private static final String ARP_TABLE_PATH = "/proc/net/arp";
    private static final String INVALID_MAC = "00:00:00:00:00:00";
    private static boolean mIsRunning = false;

    private static final long ARP_GET_TIMEOUT = 15_000L;
    public static final long ARP_GET_INTERVAL  =200;
    private long mElapsedTime = 0L;

    public static void getMatchedIp(String macAddress, String deviceType, ArpTableMatchListener l) {
        if (!mIsRunning) {
            mIsRunning = true;
            new ArpTableAnalytical().matchingIp(macAddress, deviceType, l);
        } else {
            Log.w(TAG, "getMatchedIp is running, abort");
        }
    }

    public void matchingIp(final String macAddress, String deviceType, final ArpTableMatchListener l) {
        if (macAddress == null || macAddress.isEmpty() ||
                deviceType == null || deviceType.isEmpty() ||
                l == null) {
            mIsRunning = false;
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                doMatchUnchecked(macAddress, deviceType, l);
                mIsRunning = false;
            }
        }).start();
    }

    private void doMatchUnchecked(String macAddress, String deviceType, ArpTableMatchListener l) {
        boolean isMatched = false;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(ARP_TABLE_PATH));
            String line = reader.readLine();
            Log.d(TAG, "!!!! reader; line=" + line);

            while (true) {
                line = reader.readLine();
                if (line != null) {
                    Log.d(TAG, "enter reader; line=" + line);
                    String[] tokens = line.split("[ ]+");
                    if (tokens.length < 6) {
                        continue;
                    }

                    String ip = tokens[0];
                    String mac = tokens[3];
                    String device = tokens[5];
                    Log.d(TAG, "enter reader ip= " + tokens[0] + " mac = " + tokens[3]);
                    if (!INVALID_MAC.equals(mac) && deviceType.equals(device)) {
                        Log.d(TAG, "enter macAddress.equals");
                        l.onMatched(ip);
                        isMatched = true;
                        break;
                    }
                } else if (mElapsedTime < ARP_GET_TIMEOUT){
                    Log.e(TAG, "Waiting for arp updating");
                    try {
                        Thread.sleep(ARP_GET_INTERVAL);
                        mElapsedTime += ARP_GET_INTERVAL;
                    } catch (InterruptedException e) {
                        //...
                    }
                } else {
                    break;
                }
            }

            Log.e(TAG, "out read!!!!!!");
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Could not open /proc/net/arp to lookup mac address");
        } catch (IOException e) {
            Log.e(TAG, "Could not read /proc/net/arp to lookup mac address");
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            }
            catch (IOException e) {
                // Do nothing
            }
        }

        if (!isMatched) {
            l.onNotMatched();
        }
    }


    public interface ArpTableMatchListener {
        void onMatched(String peerIp);
        void onNotMatched();
    }
}
