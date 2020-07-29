/*  Copyright (C) 2015-2020 Andreas Shimokawa, boun, Carsten Pfeiffer, Daniel
    Dakhno, Daniele Gobbetti, JohnnySun, jonnsoft, Lem Dulfo, Taavi Eomäe,
    Uwe Hermann

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.adapter.DeviceCandidateAdapter;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils;
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

import static nodomain.freeyourgadget.gadgetbridge.util.GB.toast;


public class DiscoveryActivity extends AbstractGBActivity implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {
    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryActivity.class);
    private static final long SCAN_DURATION = 30000; // 30s
    private static final int REQUEST_CODE = 1;
    private ScanCallback newBLEScanCallback = null;

    /** Use old BLE scanning **/
    private boolean oldBleScanning = false;
    /** If already bonded devices are to be ignored when scanning */
    private boolean ignoreBonded = true;
    /** If new CompanionDevice-type pairing is enabled on newer Androids **/
    private boolean enableCompanionDevicePairing = false;

    private final Handler handler = new Handler();
    private ProgressBar bluetoothProgress;
    private ProgressBar bluetoothLEProgress;

    private final Runnable stopRunnable = new Runnable() {
        @Override
        public void run() {
            if (isScanning == Scanning.SCANNING_BT_NEXT_BLE) {
                // Start the next scan in the series
                stopDiscovery();
                startDiscovery(Scanning.SCANNING_BLE);
            } else {
                stopDiscovery();
            }
        }
    };
    private DeviceCandidateAdapter deviceCandidateAdapter;
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (Objects.requireNonNull(intent.getAction())) {
                case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                    if (isScanning != Scanning.SCANNING_BLE) {
                        if (isScanning != Scanning.SCANNING_BT_NEXT_BLE) {
                            isScanning = Scanning.SCANNING_BT;
                        }
                        startButton.setText(getString(R.string.discovery_stop_scanning));
                    }
                    break;
                case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            // continue with LE scan, if available
                            if (isScanning == Scanning.SCANNING_BT || isScanning == Scanning.SCANNING_BT_NEXT_BLE) {
                                checkAndRequestLocationPermission();
                                stopDiscovery();
                                startDiscovery(Scanning.SCANNING_BLE);
                            } else {
                                discoveryFinished();
                            }
                        }
                    });
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                    bluetoothStateChanged(newState);
                    break;
                case BluetoothDevice.ACTION_FOUND: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, GBDevice.RSSI_UNKNOWN);
                    handleDeviceFound(device, rssi);
                    break;
                }
                case BluetoothDevice.ACTION_UUID: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, GBDevice.RSSI_UNKNOWN);
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    ParcelUuid[] uuids2 = AndroidUtils.toParcelUuids(uuids);
                    handleDeviceFound(device, rssi, uuids2);
                    break;
                }
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null && bondingDevice != null && device.getAddress().equals(bondingDevice.getMacAddress())) {
                        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            handleDeviceBonded();
                        }
                    }
                }
            }
        }
    };

    private void connectAndFinish(GBDevice device) {
        toast(DiscoveryActivity.this, getString(R.string.discovery_trying_to_connect_to, device.getName()), Toast.LENGTH_SHORT, GB.INFO);
        GBApplication.deviceService().connect(device, true);
        finish();
    }

    private void createBond(final GBDeviceCandidate deviceCandidate, int bondingStyle) {
        if (bondingStyle == DeviceCoordinator.BONDING_STYLE_NONE) {
            // Do nothing
            return;
        } else if (bondingStyle == DeviceCoordinator.BONDING_STYLE_ASK) {
            new AlertDialog.Builder(this)
                    .setCancelable(true)
                    .setTitle(DiscoveryActivity.this.getString(R.string.discovery_pair_title, deviceCandidate.getName()))
                    .setMessage(DiscoveryActivity.this.getString(R.string.discovery_pair_question))
                    .setPositiveButton(DiscoveryActivity.this.getString(R.string.discovery_yes_pair), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            doCreatePair(deviceCandidate);
                        }
                    })
                    .setNegativeButton(R.string.discovery_dont_pair, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            GBDevice device = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate);
                            connectAndFinish(device);
                        }
                    })
                    .show();
        } else {
            doCreatePair(deviceCandidate);
        }
        LOG.debug("Bonding initiated");
    }

    private void doCreatePair(GBDeviceCandidate deviceCandidate) {
        toast(DiscoveryActivity.this, getString(R.string.discovery_attempting_to_pair, deviceCandidate.getName()), Toast.LENGTH_SHORT, GB.INFO);
        if (enableCompanionDevicePairing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            companionDevicePair(deviceCandidate);
        } else {
            deviceBond(deviceCandidate);
        }
    }

    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            //logMessageContent(scanRecord);
            handleDeviceFound(device, (short) rssi);
        }
    };

    private void deviceBond(GBDeviceCandidate deviceCandidate) {
        if (deviceCandidate.getDevice().createBond()) {
            // Async, wait for bonding event to finish this activity
            LOG.info("Bonding in progress...");
            bondingDevice = deviceCandidate;
        } else {
            toast(DiscoveryActivity.this, getString(R.string.discovery_bonding_failed_immediately, deviceCandidate.getName()), Toast.LENGTH_SHORT, GB.ERROR);
        }
    }

    public void logMessageContent(byte[] value) {
        if (value != null) {
            LOG.warn("DATA: " + GB.hexdump(value, 0, value.length));
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void companionDevicePair(final GBDeviceCandidate deviceCandidate) {
        CompanionDeviceManager deviceManager = getSystemService(CompanionDeviceManager.class);

        BluetoothDeviceFilter deviceFilter = new BluetoothDeviceFilter.Builder()
                .setAddress(deviceCandidate.getMacAddress())
                .build();

        AssociationRequest pairingRequest = new AssociationRequest.Builder()
                .addDeviceFilter(deviceFilter)
                .setSingleDevice(true)
                .build();

        deviceManager.associate(pairingRequest,
                new CompanionDeviceManager.Callback() {
                    @Override
                    public void onFailure(CharSequence error) {
                        toast(DiscoveryActivity.this, getString(R.string.discovery_bonding_failed_immediately, deviceCandidate.getName()), Toast.LENGTH_SHORT, GB.ERROR);
                    }

                    @Override
                    public void onDeviceFound(IntentSender chooserLauncher) {
                        try {
                            startIntentSenderForResult(chooserLauncher,
                                    REQUEST_CODE, null, 0, 0, 0);
                        } catch (IntentSender.SendIntentException e) {
                            e.printStackTrace();
                        }
                    }
                },
                null
        );
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE &&
                resultCode == Activity.RESULT_OK) {

            BluetoothDevice deviceToPair =
                    data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);

            if (deviceToPair != null) {
                deviceBond(new GBDeviceCandidate(deviceToPair, (short) 0, null));
                handleDeviceBonded();
            }
        }
    }

    private void handleDeviceBonded() {
        if (bondingDevice == null) {
            return;
        }

        toast(DiscoveryActivity.this, getString(R.string.discovery_successfully_bonded, bondingDevice.getName()), Toast.LENGTH_SHORT, GB.INFO);
        GBDevice device = DeviceHelper.getInstance().toSupportedDevice(bondingDevice);
        connectAndFinish(device);
    }

    private BluetoothAdapter adapter;
    private final ArrayList<GBDeviceCandidate> deviceCandidates = new ArrayList<>();

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private ScanCallback getScanCallback() {
        if (newBLEScanCallback != null) {
            return newBLEScanCallback;
        }

        newBLEScanCallback = new ScanCallback() {
            @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                super.onScanResult(callbackType, result);
                try {
                    ScanRecord scanRecord = result.getScanRecord();
                    ParcelUuid[] uuids = null;
                    if (scanRecord != null) {
                        //logMessageContent(scanRecord.getBytes());
                        List<ParcelUuid> serviceUuids = scanRecord.getServiceUuids();
                        if (serviceUuids != null) {
                            uuids = serviceUuids.toArray(new ParcelUuid[0]);
                        }
                    }
                    LOG.warn(result.getDevice().getName() + ": " +
                            ((scanRecord != null) ? scanRecord.getBytes().length : -1));
                    handleDeviceFound(result.getDevice(), (short) result.getRssi(), uuids);
                } catch (NullPointerException e) {
                    LOG.warn("Error handling scan result", e);
                }
            }
        };

        return newBLEScanCallback;
    }

    private Button startButton;
    private Scanning isScanning = Scanning.SCANNING_OFF;
    private GBDeviceCandidate bondingDevice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Prefs prefs = GBApplication.getPrefs();
        ignoreBonded = prefs.getBoolean("ignore_bonded_devices", true);

        oldBleScanning = prefs.getBoolean("disable_new_ble_scanning", false);
        if (oldBleScanning) {
            LOG.info("New BLE scanning disabled via settings, using old method");
        }

        enableCompanionDevicePairing = prefs.getBoolean("enable_companiondevice_pairing", true);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            enableCompanionDevicePairing = false; // No support below 26
        }

        setContentView(R.layout.activity_discovery);
        startButton = findViewById(R.id.discovery_start);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartButtonClick(startButton);
            }
        });

        bluetoothProgress = findViewById(R.id.discovery_progressbar);
        bluetoothProgress.setProgress(0);
        bluetoothProgress.setIndeterminate(true);
        bluetoothProgress.setVisibility(View.GONE);
        ListView deviceCandidatesView = findViewById(R.id.discovery_device_candidates_list);

        bluetoothLEProgress = findViewById(R.id.discovery_ble_progressbar);
        bluetoothLEProgress.setProgress(0);
        bluetoothLEProgress.setIndeterminate(true);
        bluetoothLEProgress.setVisibility(View.GONE);

        deviceCandidateAdapter = new DeviceCandidateAdapter(this, deviceCandidates);
        deviceCandidatesView.setAdapter(deviceCandidateAdapter);
        deviceCandidatesView.setOnItemClickListener(this);
        deviceCandidatesView.setOnItemLongClickListener(this);

        IntentFilter bluetoothIntents = new IntentFilter();
        bluetoothIntents.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothIntents.addAction(BluetoothDevice.ACTION_UUID);
        bluetoothIntents.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        bluetoothIntents.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

        registerReceiver(bluetoothReceiver, bluetoothIntents);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            toast(DiscoveryActivity.this, getString(R.string.error_no_location_access), Toast.LENGTH_SHORT, GB.ERROR);
            LOG.error("No permission to access coarse location!");
            checkAndRequestLocationPermission();

            // We can't be sure location was granted, cancel scan start and wait for user action
            return;
        }

        LocationManager locationManager = (LocationManager) DiscoveryActivity.this.getSystemService(Context.LOCATION_SERVICE);
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Do nothing
                LOG.debug("Some location provider is enabled, assuming location is enabled");
            } else {
                toast(DiscoveryActivity.this, getString(R.string.require_location_provider), Toast.LENGTH_LONG, GB.ERROR);
                DiscoveryActivity.this.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                // We can't be sure location was enabled, cancel scan start and wait for new user action
                return;
            }
        } catch (Exception ex) {
            LOG.error("Exception when checking location status: ", ex);
        }

        startDiscovery(Scanning.SCANNING_BT_NEXT_BLE);
    }

    public void onStartButtonClick(View button) {
        LOG.debug("Start Button clicked");
        if (isScanning()) {
            stopDiscovery();
        } else {
            if (GB.supportsBluetoothLE()) {
                startDiscovery(Scanning.SCANNING_BT_NEXT_BLE);
            } else {
                startDiscovery(Scanning.SCANNING_BT);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList("deviceCandidates", deviceCandidates);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        ArrayList<Parcelable> restoredCandidates = savedInstanceState.getParcelableArrayList("deviceCandidates");
        if (restoredCandidates != null) {
            deviceCandidates.clear();
            for (Parcelable p : restoredCandidates) {
                deviceCandidates.add((GBDeviceCandidate) p);
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException e) {
            LOG.warn("Tried to unregister Bluetooth Receiver that wasn't registered");
            LOG.warn(e.getMessage());
        }

        super.onDestroy();
    }

    private void handleDeviceFound(BluetoothDevice device, short rssi) {
        if (device.getName() != null) {
            if (handleDeviceFound(device, rssi, null)) {
                LOG.info("found supported device " + device.getName() + " without scanning services, skipping service scan.");
                return;
            }
        }
        ParcelUuid[] uuids = device.getUuids();
        if (uuids == null) {
            if (device.fetchUuidsWithSdp()) {
                return;
            }
        }

        handleDeviceFound(device, rssi, uuids);
    }

    private boolean handleDeviceFound(BluetoothDevice device, short rssi, ParcelUuid[] uuids) {
        LOG.debug("found device: " + device.getName() + ", " + device.getAddress());
        if (LOG.isDebugEnabled()) {
            if (uuids != null && uuids.length > 0) {
                for (ParcelUuid uuid : uuids) {
                    LOG.debug("  supports uuid: " + uuid.toString());
                }
            }
        }

        if (device.getBondState() == BluetoothDevice.BOND_BONDED && ignoreBonded) {
            return true; // Ignore already bonded devices
        }

        GBDeviceCandidate candidate = new GBDeviceCandidate(device, rssi, uuids);
        DeviceType deviceType = DeviceHelper.getInstance().getSupportedType(candidate);
        if (deviceType.isSupported()) {
            candidate.setDeviceType(deviceType);
            LOG.info("Recognized supported device: " + candidate);
            int index = deviceCandidates.indexOf(candidate);
            if (index >= 0) {
                deviceCandidates.set(index, candidate); // replace
            } else {
                deviceCandidates.add(candidate);
            }
            deviceCandidateAdapter.notifyDataSetChanged();
            return true;
        }
        return false;
    }

    private void startDiscovery(Scanning what) {
        if (isScanning()) {
            LOG.warn("Not starting discovery, because already scanning.");
            return;
        }

        LOG.info("Starting discovery: " + what);
        startButton.setText(getString(R.string.discovery_stop_scanning));
        if (ensureBluetoothReady() && isScanning == Scanning.SCANNING_OFF) {
            if (what == Scanning.SCANNING_BT || what == Scanning.SCANNING_BT_NEXT_BLE) {
                startBTDiscovery(what);
            } else if (what == Scanning.SCANNING_BLE && GB.supportsBluetoothLE()) {
                if (oldBleScanning || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    startOldBTLEDiscovery();
                } else {
                    startBTLEDiscovery();
                }
            } else {
                discoveryFinished();
                toast(DiscoveryActivity.this, getString(R.string.discovery_enable_bluetooth), Toast.LENGTH_SHORT, GB.ERROR);
            }
        } else {
            discoveryFinished();
            toast(DiscoveryActivity.this, getString(R.string.discovery_enable_bluetooth), Toast.LENGTH_SHORT, GB.ERROR);
        }
    }

    private void stopDiscovery() {
        LOG.info("Stopping discovery");
        if (isScanning()) {
            Scanning wasScanning = isScanning;
            if (wasScanning == Scanning.SCANNING_BT || wasScanning == Scanning.SCANNING_BT_NEXT_BLE) {
                stopBTDiscovery();
            } else if (wasScanning == Scanning.SCANNING_BLE) {
                if (oldBleScanning || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    stopOldBLEDiscovery();
                } else {
                    stopBLEDiscovery();
                }
            }

            discoveryFinished();
            handler.removeMessages(0, stopRunnable);
        }
    }

    private boolean isScanning() {
        return isScanning != Scanning.SCANNING_OFF;
    }

    private void startOldBTLEDiscovery() {
        LOG.info("Starting old BLE discovery");
        isScanning = Scanning.SCANNING_BLE;

        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        adapter.startLeScan(leScanCallback);

        bluetoothLEProgress.setVisibility(View.VISIBLE);
    }

    private void stopOldBLEDiscovery() {
        if (adapter != null) {
            adapter.stopLeScan(leScanCallback);

            isScanning = Scanning.SCANNING_OFF;
            bluetoothLEProgress.setVisibility(View.GONE);
            LOG.info("Stopped old BLE discovery");
        }
    }

    /* New BTLE Discovery uses startScan (List<ScanFilter> filters,
                                         ScanSettings settings,
                                         ScanCallback callback) */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private void startBTLEDiscovery() {
        LOG.info("Starting BLE discovery");
        isScanning = Scanning.SCANNING_BLE;

        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);

        // Filters being non-null would be a very good idea with background scan, but in this case,
        // not really required.
        adapter.getBluetoothLeScanner().startScan(null, getScanSettings(), getScanCallback());

        bluetoothLEProgress.setVisibility(View.VISIBLE);
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopBLEDiscovery() {
        if (adapter == null) {
            return;
        }

        BluetoothLeScanner bluetoothLeScanner = adapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            LOG.warn("Could not get BluetoothLeScanner()!");
            return;
        }
        if (newBLEScanCallback == null) {
            LOG.warn("newLeScanCallback == null!");
            return;
        }
        try {
            bluetoothLeScanner.stopScan(newBLEScanCallback);
        } catch (NullPointerException e) {
            LOG.warn("Internal NullPointerException when stopping the scan!");
            return;
        }

        isScanning = Scanning.SCANNING_OFF;
        bluetoothLEProgress.setVisibility(View.GONE);
        LOG.debug("Stopped BLE discovery");
    }

    /**
     * Starts a regular Bluetooth scan
     *
     * @param what The scan type, only either SCANNING_BT or SCANNING_BT_NEXT_BLE!
     */
    private void startBTDiscovery(Scanning what) {
        LOG.info("Starting BT discovery");
        isScanning = what;

        handler.removeMessages(0, stopRunnable);
        handler.sendMessageDelayed(getPostMessage(stopRunnable), SCAN_DURATION);
        if (adapter.startDiscovery()) {
            LOG.error("Discovery starting failed");
        }

        bluetoothProgress.setVisibility(View.VISIBLE);
    }

    private void stopBTDiscovery() {
        if (adapter != null) {
            adapter.cancelDiscovery();

            bluetoothProgress.setVisibility(View.GONE);
            isScanning = Scanning.SCANNING_OFF;
            LOG.info("Stopped BT discovery");
        }
    }

    private void discoveryFinished() {
        if (isScanning != Scanning.SCANNING_OFF) {
            LOG.warn("Scan was not properly stopped: " + isScanning);
        }
        startButton.setText(getString(R.string.discovery_start_scanning));
    }


    private void bluetoothStateChanged(int newState) {
        discoveryFinished();
        if (newState == BluetoothAdapter.STATE_ON) {
            this.adapter = BluetoothAdapter.getDefaultAdapter();
            startButton.setEnabled(true);
        } else {
            this.adapter = null;
            startButton.setEnabled(false);
        }
    }

    private boolean checkBluetoothAvailable() {
        BluetoothManager bluetoothService = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (bluetoothService == null) {
            LOG.warn("No bluetooth service available");
            this.adapter = null;
            return false;
        }
        BluetoothAdapter adapter = bluetoothService.getAdapter();
        if (adapter == null) {
            LOG.warn("No bluetooth adapter available");
            this.adapter = null;
            return false;
        }
        if (!adapter.isEnabled()) {
            LOG.warn("Bluetooth not enabled");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivity(enableBtIntent);
            this.adapter = null;
            return false;
        }
        this.adapter = adapter;
        return true;
    }

    private boolean ensureBluetoothReady() {
        boolean available = checkBluetoothAvailable();
        startButton.setEnabled(available);
        if (available) {
            adapter.cancelDiscovery();
            // must not return the result of cancelDiscovery()
            // appears to return false when currently not scanning
            return true;
        }
        return false;
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private ScanSettings getScanSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new ScanSettings.Builder()
                    .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(android.bluetooth.le.ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setPhy(android.bluetooth.le.ScanSettings.PHY_LE_ALL_SUPPORTED)
                    .setNumOfMatches(android.bluetooth.le.ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new ScanSettings.Builder()
                    .setCallbackType(android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(android.bluetooth.le.ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(android.bluetooth.le.ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .build();
        } else {
            return new ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
        }
    }

    private List<ScanFilter> getScanFilters() {
        List<ScanFilter> allFilters = new ArrayList<>();
        for (DeviceCoordinator coordinator : DeviceHelper.getInstance().getAllCoordinators()) {
            allFilters.addAll(coordinator.createBLEScanFilters());
        }
        return allFilters;
    }

    private Message getPostMessage(Runnable runnable) {
        Message message = Message.obtain(handler, runnable);
        message.obj = runnable;
        return message;
    }

    private void checkAndRequestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        GBDeviceCandidate deviceCandidate = deviceCandidates.get(position);
        if (deviceCandidate == null) {
            LOG.error("Device candidate clicked, but item not found");
            return;
        }

        stopDiscovery();
        DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(deviceCandidate);
        LOG.info("Using device candidate " + deviceCandidate + " with coordinator: " + coordinator.getClass());

        if (coordinator.getBondingStyle() == DeviceCoordinator.BONDING_STYLE_REQUIRE_KEY) {
            SharedPreferences sharedPrefs = GBApplication.getDeviceSpecificSharedPrefs(deviceCandidate.getMacAddress());

            String authKey = sharedPrefs.getString("authkey", null);
            if (authKey == null ||
                    authKey.isEmpty() ||
                    authKey.getBytes().length < 34 ||
                    !authKey.startsWith("0x")) {
                toast(DiscoveryActivity.this, getString(R.string.discovery_need_to_enter_authkey), Toast.LENGTH_LONG, GB.WARN);
                return;
            }
        }

        Class<? extends Activity> pairingActivity = coordinator.getPairingActivity();
        if (pairingActivity != null) {
            Intent intent = new Intent(this, pairingActivity);
            intent.putExtra(DeviceCoordinator.EXTRA_DEVICE_CANDIDATE, deviceCandidate);
            startActivity(intent);
        } else {
            GBDevice device = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate);
            int bondingStyle = coordinator.getBondingStyle();
            if (bondingStyle == DeviceCoordinator.BONDING_STYLE_NONE) {
                LOG.info("No bonding needed, according to coordinator, so connecting right away");
                connectAndFinish(device);
                return;
            }

            try {
                BluetoothDevice btDevice = adapter.getRemoteDevice(deviceCandidate.getMacAddress());
                switch (btDevice.getBondState()) {
                    case BluetoothDevice.BOND_NONE: {
                        createBond(deviceCandidate, bondingStyle);
                        break;
                    }
                    case BluetoothDevice.BOND_BONDING: {
                        // async, wait for bonding event to finish this activity
                        bondingDevice = deviceCandidate;
                        break;
                    }
                    case BluetoothDevice.BOND_BONDED: {
                        bondingDevice = deviceCandidate;
                        handleDeviceBonded();
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.error("Error pairing device: " + deviceCandidate.getMacAddress());
            }
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id) {
        GBDeviceCandidate deviceCandidate = deviceCandidates.get(position);
        if (deviceCandidate == null) {
            LOG.error("Device candidate clicked, but item not found");
            return true;
        }

        DeviceCoordinator coordinator = DeviceHelper.getInstance().getCoordinator(deviceCandidate);
        GBDevice device = DeviceHelper.getInstance().toSupportedDevice(deviceCandidate);
        if (coordinator.getSupportedDeviceSpecificSettings(device) == null) {
            return true;
        }

        Intent startIntent;
        startIntent = new Intent(this, DeviceSettingsActivity.class);
        startIntent.putExtra(GBDevice.EXTRA_DEVICE, device);
        startActivity(startIntent);
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopBTDiscovery();
        if (oldBleScanning) {
            stopOldBLEDiscovery();
        } else {
            if (GBApplication.isRunningLollipopOrLater()) {
                stopBLEDiscovery();
            }
        }
    }

    private enum Scanning {
        /**
         * Regular Bluetooth scan
         */
        SCANNING_BT,
        /**
         * Regular Bluetooth scan but when ends, start BLE scan
         */
        SCANNING_BT_NEXT_BLE,
        /**
         * Regular BLE scan
         */
        SCANNING_BLE,
        /**
         * Scanning has ended or hasn't been started
         */
        SCANNING_OFF
    }
}
