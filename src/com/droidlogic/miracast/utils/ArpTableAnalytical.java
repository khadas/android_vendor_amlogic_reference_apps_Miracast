package com.droidlogic.miracast.utils;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import android.util.Log;

public class ArpTableAnalytical {
    private static final String TAG = "WifiDirectArpTable";
    private static final String ARP_TABLE_PATH = "/proc/net/arp";
    private static final String INVALID_MAC = "00:00:00:00:00:00";
    private static boolean mIsRunning = false;

    //Not read when re-entered 60 times, each delay of 100 milliseconds more than that is the timeout.
    private static final long ARP_GET_TIMEOUT = 60;
    public static final long ARP_GET_INTERVAL  =100;

    public static void getMatchedIp(String macAddress, String deviceType, ArpTableMatchListener l) {
        if (!mIsRunning) {
            mIsRunning = true;
            ArpTableAnalytical arpTableAnalytical = new ArpTableAnalytical();
            arpTableAnalytical.matchingIp(macAddress, deviceType, l);
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
        int retryCount = 0;  // Number of current attempts

        while (retryCount < ARP_GET_TIMEOUT && !isMatched) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(ARP_TABLE_PATH));
                String line = reader.readLine();
                Log.d(TAG, "!!!! reader; line=" + line);

                while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "enter reader; line=" + line);
                        String[] tokens = line.split("[ ]+");
                        String ip = tokens[0];
                        String mac = tokens[3];
                        String device = tokens[5];
                        Log.d(TAG, "enter reader ip= " + tokens[0] + " mac = " + tokens[3]);
                        if (tokens.length >= 6 && !INVALID_MAC.equals(mac) &&  deviceType.equals(device)) {
                            Log.d(TAG, "enter macAddress.equals");
                            l.onMatched(ip);
                            isMatched = true;
                            break;
                        }  else {
                            Log.d(TAG, "Current row does not exist");
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
                        try {
                            Thread.sleep(ARP_GET_INTERVAL);
                            retryCount ++;
                        } catch (InterruptedException e) {
                            //...
                        }
                    }
                } catch (IOException e) {
                    // Do nothing
                }
            }

            if (!isMatched) {
                l.onNotMatched();
            }
        }
    }


    public interface ArpTableMatchListener {
        void onMatched(String peerIp);
        void onNotMatched();
    }
}
