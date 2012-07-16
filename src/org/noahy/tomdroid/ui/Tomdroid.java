/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2009, 2010, 2011 Olivier Bilodeau <olivier@bottomlesspit.org>
 * Copyright 2009, Benoit Garret <benoit.garret_launchpad@gadz.org>
 * Copyright 2010, Rodja Trappe <mail@rodja.net>
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

import org.noahy.tomdroid.Note;
import org.noahy.tomdroid.NoteManager;
import org.noahy.tomdroid.R;
import org.noahy.tomdroid.sync.ServiceAuth;
import org.noahy.tomdroid.sync.SyncManager;
import org.noahy.tomdroid.sync.SyncService;
import org.noahy.tomdroid.util.FirstNote;
import org.noahy.tomdroid.util.NewNote;
import org.noahy.tomdroid.util.NoteViewShortcutsHelper;
import org.noahy.tomdroid.util.Preferences;
import org.noahy.tomdroid.util.Send;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.*;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import org.noahy.tomdroid.util.TLog;

public class Tomdroid extends ListActivity {

	// Global definition for Tomdroid
	public static final String	AUTHORITY			= "org.noahy.tomdroid.notes";
	public static final Uri		CONTENT_URI			= Uri.parse("content://" + AUTHORITY + "/notes");
	public static final String	CONTENT_TYPE		= "vnd.android.cursor.dir/vnd.tomdroid.note";
	public static final String	CONTENT_ITEM_TYPE	= "vnd.android.cursor.item/vnd.tomdroid.note";
	public static final String	PROJECT_HOMEPAGE	= "http://www.launchpad.net/tomdroid/";

	// config parameters
	public static String	NOTES_PATH				= null;
	
	// Set this to false for release builds, the reason should be obvious
	public static final boolean	CLEAR_PREFERENCES	= false;

	// Logging info
	private static final String	TAG					= "Tomdroid";

    public static Uri getNoteIntentUri(long noteId) {
        return Uri.parse(CONTENT_URI + "/" + noteId);
    }

	// UI to data model glue
	private TextView			listEmptyView;
	private ListAdapter			adapter;

	// UI feedback handler
	private Handler	syncMessageHandler	= new SyncMessageHandler(this);
	
