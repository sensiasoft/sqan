package org.sofwerx.sqan.manet.wifiaware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.IdentityChangedListener;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.issues.WiFiInUseIssue;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.TransportPreference;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 *
 * FIXME WiFiAwareManager is showing as Not Available for an unknown reason
 *
 *  TODO add support for Out of Band (OOB) discovery
 */
public class WiFiAwareManet extends AbstractManet {
    private static final String SERVICE_ID = "sqan";
    private static final long TIME_TO_CONSIDER_STALE_DEVICE = 1000l * 60l * 5l;
    private WifiAwareManager wifiAwareManager;
    private BroadcastReceiver hardwareStatusReceiver;
    private final AttachCallback attachCallback;
    private final IdentityChangedListener identityChangedListener;
    private WifiAwareSession awareSession;
    private final PublishConfig configPub;
    private final SubscribeConfig configSub;
    private static final long INTERVAL_LISTEN_BEFORE_PUBLISH = 1000l * 30l; //amount of time to listen for an existing hub before assuming the hub role
    private Role role = Role.NONE;
    private DiscoverySession discoverySession;
    private ArrayList<Connection> connections = new ArrayList<>();

    private enum Role {HUB, SPOKE, NONE}

    public WiFiAwareManet(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiAwareManager = null;
            hardwareStatusReceiver = null;
            identityChangedListener = new IdentityChangedListener() {
                @Override
                public void onIdentityChanged(byte[] mac) {
                    onMacChanged(mac);
                }
            };
            discoverySession = null;
            configPub = new PublishConfig.Builder()
                    .setServiceName(SERVICE_ID)
                    .build();
            configSub = new SubscribeConfig.Builder()
                    .setServiceName(SERVICE_ID)
                    .build();
            attachCallback = new AttachCallback() {
                @Override
                public void onAttached(final WifiAwareSession session) {
                    if (handler != null) {
                        handler.post(() -> {
                            Log.d(Config.TAG, "onAttached(session)");
                            awareSession = session;
                            findOrCreateHub();
                        });
                    }
                    isRunning.set(true);
                }

                @Override
                public void onAttachFailed() {
                    if (handler != null) {
                        handler.post(() -> {
                            Log.e(Config.TAG, "unable to attach to WiFiAware manager");
                            setStatus(Status.ERROR);
                        });
                    }
                    wifiAwareManager = null;
                    isRunning.set(false);
                }
            };
        } else {
            attachCallback = null;
            identityChangedListener = null;
            configPub = null;
            configSub = null;
        }
    }

    @Override
    public ManetType getType() { return ManetType.WIFI_AWARE; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            SqAnService.onIssueDetected(new WiFiIssue(true,"This device does not have WiFi Aware"));
            passed = false;
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WifiAwareManager mngr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
                if (!mngr.isAvailable()) {
                    SqAnService.onIssueDetected(new WiFiIssue(true, "WiFi Aware is supported but the system is not making it available"));
                    passed = false;
                }
            }
        }
        if (NetUtil.isWiFiConnected(context))
            SqAnService.onIssueDetected(new WiFiInUseIssue(false,"WiFi is connected to another network"));
        return passed;
    }

    @Override
    public int getMaximumPacketSize() {
        return 64000; //TODO temp maximum
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public String getName() { return "WiFi Aware™"; }

    @Override
    public void init() throws ManetException {
        //if (!isRunning) {
            isRunning.set(true);
            if (hardwareStatusReceiver == null) {
                hardwareStatusReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (handler != null)
                            handler.post(() -> onWiFiAwareStatusChanged());
                    }
                };
                IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
                context.registerReceiver(hardwareStatusReceiver, filter);
            }
            if (wifiAwareManager == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NetUtil.turnOnWiFiIfNeeded(context);
                    NetUtil.forceLeaveWiFiNetworks(context); //TODO include a check to protect an active connection if its used for data backhaul
                    wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
                    if ((wifiAwareManager != null) && wifiAwareManager.isAvailable())
                        wifiAwareManager.attach(attachCallback, identityChangedListener, handler);
                    else {
                        Log.e(Config.TAG, "WiFi Aware Manager is not available");
                        setStatus(Status.ERROR);
                    }
                }
            }
        //}
    }

    @Override
    protected boolean isBluetoothBased() { return false; }

    @Override
    protected boolean isWiFiBased() { return true; }

    private Connection findPeer(PeerHandle peerHandle) {
        if ((peerHandle != null) && (connections != null) && !connections.isEmpty()) {
            int id = peerHandle.hashCode();
            synchronized (connections) {
                for (Connection connection : connections) {
                    if ((connection != null) && (connection.getPeerHandle() != null) && (connection.getPeerHandle().hashCode() == id)) {
                        Log.d(Config.TAG,"findPeer found Aware "+id);
                        return connection;
                    }
                }
            }
        }
        return null;
    }

    private Connection findConnectionWithTransientID(final int id) {
        if ((connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                for (Connection connection : connections) {
                    if ((connection != null) && (connection.getDevice()!= null) && (connection.getDevice().getTransientAwareId() == id)) {
                        Log.d(Config.TAG,"findConnectionWithTransientID() found a match for Aware ID "+id+" in "+connection.getDevice().getLabel());
                        return connection;
                    }
                }
            }
        }
        return null;
    }

    private void updatePeer(PeerHandle peerHandle) {
        if (peerHandle == null)
            return;
        Connection old = findPeer(peerHandle);
        if (old == null)
            old = findConnectionWithTransientID(peerHandle.hashCode());
        if (old == null) {
            if (connections == null)
                connections = new ArrayList<>();
            synchronized (connections) {
                int netId = peerHandle.hashCode();
                SqAnDevice device = SqAnDevice.findByTransientAwareID(netId);
                if (device == null) {
                    device = new SqAnDevice();
                    Log.d(Config.TAG, "WiFi Aware ID " + netId + " does not match an existing device, creating a new device");
                } else
                    Log.d(Config.TAG, "WiFi Aware ID " + netId + " matches existing device: " + device.getLabel());
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "New WiFi Aware connection (" + peerHandle.hashCode() + ") for " + device.getLabel());
                device.setConnected(0, false, true);
                old = new Connection(peerHandle, device);
                old.setLastConnection();
                connections.add(old);
            }
            SqAnService.burstVia(new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM),TransportPreference.WIFI);
        } else {
            Log.d(Config.TAG,"updatePeer() found existing connection to Aware "+peerHandle.hashCode());
            old.setLastConnection();
        }
    }

    private void startAdvertising() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware - startAdvertising()");
            if (discoverySession != null) {
                discoverySession.close();
                discoverySession = null;
                setStatus(Status.CHANGING_MEMBERSHIP);
            }
            if (awareSession != null) {
                setStatus(Status.ADVERTISING);
                awareSession.publish(configPub, new DiscoverySessionCallback() {
                    @Override
                    public void onPublishStarted(PublishDiscoverySession session) {
                        Log.d(Config.TAG, "onPublishStarted()");
                        discoverySession = session;
                        role = Role.HUB;
                        setStatus(Status.CONNECTED);
                        if (listener != null)
                            listener.onStatus(status);
                    }

                    @Override
                    public void onMessageReceived(final PeerHandle peerHandle, final byte[] message) {
                        if (role == Role.NONE) {
                            role = Role.SPOKE;
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onMessageReceived() - changing role to SPOKE");
                        } else
                            Log.d(Config.TAG, "onMessageReceived()");
                        if (handler != null)
                            handler.post(() -> {
                                updatePeer(peerHandle);
                                handleMessage(findConnection(peerHandle),message);
                            });
                    }
                }, handler);
            }
        }
    }

    private Connection findConnection(PeerHandle peerHandle) {
        if ((peerHandle != null) && (connections != null) && !connections.isEmpty()) {
            int handleToFind = peerHandle.hashCode();
            synchronized (connections) {
                for (Connection connection:connections) {
                    if ((connection != null) && (connection.getPeerHandle() != null) && (connection.getPeerHandle().hashCode() == handleToFind))
                        return connection;
                }
            }
        }
        return null;
    }

    private void handleMessage(Connection connection,byte[] message) {
        if (message == null) {
            CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Aware received an empty message");
            return;
        }
        if (connection == null) {
            CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Aware received a message from a null connection; this should never happen");
            return;
        }

        connection.setLastConnection();
        AbstractPacket packet = AbstractPacket.newFromBytes(message);
        if (packet == null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "WiFi Aware message from "+((connection.getDevice()==null)?"unknown device":connection.getDevice().getLabel())+" could not be parsed");
            return;
        }
        SqAnDevice device;
        if (packet.isDirectFromOrigin())
            device = SqAnDevice.findByUUID(packet.getOrigin());
        else
            device = connection.getDevice();
        if (device == null)
            CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Aware received a message from an unknown device");
        if (device != null) {
            Log.d(Config.TAG,"WiFi Aware received a message from "+device.getLabel()+" (Aware "+((connection.getPeerHandle()==null)?"unk peer handle":connection.getPeerHandle().hashCode())+")");
            device.setHopsAway(packet.getCurrentHopCount(), false, true);
            device.setLastConnect();
            if (packet.isDirectFromOrigin())
                connection.setDevice(device);
        }
        relayPacketIfNeeded(connection,message,packet.getSqAnDestination(),packet.getOrigin(),packet.getCurrentHopCount());
        super.onReceived(packet);
    }

    private void relayPacketIfNeeded(Connection originConnection, final byte[] data, final int destination, final int origin, final int hopCount) {
        if ((originConnection != null) && (data != null) && (connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                SqAnDevice device;
                AbstractPacket reconstructed = null;
                for (Connection connection : connections) {
                    if ((connection == null) || (originConnection == connection) || (connection.getDevice() == null))
                        continue;
                    device = connection.getDevice();
                    if ((device.getUUID() != origin) //dont send to ourselves
                        && AddressUtil.isApplicableAddress(device.getUUID(),destination)
                        && hopCount < device.getHopsToDevice(origin)) {
                        CommsLog.log(CommsLog.Entry.Category.COMMS,"WiFi Aware relaying packet from "+origin+" ("+hopCount+" hops) to "+device.getLabel());

                        if (device.isWiFiPreferred())
                            burst(data,connection.getPeerHandle());
                        if (device.isBtPreferred()) {
                            CommsLog.log(CommsLog.Entry.Category.COMMS,"WiFi Aware referring packet from "+origin+" ("+hopCount+" hops) to "+device.getLabel()+" for relay via bluetooth");
                            if (reconstructed == null)
                                reconstructed = AbstractPacket.newFromBytes(data);
                            SqAnService.burstVia(reconstructed, TransportPreference.BLUETOOTH);
                        }
                    }
                }
            }
        } else
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"WiFi Aware cannot relay a null packet or handle a null connection origin");
    }

    private void startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware discovery started");
            if (discoverySession != null) {
                Log.d(Config.TAG,"Stopping previous Aware discoverySession to start new discoverySession");
                discoverySession.close();
                discoverySession = null;
                setStatus(Status.CHANGING_MEMBERSHIP);
            }
            if (awareSession != null) {
                role = Role.NONE;
                setStatus(Status.DISCOVERING);
                awareSession.subscribe(configSub, new DiscoverySessionCallback() {
                    @Override
                    public void onSubscribeStarted(final SubscribeDiscoverySession session) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onSubscribeStarted()");
                        discoverySession = session;
                        setStatus(Status.CONNECTED);
                    }

                    @Override
                    public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onServiceDiscovered()");
                        setStatus(Status.CONNECTED);
                        if (role == Role.NONE)
                            role = Role.SPOKE;
                        if (handler != null)
                            handler.post(() -> updatePeer(peerHandle));
                        if (listener != null)
                            listener.onStatus(status);
                    }
                }, handler);
            }
        }
    }

    //FIXME hey dummy! once its connected, use the https://developer.android.com/guide/topics/connectivity/wifi-aware#create_a_connection section rather than the message

    //FIXME use the identity change listener to get the Aware MAC and know when it changes
    //FIXME https://android.googlesource.com/platform/frameworks/base/+/master/wifi/java/android/net/wifi/aware/IdentityChangedListener.java


    private void burst(AbstractPacket packet, PeerHandle peerHandle) {
        if (packet == null) {
            Log.d(Config.TAG,"Aware Cannot send empty packet");
            return;
        }
        burst(packet.toByteArray(),peerHandle);
    }

    private void burst(final byte[] bytes, final PeerHandle peerHandle) {
        if (bytes == null) {
            Log.d(Config.TAG,"Aware cannot send empty byte array");
            return;
        }
        if (peerHandle == null) {
            Log.d(Config.TAG,"Aware cannot send packet to an empty PeerHandle");
            return;
        }
        if (discoverySession == null) {
            Log.d(Config.TAG,"Aware cannot send packet as no discoverySession exists");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (bytes.length > getMaximumPacketSize()) {
                Log.d(Config.TAG, "Packet larger than WiFi Aware max; segmenting and sending");
                //TODO segment and burst
            } else {
                Log.d(Config.TAG,"WiFi Aware queuing packet to send to "+peerHandle.hashCode());
                handler.post(() -> discoverySession.sendMessage(peerHandle, 0, bytes));
            }
        } else
            Log.d(Config.TAG,"Cannot burst, WiFi Aware is not supported");
    }

    @Override
    public void burst(final AbstractPacket packet) throws ManetException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (handler != null) {
                handler.post(() -> {
                    if ((connections != null) && !connections.isEmpty()) {
                        synchronized (connections) {
                            Log.d(Config.TAG, "Aware burst(packet)");
                            for (Connection connection:connections) {
                                if ((connection.getDevice() != null) && (connection.getPeerHandle() != null)) {
                                    if (AddressUtil.isApplicableAddress(connection.getDevice().getUUID(), packet.getSqAnDestination()))
                                        burst(packet, connection.getPeerHandle());
                                }
                            }
                        }
                    } else
                        Log.d(Config.TAG, "Aware tried to burst but no nodes available to receive");
                });
            }
        }
    }

    @Override
    public void connect() throws ManetException {
        //TODO
    }

    @Override
    public void pause() throws ManetException {
        //TODO
    }

    @Override
    public void resume() throws ManetException {
        //TODO
    }

    /**
     * Looks for an existing hub on the network, if one isn't found, then assume that role
     */
    private void findOrCreateHub() {
        Log.d(Config.TAG,"findOrCreateHub()");
        if (awareSession != null) {
            startDiscovery();
            if (handler != null) {
                handler.postDelayed(() -> {
                    if (role == Role.NONE) {
                        Log.d(Config.TAG,"no existing network found");
                        setStatus(Status.CHANGING_MEMBERSHIP);
                        assumeHubRole();
                    } else
                        Log.d(Config.TAG,"No need to change roles (currently "+role.name()+") as it appears discovery was successful");
                },INTERVAL_LISTEN_BEFORE_PUBLISH);
            }
        }
    }

    /**
     * Take over the role as the Hub for this mesh
     */
    private void assumeHubRole() {
        Log.d(Config.TAG,"Assuming the hub role");
        startAdvertising();
    }

    @Override
    public void disconnect() throws ManetException {
        if (hardwareStatusReceiver != null) {
            try {
                context.unregisterReceiver(hardwareStatusReceiver);
                hardwareStatusReceiver = null;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        if (discoverySession != null) {
            discoverySession.close();
            discoverySession = null;
        }
        if (awareSession != null) {
            awareSession.close();
            awareSession = null;
        }
        setStatus(Status.OFF);
        CommsLog.log(CommsLog.Entry.Category.STATUS, "MANET disconnected");
        isRunning.set(true);
    }

    @Override
    protected void onDeviceLost(SqAnDevice device, boolean directConnection) {
        //TODO
    }

    @Override
    public void executePeriodicTasks() {
        if (!isRunning()) {
            try {
                Log.d(Config.TAG,"Attempting to restart WiFi Aware manager");
                init();
            } catch (ManetException e) {
                Log.e(Config.TAG, "Unable to initialize WiFi Aware: " + e.getMessage());
            }
        }

        removeUnresponsiveConnections();
    }

    private void removeUnresponsiveConnections() {
        if ((connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                int i=0;
                final long timeToConsiderStale = System.currentTimeMillis() - TIME_TO_CONSIDER_STALE_DEVICE;
                while (i<connections.size()) {
                    if (connections.get(i) == null) {
                        connections.remove(i);
                        continue;
                    }
                    if (timeToConsiderStale > connections.get(i).getLastConnection()) {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing stale WiFi Aware connection: "+((connections.get(i).getDevice()==null)?"device unknown":connections.get(i).getDevice().getLabel()));
                        connections.remove(i);
                    } else
                        i++;
                }
            }
        }
    }

    /**
     * Entry point when a change in the availability of WiFiAware is detected
     */
    private void onWiFiAwareStatusChanged() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WifiAwareManager mgr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
            if (mgr != null) {
                Log.d(Config.TAG, "WiFi Aware state changed: " + (mgr.isAvailable() ? "available" : "not available"));
                //TODO
            }
        }
    }

    /**
     * Called based on the frequently (30min or less) randomization of MACs assigned for WiFiAware
     * @param mac the new MAC assigned to this device for WiFiAware use
     */
    public void onMacChanged (byte[] mac) {
        //TODO
    }
}
