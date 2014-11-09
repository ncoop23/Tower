package org.droidplanner.android.fragments;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.getpebble.android.kit.PebbleKit;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.HitBuilders;
import com.ox3dr.services.android.lib.drone.connection.ConnectionType;
import com.ox3dr.services.android.lib.drone.event.Event;
import com.ox3dr.services.android.lib.drone.event.Extra;
import com.ox3dr.services.android.lib.drone.property.State;
import com.ox3dr.services.android.lib.drone.property.Type;

import org.droidplanner.R;
import org.droidplanner.android.DroidPlannerApp;
import org.droidplanner.android.activities.helpers.MapPreferencesActivity;
import org.droidplanner.android.api.DroneApi;
import org.droidplanner.android.maps.providers.DPMapProvider;
import org.droidplanner.android.utils.analytics.GAUtils;
import org.droidplanner.android.utils.file.DirectoryPath;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;

/**
 * Implements the application settings screen.
 */
public class SettingsFragment extends PreferenceFragment implements
		OnSharedPreferenceChangeListener, DroidPlannerApp.ApiListener {

	/**
	 * Used as tag for logging.
	 */
	private final static String TAG = SettingsFragment.class.getSimpleName();

	private static final String PACKAGE_NAME = SettingsFragment.class.getPackage().getName();

	/**
	 * Action used to broadcast updates to the period for the spoken status
	 * summary.
	 */
	public static final String ACTION_UPDATED_STATUS_PERIOD = PACKAGE_NAME + ""
			+ ".ACTION_UPDATED_STATUS_PERIOD";

	/**
	 * Used to retrieve the new period for the spoken status summary.
	 */
	public static final String EXTRA_UPDATED_STATUS_PERIOD = "extra_updated_status_period";

	private static final IntentFilter intentFilter = new IntentFilter();
	static {
		intentFilter.addAction(Event.EVENT_DISCONNECTED);
		intentFilter.addAction(Event.EVENT_STATE);
		intentFilter.addAction(Event.EVENT_HEARTBEAT_FIRST);
		intentFilter.addAction(Event.EVENT_HEARTBEAT_RESTORED);
		intentFilter.addAction(Event.EVENT_TYPE_UPDATED);
	}

	private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (Event.EVENT_DISCONNECTED.equals(action)) {
				updateMavlinkVersionPreference(null);
				updateFirmwareVersionPreference(null);
			} else if (Event.EVENT_HEARTBEAT_FIRST.equals(action)
					|| Event.EVENT_HEARTBEAT_RESTORED.equals(action)) {
				int mavlinkVersion = intent.getIntExtra(Extra.EXTRA_MAVLINK_VERSION, -1);
				if (mavlinkVersion == -1)
					updateMavlinkVersionPreference(null);
				else
					updateMavlinkVersionPreference(String.valueOf(mavlinkVersion));
			} else if (Event.EVENT_TYPE_UPDATED.equals(action)) {
				if (dpApp.isDpApiConnected()) {
					updateFirmwareVersionPreference(dpApp.getDroneApi().getType()
							.getFirmwareVersion());
				} else
					updateFirmwareVersionPreference(null);
			}
		}
	};

	/**
	 * Keep track of which preferences' summary need to be updated.
	 */
	private final HashSet<String> mDefaultSummaryPrefs = new HashSet<String>();

	private final Handler mHandler = new Handler();

	private DroidPlannerApp dpApp;
	private DroidPlannerPrefs dpPrefs;

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		dpApp = (DroidPlannerApp) activity.getApplication();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);

		initSummaryPerPrefs();

		final Context context = getActivity().getApplicationContext();
		dpPrefs = new DroidPlannerPrefs(context);
		final SharedPreferences sharedPref = dpPrefs.prefs;

		setupPeriodicControls();

		// Populate the map preference category
		final String mapsProvidersPrefKey = getString(R.string.pref_maps_providers_key);
		final ListPreference mapsProvidersPref = (ListPreference) findPreference(mapsProvidersPrefKey);
		if (mapsProvidersPref != null) {
			// FIXME: Restore Mapbox map provider when feature complete.
			final DPMapProvider[] providers = { DPMapProvider.GOOGLE_MAP };// DPMapProvider.values();
			final int providersCount = providers.length;
			final CharSequence[] providersNames = new CharSequence[providersCount];
			final CharSequence[] providersNamesValues = new CharSequence[providersCount];
			for (int i = 0; i < providersCount; i++) {
				final String providerName = providers[i].name();
				providersNamesValues[i] = providerName;
				providersNames[i] = providerName.toLowerCase(Locale.ENGLISH).replace('_', ' ');
			}

			// final String defaultProviderName =
			// sharedPref.getString(mapsProvidersPrefKey,
			// DPMapProvider.DEFAULT_MAP_PROVIDER.name());
			final String defaultProviderName = DPMapProvider.DEFAULT_MAP_PROVIDER.name();

			mapsProvidersPref.setEntries(providersNames);
			mapsProvidersPref.setEntryValues(providersNamesValues);
			mapsProvidersPref.setValue(defaultProviderName);
			mapsProvidersPref
					.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {

						@Override
						public boolean onPreferenceChange(Preference preference, Object newValue) {
							// Update the map provider settings preference.
							final String mapProviderName = newValue.toString();
							return updateMapSettingsPreference(mapProviderName);
						}
					});

			updateMapSettingsPreference(defaultProviderName);
		}

		// update the summary for the preferences in the mDefaultSummaryPrefs
		// hash table.
		for (String prefKey : mDefaultSummaryPrefs) {
			final Preference pref = findPreference(prefKey);
			if (pref != null) {
				pref.setSummary(sharedPref.getString(prefKey, ""));
			}
		}

		final String maxFlightPathSizeKey = getString(R.string.pref_max_flight_path_size_key);
		final Preference maxFlightPathSizePref = findPreference(maxFlightPathSizeKey);
		if (maxFlightPathSizePref != null) {
			maxFlightPathSizePref.setSummary(sharedPref.getString(maxFlightPathSizeKey, "") + " "
					+ getString(R.string.set_to_zero_to_disable));
		}

		final String rcModeKey = getString(R.string.pref_rc_mode_key);
		final Preference rcModePref = findPreference(rcModeKey);
		if (rcModePref != null) {
			if (sharedPref.getString(rcModeKey, "MODE2").equalsIgnoreCase("MODE1")) {
				rcModePref.setSummary(getString(R.string.mode1_throttle_on_right_stick));
			} else {
				rcModePref.setSummary(getString(R.string.mode2_throttle_on_left_stick));
			}
		}

		// Set the usage statistics preference
		final String usageStatKey = getString(R.string.pref_usage_statistics_key);
		final CheckBoxPreference usageStatPref = (CheckBoxPreference) findPreference(usageStatKey);
		if (usageStatPref != null) {
			usageStatPref
					.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
						@Override
						public boolean onPreferenceChange(Preference preference, Object newValue) {
							// Update the google analytics singleton.
							final boolean optIn = (Boolean) newValue;
							final GoogleAnalytics analytics = GoogleAnalytics.getInstance(context);
							analytics.setAppOptOut(!optIn);
							return true;
						}
					});
		}

		final Preference storagePref = findPreference(getString(R.string.pref_storage_key));
		if (storagePref != null) {
			storagePref.setSummary(DirectoryPath.getDroidPlannerPath());
		}

		try {
			Preference versionPref = findPreference("pref_version");
			if (versionPref != null) {
				String version = context.getPackageManager().getPackageInfo(
						context.getPackageName(), 0).versionName;
				versionPref.setSummary(version);
			}
		} catch (NameNotFoundException e) {
			Log.e(TAG, "Unable to retrieve version name.", e);
		}

		updateMavlinkVersionPreference(null);
		setupPebblePreference();
		setDronesharePreferencesListeners();
		setupConnectionPreferences();
	}

	/**
	 * When a droneshare preference is updated, the listener will kick start the
	 * droneshare uploader service to see if any action is needed.
	 */
	private void setDronesharePreferencesListeners() {
		final Context context = getActivity().getApplicationContext();

		CheckBoxPreference dshareTogglePref = (CheckBoxPreference) findPreference(getString(R.string.pref_dshare_enabled_key));
		if (dshareTogglePref != null) {
			dshareTogglePref
					.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
						@Override
						public boolean onPreferenceChange(Preference preference, Object newValue) {
                            dpApp.getDroneApi().enableDroneShare(dpPrefs.getDroneshareLogin(),
                                    dpPrefs.getDronesharePassword(), (Boolean) newValue);
							return true;
						}
					});
		}

		EditTextPreference dshareUsernamePref = (EditTextPreference) findPreference(getString(R.string.pref_dshare_username_key));
		if (dshareUsernamePref != null) {
			dshareUsernamePref
					.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
						@Override
						public boolean onPreferenceChange(Preference preference, Object newValue) {
                            dpApp.getDroneApi().enableDroneShare(newValue.toString(),
                                    dpPrefs.getDronesharePassword(), dpPrefs.getDroneshareEnabled());
							return true;
						}
					});
		}

		EditTextPreference dsharePasswordPref = (EditTextPreference) findPreference(getString(R.string.pref_dshare_password_key));
		if (dsharePasswordPref != null) {
			dsharePasswordPref
					.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
						@Override
						public boolean onPreferenceChange(Preference preference, Object newValue) {
                            dpApp.getDroneApi().enableDroneShare(dpPrefs.getDroneshareLogin(),
                                    newValue.toString(), dpPrefs.getDroneshareEnabled());
							return true;
						}
					});
		}
	}

	private void setupConnectionPreferences() {
		ListPreference connectionTypePref = (ListPreference) findPreference(getString(R.string.pref_connection_type_key));
		if (connectionTypePref != null) {
			int defaultConnectionType = dpPrefs.getConnectionParameterType();
			updateConnectionPreferenceSummary(connectionTypePref, defaultConnectionType);
			connectionTypePref
					.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
						@Override
						public boolean onPreferenceChange(Preference preference, Object newValue) {
							int connectionType = Integer.parseInt((String) newValue);
							updateConnectionPreferenceSummary(preference, connectionType);
							return true;
						}
					});
		}
	}

	private void updateConnectionPreferenceSummary(Preference preference, int connectionType) {
		String connectionName;
		switch (connectionType) {
		case ConnectionType.TYPE_USB:
			connectionName = "USB";
			break;

		case ConnectionType.TYPE_UDP:
			connectionName = "UDP";
			break;

		case ConnectionType.TYPE_TCP:
			connectionName = "TCP";
			break;

		case ConnectionType.TYPE_BLUETOOTH:
			connectionName = "BLUETOOTH";
			break;

		default:
			connectionName = null;
			break;
		}

		if (connectionName != null)
			preference.setSummary(connectionName);
	}

	/**
	 * Pebble Install Button. When clicked, will check for pebble if pebble is
	 * not present, error displayed. If it is, the pbw (pebble bundle) will be
	 * copied from assets to external memory (makes sure to overwrite), and
	 * sends pbw intent for pebble app to install bundle.
	 */
	private void setupPebblePreference() {
		final Context context = getActivity().getApplicationContext();

		Preference pebblePreference = findPreference(getString(R.string.pref_pebble_install_key));
		pebblePreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
			@Override
			public boolean onPreferenceClick(Preference pref) {
				if (PebbleKit.isWatchConnected(context)) {
					InputStream in = null;
					OutputStream out = null;
					try {
						in = context.getAssets().open("Pebble/DroidPlanner.pbw");
						File outFile = new File(DirectoryPath.getDroidPlannerPath(),
								"DroidPlanner.pbw");
						out = new FileOutputStream(outFile);
						byte[] buffer = new byte[1024];
						int read;
						while ((read = in.read(buffer)) != -1) {
							out.write(buffer, 0, read);
						}
						in.close();
						in = null;
						out.flush();
						out.close();
						out = null;

						Intent intent = new Intent(Intent.ACTION_VIEW);
						intent.setData(Uri.fromFile(outFile));
						intent.setClassName("com.getpebble.android",
								"com.getpebble.android.ui.UpdateActivity");
						startActivity(intent);
					} catch (IOException e) {
						Log.e("pebble", "Failed to copy pbw asset", e);
						Toast.makeText(context, "Failed to copy pbw asset", Toast.LENGTH_SHORT)
								.show();
					} catch (ActivityNotFoundException e) {
						Log.e("pebble", "Pebble App Not installed", e);
						Toast.makeText(context, "Pebble App Not installed", Toast.LENGTH_SHORT)
								.show();
					}
				} else {
					Toast.makeText(context, "No Pebble Connected", Toast.LENGTH_SHORT).show();
				}
				return true;
			}
		});
	}

	private void initSummaryPerPrefs() {
		mDefaultSummaryPrefs.clear();

		mDefaultSummaryPrefs.add(getString(R.string.pref_baud_type_key));
		mDefaultSummaryPrefs.add(getString(R.string.pref_server_port_key));
		mDefaultSummaryPrefs.add(getString(R.string.pref_server_ip_key));
		mDefaultSummaryPrefs.add(getString(R.string.pref_udp_server_port_key));
		mDefaultSummaryPrefs.add(getString(R.string.pref_bluetooth_device_address_key));
		mDefaultSummaryPrefs.add(getString(R.string.pref_rc_quickmode_left_key));
		mDefaultSummaryPrefs.add(getString(R.string.pref_rc_quickmode_right_key));
	}

	/**
	 * This is used to update the mavlink version preference.
	 * 
	 * @param version
	 *            mavlink version
	 */
	private void updateMavlinkVersionPreference(String version) {
		final Preference mavlinkVersionPref = findPreference(getString(R.string.pref_mavlink_version_key));
		if (mavlinkVersionPref != null) {
			final HitBuilders.EventBuilder mavlinkEvent = new HitBuilders.EventBuilder()
					.setCategory(GAUtils.Category.MAVLINK_CONNECTION);

			if (version == null) {
				mavlinkVersionPref.setSummary(getString(R.string.empty_content));
				mavlinkEvent.setAction("Mavlink version unset");
			} else {
				mavlinkVersionPref.setSummary('v' + version);
				mavlinkEvent.setAction("Mavlink version set").setLabel(version);
			}

			// Record the mavlink version
			GAUtils.sendEvent(mavlinkEvent);
		}
	}

	private void updateFirmwareVersionPreference(String firmwareVersion) {
		final Preference firmwareVersionPref = findPreference(getString(R.string.pref_firmware_version_key));
		if (firmwareVersionPref != null) {
			final HitBuilders.EventBuilder firmwareEvent = new HitBuilders.EventBuilder()
					.setCategory(GAUtils.Category.MAVLINK_CONNECTION);

			if (firmwareVersion == null) {
				firmwareVersionPref.setSummary(getString(R.string.empty_content));
				firmwareEvent.setAction("Firmware version unset");
			} else {
				firmwareVersionPref.setSummary(firmwareVersion);
				firmwareEvent.setAction("Firmware version set").setLabel(firmwareVersion);
			}

			// Record the firmware version.
			GAUtils.sendEvent(firmwareEvent);
		}
	}

	private boolean updateMapSettingsPreference(final String mapProviderName) {
		final DPMapProvider mapProvider = DPMapProvider.getMapProvider(mapProviderName);
		if (mapProvider == null)
			return false;

		final Preference providerPrefs = findPreference(getText(R.string.pref_map_provider_settings_key));
		if (providerPrefs != null) {
			providerPrefs.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
				@Override
				public boolean onPreferenceClick(Preference preference) {
					startActivity(new Intent(getActivity(), MapPreferencesActivity.class).putExtra(
							MapPreferencesActivity.EXTRA_MAP_PROVIDER_NAME, mapProviderName));
					return true;
				}
			});
		}
		return true;
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		final Preference preference = findPreference(key);
		if (preference == null) {
			return;
		}

		if (mDefaultSummaryPrefs.contains(key)) {
			preference.setSummary(sharedPreferences.getString(key, ""));
		}

		if (key.equals(getString(R.string.pref_max_flight_path_size_key))) {
			preference.setSummary(sharedPreferences.getString(key, "") + " "
					+ getString(R.string.set_to_zero_to_disable));
		}

		if (key.equals(getString(R.string.pref_rc_mode_key))) {
			if (sharedPreferences.getString(key, "MODE2").equalsIgnoreCase("MODE1")) {
				preference.setSummary(R.string.mode1_throttle_on_right_stick);
			} else {
				preference.setSummary(R.string.mode2_throttle_on_left_stick);
			}
		}
	}

	private void setupPeriodicControls() {
		final PreferenceCategory periodicSpeechPrefs = (PreferenceCategory) findPreference(getString(R.string.pref_tts_periodic_key));
		ListPreference periodic = ((ListPreference) periodicSpeechPrefs.getPreference(0));
		periodic.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
			@Override
			public boolean onPreferenceChange(Preference preference, final Object newValue) {
				// Broadcast the event locally on update.
				// A handler is used to that the current action has the time to
				// return,
				// and store the value in the preferences.
				mHandler.post(new Runnable() {
					@Override
					public void run() {
						LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(
								new Intent(ACTION_UPDATED_STATUS_PERIOD).putExtra(
										EXTRA_UPDATED_STATUS_PERIOD, (String) newValue));

						setupPeriodicControls();
					}
				});
				return true;
			}
		});

		int val = Integer.parseInt(periodic.getValue());

		final boolean isEnabled = val != 0;
		if (isEnabled) {
			periodic.setSummary(getString(R.string.pref_tts_status_every) + " " + val + " "
					+ getString(R.string.pref_tts_seconds));
		} else {
			periodic.setSummary(R.string.pref_tts_periodic_status_disabled);
		}

		for (int i = 1; i < periodicSpeechPrefs.getPreferenceCount(); i++) {
			periodicSpeechPrefs.getPreference(i).setEnabled(isEnabled);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		dpApp.addApiListener(this);
	}

	@Override
	public void onStop() {
		super.onStop();
		dpApp.removeApiListener(this);
	}

	@Override
	public void onResume() {
		super.onResume();
		getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
	}

	@Override
	public void onPause() {
		super.onPause();
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
				this);
	}

	/**
	 * Allows the settings screen to perform the correct/expected behavior when
	 * the up arrow is clicked.
	 *
	 * @param prefScreen
	 * @param pref
	 * @return
	 */
	@Override
	public boolean onPreferenceTreeClick(PreferenceScreen prefScreen, Preference pref) {
		if (pref instanceof PreferenceScreen) {
			final Dialog dialog = ((PreferenceScreen) pref).getDialog();
			final View homeBtn = dialog.findViewById(android.R.id.home);
			if (homeBtn != null) {
				View.OnClickListener dismissDialogClickListener = new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						dialog.dismiss();
					}
				};

				// Prepare yourselves for some hacky programming
				ViewParent homeBtnContainer = homeBtn.getParent();

				// The home button is an ImageView inside a FrameLayout
				if (homeBtnContainer instanceof FrameLayout) {
					ViewGroup containerParent = (ViewGroup) homeBtnContainer.getParent();

					if (containerParent instanceof LinearLayout) {
						// This view also contains the title text, set the whole
						// view as clickable
						containerParent.setOnClickListener(dismissDialogClickListener);
					} else {
						// Just set it on the home button
						((FrameLayout) homeBtnContainer)
								.setOnClickListener(dismissDialogClickListener);
					}
				} else {
					// The 'If all else fails' default case
					homeBtn.setOnClickListener(dismissDialogClickListener);
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public void onApiConnected(DroneApi api) {
		State droneState = api.getState();
		Type droneType = api.getType();
		final int mavlinkVersion = droneState.getMavlinkVersion();
		if (mavlinkVersion != State.INVALID_MAVLINK_VERSION) {
			updateMavlinkVersionPreference(String.valueOf(mavlinkVersion));
		} else {
			updateMavlinkVersionPreference(null);
		}

		updateFirmwareVersionPreference(droneType.getFirmwareVersion());

		LocalBroadcastManager.getInstance(getActivity().getApplicationContext()).registerReceiver(
				broadcastReceiver, intentFilter);
	}

	@Override
	public void onApiDisconnected() {
		LocalBroadcastManager.getInstance(getActivity().getApplicationContext())
				.unregisterReceiver(broadcastReceiver);
	}
}