	/** Called when the activity is created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.main);
		Preferences.init(this, CLEAR_PREFERENCES);

		// get the Path to the notes-folder from Preferences
		NOTES_PATH = Environment.getExternalStorageDirectory()
				+ "/" + Preferences.getString(Preferences.Key.SD_LOCATION) + "/";
		
		// did we already show the warning and got destroyed by android's activity killer?
		if (Preferences.getBoolean(Preferences.Key.FIRST_RUN)) {
			TLog.i(TAG, "Tomdroid is first run.");
			
			// add a first explanatory note
			NoteManager.putNote(this, FirstNote.createFirstNote());
			
			// Warn that this is a "will eat your babies" release
			new AlertDialog.Builder(this).setMessage(getString(R.string.strWelcome)).setTitle(
					getString(R.string.titleWelcome)).setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					Preferences.putBoolean(Preferences.Key.FIRST_RUN, false);
					dialog.dismiss();
				}
			}).setIcon(R.drawable.icon).show();
		}
		
		final ImageView newButton = (ImageView) findViewById(R.id.new_item);
		newButton.setOnClickListener(new View.OnClickListener() {

			public void onClick(View v) {
				newNote();
			}
		});
		
		// adapter that binds the ListView UI to the notes in the note manager
		adapter = NoteManager.getListAdapter(this);
		setListAdapter(adapter);

		// set the view shown when the list is empty
		listEmptyView = (TextView) findViewById(R.id.list_empty);
		getListView().setEmptyView(listEmptyView);
		
		registerForContextMenu(findViewById(android.R.id.list));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		// Create the menu based on what is defined in res/menu/main.xml
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main, menu);
		return true;

	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menuAbout:
				showAboutDialog();
				return true;

			case R.id.menuPrefs:
				startActivity(new Intent(this, PreferencesActivity.class));
				return true;
				
			case R.id.menuSearch:
				startSearch(null, false, null, false);
				return true;
		}

		return super.onOptionsItemSelected(item);
	}
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		MenuInflater inflater = getMenuInflater();
	    inflater.inflate(R.menu.main_longclick, menu);
	    menu.setHeaderTitle(getString(R.string.noteOptions));
		super.onCreateContextMenu(menu, v, menuInfo);
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo)item.getMenuInfo();
		long noteId = info.id;
		Uri intentUri = Uri.parse(Tomdroid.CONTENT_URI+"/"+noteId);
        Note note = NoteManager.getNote(this, intentUri);

        switch (item.getItemId()) {
            case R.id.menu_send:
                (new Send(this, note)).send();
				break;
			case R.id.view:
				this.ViewNote(noteId);
				break;
			case R.id.delete:
				this.deleteNote(noteId);
				break;
			case R.id.create_shortcut:
                final NoteViewShortcutsHelper helper = new NoteViewShortcutsHelper(this);
                sendBroadcast(helper.getBroadcastableCreateShortcutIntent(intentUri, note.getTitle()));
                break;
            default:
				break;
		}
		
		return super.onContextItemSelected(item);
	}

	public void onResume() {
		super.onResume();
		Intent intent = this.getIntent();

		SyncService currentService = SyncManager.getInstance().getCurrentService();
		
		if (currentService.needsAuth() && intent != null) {
			Uri uri = intent.getData();

			if (uri != null && uri.getScheme().equals("tomdroid")) {
				TLog.i(TAG, "Got url : {0}", uri.toString());

				final ProgressDialog dialog = ProgressDialog.show(this, "",	getString(R.string.prefSyncCompleteAuth), true, false);

				Handler handler = new Handler() {

					@Override
					public void handleMessage(Message msg) {
						dialog.dismiss();
					}

				};

				((ServiceAuth) currentService).remoteAuthComplete(uri, handler);
			}
		}
		
		SyncManager.setActivity(this);
		SyncManager.setHandler(this.syncMessageHandler);
	}

	private void showAboutDialog() {

		// grab version info
		String ver;
		try {
			ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		} catch (NameNotFoundException e) {
			e.printStackTrace();
			ver = "Not found!";
		}

		// format the string
		String aboutDialogFormat = getString(R.string.strAbout);
		String aboutDialogStr = String.format(aboutDialogFormat, getString(R.string.app_desc), // App description
				getString(R.string.author), // Author name
				ver // Version
				);

		// build and show the dialog
		new AlertDialog.Builder(this).setMessage(aboutDialogStr).setTitle(getString(R.string.titleAbout))
				.setIcon(R.drawable.icon).setNegativeButton(getString(R.string.btnProjectPage), new OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						startActivity(new Intent(Intent.ACTION_VIEW, Uri
								.parse(Tomdroid.PROJECT_HOMEPAGE)));
						dialog.dismiss();
					}
				}).setPositiveButton(getString(R.string.btnOk), new OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
					}
				}).show();
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {

		Cursor item = (Cursor) adapter.getItem(position);
		long noteId = item.getInt(item.getColumnIndexOrThrow(Note.ID));
			this.ViewNote(noteId);
		
	}
	
	public void ViewNote(long noteId) {
		Uri intentUri = getNoteIntentUri(noteId);
		Intent i = new Intent(Intent.ACTION_VIEW, intentUri, this, ViewNote.class);
		startActivity(i);
	}
	
	public static void ViewList(Context View) {
		if ( ! ( View instanceof Tomdroid ) )
	    {
			View.startActivity(new Intent(View, Tomdroid.class)
			.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
	    }
	}

	public void newNote() {
		
		// add a new note
		
		Note note = NewNote.createNewNote(this);
		Uri uri = NoteManager.putNote(this, note);

		// put note to server
		
		SyncManager.getInstance().pushNote(note);

		// view new note
		
		Intent i = new Intent(Intent.ACTION_VIEW, uri, this, EditNote.class);
		startActivity(i);

		
	}
	private void deleteNote(long noteId) {
		
		final Note note = NoteManager.getNote(this, Uri.parse(Tomdroid.CONTENT_URI + "/" + noteId));
		
		final Activity activity = this;
		new AlertDialog.Builder(this)
        .setIcon(android.R.drawable.ic_dialog_alert)
        .setTitle(R.string.delete_note)
        .setMessage(R.string.delete_message)
        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
        		String guid = note.getGuid();
        		NoteManager.deleteNote(activity, note.getDbId());
        		
        		// delete note from server
        		SyncManager.getInstance().deleteNote(guid);
            }

        })
        .setNegativeButton(R.string.no, null)
        .show();
	}
}