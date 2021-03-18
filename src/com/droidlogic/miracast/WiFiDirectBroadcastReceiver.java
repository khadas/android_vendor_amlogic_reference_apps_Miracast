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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.net.wifi.p2p.WifiP2pManager.GroupInfoListener;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.net.InetAddress;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * A BroadcastReceiver that notifies of important wifi p2p events.
 */
public class WiFiDirectBroadcastReceiver extends BroadcastReceiver
{
    private String mWfdMac;
    private String mWfdPort;
    private String mPeerValidAddress;
    private boolean mWfdIsConnected = false;
    private boolean mSinkIsConnected = false;
    private WifiP2pManager manager;
    private Channel channel;
    private WiFiDirectMainActivity activity;
    private final String DEFAULT_PORT = "7236";
    private boolean mPeerValid = false;

    Object lock = new Object();
    /**
     * @param manager WifiP2pManager system service
     * @param channel Wifi p2p channel
     * @param activity activity associated with the receiver
     */
    public WiFiDirectBroadcastReceiver (WifiP2pManager manager, Channel channel,
                                        WiFiDirectMainActivity activity)
    {
        super();
        this.manager = manager;
        this.channel = channel;
        this.activity = activity;
    }

    /*
     * (non-Javadoc)
     * @see android.content.BroadcastReceiver#onReceive(android.content.Context,
     * android.content.Intent)
     */
    @Override
    public void onReceive(Context context, Intent intent)
    {
        String action = intent.getAction();
        WifiP2pWfdInfo wfdInfo = null;
        if (WiFiDirectMainActivity.DEBUG)
        {
            Log.d (WiFiDirectMainActivity.TAG, "onReceive action:" + action);
        }
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals (action) )
        {
            // UI update to indicate wifi p2p status.
            int state = intent.getIntExtra (WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            {
                // Wifi Direct mode is enabled
                activity.setIsWifiP2pEnabled (true);
            }
            else
            {
                activity.setIsWifiP2pEnabled (false);
                activity.resetData();

            }
            if (WiFiDirectMainActivity.DEBUG)
            {
                Log.d (WiFiDirectMainActivity.TAG, "P2P state changed - " + state);
            }
        }
        else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals (action) )
        {
            // request available peers from the wifi p2p manager. This is an
            // asynchronous call and the calling activity is notified with a
            // callback on PeerListListener.onPeersAvailable()
            if (manager != null && !activity.mForceStopScan && !activity.mStartConnecting) {
                Log.d(WiFiDirectMainActivity.TAG, "requestPeers!!!!");
                manager.requestPeers (channel, (PeerListListener) activity);
            }
            if (WiFiDirectMainActivity.DEBUG)
            {
                Log.d (WiFiDirectMainActivity.TAG, "P2P peers changed");
            }
        }
        else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals (action) )
        {
            if (manager == null)
            {
                return;
            }
            NetworkInfo networkInfo = (NetworkInfo) intent
                                      .getParcelableExtra (WifiP2pManager.EXTRA_NETWORK_INFO);
            WifiP2pInfo p2pInfo = (WifiP2pInfo) intent.getParcelableExtra (WifiP2pManager.EXTRA_WIFI_P2P_INFO);
            WifiP2pGroup p2pGroup = (WifiP2pGroup) intent.getParcelableExtra (WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
            if (WiFiDirectMainActivity.DEBUG)
            {
                Log.d (WiFiDirectMainActivity.TAG, "===================");
                Log.d (WiFiDirectMainActivity.TAG, "Received WIFI_P2P_CONNECTION_CHANGED_ACTION, isConnected:" + networkInfo.isConnected() );
                Log.d (WiFiDirectMainActivity.TAG, "networkInfo=" + networkInfo);
                Log.d (WiFiDirectMainActivity.TAG, "p2pInfo=" + p2pInfo);
                Log.d (WiFiDirectMainActivity.TAG, "p2pGroup=" + p2pGroup);
                Log.d (WiFiDirectMainActivity.TAG, "===================");
            }
            if (networkInfo.isConnected())
            {
                mWfdIsConnected = true;
                if (p2pGroup.isGroupOwner() == true)
                {
                    if (mPeerValid)
                        return;
                    Log.d (WiFiDirectMainActivity.TAG, "I am GO");
                    WifiP2pDevice device = null;
                    for (WifiP2pDevice c : p2pGroup.getClientList()) {
                        device = c;
                        break;
                    }
                    if (device != null)
                    {
                        wfdInfo = device.getWfdInfo();
                        if (wfdInfo != null) {
                            mWfdPort = String.valueOf(wfdInfo.getControlPort());
                            mWfdMac = device.deviceAddress;
                        }
                        Thread subThead = new Thread() {
                            public void run() {
                                try {
                                    String INETADDRESS_CLASS_NAME = "java.net.InetAddress";
                                    Class inetAddressClass = Class.forName(INETADDRESS_CLASS_NAME);
                                    Method meths[] = inetAddressClass.getMethods();
                                    Method isReachableByICMP = null;
                                    for (int i = 0; i < meths.length; i++) {
                                        if (meths[i].getName().endsWith("isReachableByICMP")) {
                                            isReachableByICMP = meths[i];
                                            break;
                                        }
                                    }
                                    String goHost = p2pInfo.groupOwnerAddress.getHostAddress();
                                    StringBuilder hostBuilder = new StringBuilder(goHost.substring(0, goHost.lastIndexOf(".") + 1));
                                    int owner = Integer.parseInt(goHost.substring(goHost.lastIndexOf(".") + 1));
                                    for (int i = 80; i <= 150; i++) {
                                        if (i == owner)
                                            continue;
                                        mPeerValidAddress = hostBuilder.replace(goHost.lastIndexOf(".") + 1, hostBuilder.length(), String.valueOf(i)).toString();
                                        mPeerValid = Boolean.parseBoolean(isReachableByICMP.invoke(InetAddress.getByName(mPeerValidAddress), 200).toString());
                                        if (mPeerValid)
                                            break;
                                    }
                                    if (mPeerValid == false) {
                                        for (int i = 151; i <= 254; i++) {
                                            if (i == owner)
                                                continue;
                                            mPeerValidAddress = hostBuilder.replace(goHost.lastIndexOf(".") + 1, hostBuilder.length(), String.valueOf(i)).toString();
                                            mPeerValid = Boolean.parseBoolean(isReachableByICMP.invoke(InetAddress.getByName(mPeerValidAddress), 100).toString());
                                            if (mPeerValid)
                                                break;
                                        }
                                    }
                                    if (mPeerValid == false) {
                                        for (int i = 1; i <= 79; i++) {
                                            if (i == owner)
                                                continue;
                                            mPeerValidAddress = hostBuilder.replace(goHost.lastIndexOf(".") + 1, hostBuilder.length(), String.valueOf(i)).toString();
                                            mPeerValid = Boolean.parseBoolean(isReachableByICMP.invoke(InetAddress.getByName(mPeerValidAddress), 100).toString());
                                            if (mPeerValid)
                                                break;
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        };
                        subThead.start();
                        try {
                            subThead.join();
                            if (mPeerValid) {
                                Log.d(WiFiDirectMainActivity.TAG, "mWfdPort:" + mWfdPort + " host is " + mPeerValidAddress);
                                activity.startMiracast(mPeerValidAddress, mWfdPort);
                            } else {
                                Log.d(WiFiDirectMainActivity.TAG, "not found peer information");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                else
                {
                    Log.d (WiFiDirectMainActivity.TAG, "I am GC");
                    WifiP2pDevice device = p2pGroup.getOwner();
                    if (device != null) {
                        wfdInfo = device.getWfdInfo();
                        if (wfdInfo != null) {
                            mWfdPort = String.valueOf(wfdInfo.getControlPort());
                            Log.d(WiFiDirectMainActivity.TAG, "mWfdPort:" + mWfdPort);
                            if (mWfdPort.equals(DEFAULT_PORT))
                                activity.startMiracast(p2pInfo.groupOwnerAddress.getHostAddress(), mWfdPort);
                            else {
                                Log.d(WiFiDirectMainActivity.TAG, "use default port");
                                activity.startMiracast(p2pInfo.groupOwnerAddress.getHostAddress(), DEFAULT_PORT);
                            }
                        } else {
                            Log.d(WiFiDirectMainActivity.TAG, "wfdInfo is null");
                        }
                    } else {
                        Log.d(WiFiDirectMainActivity.TAG, "device is null");
                    }
                }
                mSinkIsConnected = false;
            }
            else
            {
                mPeerValid = false;
                mWfdIsConnected = false;
                // It's a disconnect
                activity.resetData();
                //activity.stopMiracast(false);
                //start a search when we are disconnected
                Log.d (WiFiDirectMainActivity.TAG, "ForceStopScan=" + activity.mForceStopScan);
                if (!activity.mForceStopScan)
                    activity.startSearch();
            }
        }
        else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals (action) )
        {

            if (WiFiDirectMainActivity.DEBUG)
            {
                Log.d (WiFiDirectMainActivity.TAG, "WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
            }
            activity.resetData();
            activity.setDevice( (WifiP2pDevice) intent.getParcelableExtra (
                                     WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) );
        }
        else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals (action) )
        {
            int discoveryState = intent.getIntExtra (WifiP2pManager.EXTRA_DISCOVERY_STATE,
                                 WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
            if ( activity != null && discoveryState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED)
            {
                activity.discoveryStop();
                if (!activity.mForceStopScan && !activity.mStartConnecting)
                    activity.startSearchTimer();
            }
            if (WiFiDirectMainActivity.DEBUG)
            {
                Log.d (WiFiDirectMainActivity.TAG, "Discovery state changed: " + discoveryState + " ->1:stop, 2:start");
            }
        }
        else if (WiFiDirectMainActivity.WIFI_P2P_IP_ADDR_CHANGED_ACTION.equals (action) )
        {
            String ipaddr = intent.getStringExtra(WiFiDirectMainActivity.WIFI_P2P_PEER_IP_EXTRA);
            String macaddr = intent.getStringExtra(WiFiDirectMainActivity.WIFI_P2P_PEER_MAC_EXTRA);;
            Log.d (WiFiDirectMainActivity.TAG, "ipaddr is " + ipaddr + "  macaddr is " + macaddr);
            if (ipaddr != null && macaddr != null) {
                if (mWfdIsConnected) {
                    if (!mSinkIsConnected) {
                        if ((mWfdMac.substring(0, 11)).equals(macaddr.substring(0, 11))) {
                            Log.d (WiFiDirectMainActivity.TAG, "wfdMac:" + mWfdMac + ", macaddr:" + macaddr + " is mate!!");
                            activity.startMiracast(ipaddr, mWfdPort);
                            mSinkIsConnected = true;
                        } else {
                            Log.d (WiFiDirectMainActivity.TAG, "wfdMac:" + mWfdMac + ", macaddr:" + macaddr + " is unmate!!");
                        }
                    }
                }
            }
        }
    }
}
