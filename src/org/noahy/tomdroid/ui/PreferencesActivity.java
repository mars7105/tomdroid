/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2009, Benoit Garret <benoit.garret_launchpad@gadz.org>
 * Copyright 2010, 2011 Olivier Bilodeau <olivier@bottomlesspit.org>
 * 
 * This file is part of Tomdroid.
 * 
 * Tomdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Tomdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Tomdroid.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.noahy.tomdroid.ui;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.provider.SearchRecentSuggestions;
import android.widget.Toast;
import org.noahy.tomdroid.NoteManager;
import org.noahy.tomdroid.R;
import org.noahy.tomdroid.sync.ServiceAuth;
import org.noahy.tomdroid.sync.SyncManager;
import org.noahy.tomdroid.sync.SyncService;
import org.noahy.tomdroid.util.FirstNote;
import org.noahy.tomdroid.util.Preferences;
import org.noahy.tomdroid.util.SearchSuggestionProvider;
import org.noahy.tomdroid.util.TLog;

import java.io.File;
import java.util.ArrayList;

public class PreferencesActivity extends PreferenceActivity {
	
	private static final String TAG = "PreferencesActivity";
	
	// TODO: put the various preferences in fields and figure out what to do on activity suspend/resume
	private EditTextPreference syncServer = null;
	private ListPreference syncService = null;
	private EditTextPreference sdLocation = null;
	private Preference clearSearchHistory = null;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);
		
		// Fill the Preferences fields
		syncServer = (EditTextPreference)findPreference(Preferences.Key.SYNC_SERVER.getName());
		syncService = (ListPreference)findPreference(Preferences.Key.SYNC_SERVICE.getName());
		sdLocation = (EditTextPreference)findPreference(Preferences.Key.SD_LOCATION.getName());
		clearSearchHistory = (Preference)findPreference(Preferences.Key.CLEAR_SEARCH_HISTORY.getName());
		
		// Set the default values if nothing exists
		this.setDefaults();
		
		// Fill the services combo list
		this.fillServices();
		
		// Enable or disable the server field depending on the selected sync service
		setServer(syncService.getValue());
		
		syncService.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
			
			public boolean onPreferenceChange(Preference preference, Object newValue) {
				String selectedSyncServiceKey = (String)newValue;
				
				// did the selection change?
				if (!syncService.getValue().contentEquals(selectedSyncServiceKey)) {
					TLog.d(TAG, "preference change triggered");
					
					syncServiceChanged(selectedSyncServiceKey);
				}
				return true;
			}
		});
		
		// Re-authenticate if the sync server changes
		syncServer.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

			public boolean onPreferenceChange(Preference preference,
					Object serverUri) {
				
				if (serverUri == null) {
					Toast.makeText(PreferencesActivity.this,
							getString(R.string.prefServerEmpty),
							Toast.LENGTH_SHORT).show();
					return false;
				}
				if ((serverUri.toString().contains("\t")) || (serverUri.toString().contains(" ")) || (serverUri.toString().contains("\n"))){
					noValidEntry(serverUri.toString());
					return false;
				}
			    
				authenticate((String) serverUri);
				return true;
			}
			
		});
		
		// Change the Folder Location
		sdLocation.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {

			public boolean onPreferenceChange(Preference preference, Object locationUri) {

				if (locationUri.equals(Preferences.getString(Preferences.Key.SD_LOCATION))) { 
					return false;
				}
				if ((locationUri.toString().contains("\t")) || (locationUri.toString().contains("\n"))) { 
					noValidEntry(locationUri.toString());
					return false;
				}
				
				File path = new File(Environment.getExternalStorageDirectory()
						+ "/" + locationUri + "/");

				if(!path.exists()) {
					TLog.w(TAG, "Folder {0} does not exist.", path);
					folderNotExisting(path.toString());
					return false;
				}
				
				Preferences.putString(Preferences.Key.SD_LOCATION, locationUri.toString());
				TLog.d(TAG, "Changed Folder to: " + path.toString());

				Tomdroid.NOTES_PATH = path.toString();
				sdLocation.setSummary(Tomdroid.NOTES_PATH);

				resetLocalDatabase();
				return true;
			}
		});
		
		//delete Search History
		clearSearchHistory.setOnPreferenceClickListener(new OnPreferenceClickListener() {
	        public boolean onPreferenceClick(Preference preference) {
	            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(PreferencesActivity.this,
	                    SearchSuggestionProvider.AUTHORITY, SearchSuggestionProvider.MODE);
	            suggestions.clearHistory();
	            	
	        	Toast.makeText(getBaseContext(),
                        getString(R.string.deletedSearchHistory),
                        Toast.LENGTH_LONG).show();
	        	TLog.d(TAG, "Deleted search history.");
	        	
	        	return true;
	        }
	    });
	}
	
	private void authenticate(String serverUri) {

		// update the value before doing anything
		Preferences.putString(Preferences.Key.SYNC_SERVER, serverUri);

		SyncService currentService = SyncManager.getInstance().getCurrentService();

		if (!currentService.needsAuth()) {
			return;
		}

		// service needs authentication
		TLog.i(TAG, "Creating dialog");

		final ProgressDialog authProgress = ProgressDialog.show(this, "",
				getString(R.string.prefSyncCompleteAuth), true, false);

		Handler handler = new Handler() {

			@Override
			public void handleMessage(Message msg) {

				boolean wasSuccsessful = false;
				Uri authorizationUri = (Uri) msg.obj;
				if (authorizationUri != null) {

					Intent i = new Intent(Intent.ACTION_VIEW, authorizationUri);
					startActivity(i);
					wasSuccsessful = true;

				} else {
					// Auth failed, don't update the value
					wasSuccsessful = false;
				}

				if (authProgress != null)
					authProgress.dismiss();

				if (wasSuccsessful) {
					resetLocalDatabase();
				} else {
					connectionFailed();
				}
			}
		};

		((ServiceAuth) currentService).getAuthUri(serverUri, handler);
	}
	
	private void fillServices()
	{
		ArrayList<SyncService> availableServices = SyncManager.getInstance().getServices();
		CharSequence[] entries = new CharSequence[availableServices.size()];
		CharSequence[] entryValues = new CharSequence[availableServices.size()];
		
		for (int i = 0; i < availableServices.size(); i++) {
			entries[i] = availableServices.get(i).getDescription();
			entryValues[i] = availableServices.get(i).getName();
		}
		
		syncService.setEntries(entries);
		syncService.setEntryValues(entryValues);
	}
	
	private void setDefaults()
	{
		String defaultServer = (String)Preferences.Key.SYNC_SERVER.getDefault();
		syncServer.setDefaultValue(defaultServer);
		if(syncServer.getText() == null)
			syncServer.setText(defaultServer);

		String defaultService = (String)Preferences.Key.SYNC_SERVICE.getDefault();
		syncService.setDefaultValue(defaultService);
		if(syncService.getValue() == null)
			syncService.setValue(defaultService);
		
		String defaultLocation = (String)Preferences.Key.SD_LOCATION.getDefault();
		sdLocation.setDefaultValue(defaultLocation);
		if(sdLocation.getText() == null)
			sdLocation.setText(defaultLocation);
	
	}

	private void setServer(String syncServiceKey) {

		SyncService service = SyncManager.getInstance().getService(syncServiceKey);

		if (service == null)
			return;

		syncServer.setEnabled(service.needsServer());
		syncService.setSummary(service.getDescription());
		sdLocation.setEnabled(service.needsLocation());
		sdLocation.setSummary(Tomdroid.NOTES_PATH);
	}
		
	private void connectionFailed() {
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.prefSyncConnectionFailed))
			.setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}})
			.show();
	}
	
	private void folderNotExisting(String path) {
		new AlertDialog.Builder(this)
			.setTitle(getString(R.string.error))
			.setMessage(String.format(getString(R.string.prefFolderCreated), path))
			.setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}})
			.show();
	}
	
	private void noValidEntry(String Entry) {
		new AlertDialog.Builder(this)
			.setTitle(getString(R.string.error))
			.setMessage(String.format(getString(R.string.prefNoValidEntry), Entry))
			.setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}})
			.show();
	}

	//TODO use LocalStorage wrapper from two-way-sync branch when it get's merged
	private void resetLocalDatabase() {
		getContentResolver().delete(Tomdroid.CONTENT_URI, null, null);
		Preferences.putLong(Preferences.Key.LATEST_SYNC_REVISION, 0);
		
		// add a first explanatory note
		// TODO this will be problematic with two-way sync
		NoteManager.putNote(this, FirstNote.createFirstNote());
	}
	
	/**
	 * Housekeeping when a syncServer changes
	 * @param syncServiceKey - key of the new sync service 
	 */
	private void syncServiceChanged(String syncServiceKey) {
		
		setServer(syncServiceKey);
		
		// TODO this should be refactored further, notice that setServer performs the same operations 
		SyncService service = SyncManager.getInstance().getService(syncServiceKey);
		
		if (service == null)
			return;

		// reset if no-auth required
		// I believe it's done this way because if needsAuth the database is reset when they successfully auth for the first time
		// TODO we should graphically warn the user that his database is about to be dropped
		if (!service.needsAuth()){
		    resetLocalDatabase();
		}
	}
}