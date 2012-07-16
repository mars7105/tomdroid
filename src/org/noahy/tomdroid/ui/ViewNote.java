/*
 * Tomdroid
 * Tomboy on Android
 * http://www.launchpad.net/tomdroid
 * 
 * Copyright 2008, 2009, 2010 Olivier Bilodeau <olivier@bottomlesspit.org>
 * Copyright 2009, Benoit Garret <benoit.garret_launchpad@gadz.org>
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.SpannableStringBuilder;
import android.text.util.Linkify;
import android.text.util.Linkify.TransformFilter;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import org.noahy.tomdroid.Note;
import org.noahy.tomdroid.NoteManager;
import org.noahy.tomdroid.R;
import org.noahy.tomdroid.sync.SyncManager;
import org.noahy.tomdroid.util.LinkifyPhone;
import org.noahy.tomdroid.util.NoteContentBuilder;
import org.noahy.tomdroid.util.NoteViewShortcutsHelper;
import org.noahy.tomdroid.util.Send;
import org.noahy.tomdroid.util.TLog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO this class is starting to smell
public class ViewNote extends Activity {
	public static final String CALLED_FROM_SHORTCUT_EXTRA = "org.noahy.tomdroid.CALLED_FROM_SHORTCUT";
    public static final String SHORTCUT_NAME = "org.noahy.tomdroid.SHORTCUT_NAME";

    // UI elements
	private TextView title;

	private TextView content;
    // Model objects
	private Note note;

	private SpannableStringBuilder noteContent;

	// Logging info
	private static final String TAG = "ViewNote";
    // UI feedback handler
	private Handler	syncMessageHandler	= new SyncMessageHandler(this);
	
	private Uri uri;

	// TODO extract methods in here
	@SuppressLint("NewApi")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.note_view);
		content = (TextView) findViewById(R.id.content);
		content.setBackgroundColor(0xffffffff);
		content.setTextColor(Color.DKGRAY);
		content.setTextSize(18.0f);
		title = (TextView) findViewById(R.id.title);
		title.setTextColor(Color.DKGRAY);
		title.setTextSize(18.0f);
		
		int api = Integer.parseInt(Build.VERSION.SDK);
		
		if (api >= 11) {
			title.setTextIsSelectable(true);
			content.setTextIsSelectable(true);
		}
		
		final ImageView editButton = (ImageView) findViewById(R.id.edit);
		editButton.setOnClickListener(new View.OnClickListener() {

			public void onClick(View v) {
				startEditNote();
			}
		});
		
		final ImageView deleteButton = (ImageView) findViewById(R.id.delete);        
		deleteButton.setOnClickListener(new View.OnClickListener() {

			public void onClick(View v) {
				deleteNote();
			}
		});
		
		
        uri = getIntent().getData();

        if (uri == null) {
			TLog.d(TAG, "The Intent's data was null.");
            showNoteNotFoundDialog(uri);
        } else handleNoteUri(uri);
    }

	private void handleNoteUri(final Uri uri) {// We were triggered by an Intent URI
        TLog.d(TAG, "ViewNote started: Intent-filter triggered.");

        // TODO validate the good action?
        // intent.getAction()

        // TODO verify that getNote is doing the proper validation
        note = NoteManager.getNote(this, uri);

        if(note != null) {
            noteContent = note.getNoteContent(noteContentHandler);
        } else {
            TLog.d(TAG, "The note {0} doesn't exist", uri);
            showNoteNotFoundDialog(uri);
        }
    }

    private void showNoteNotFoundDialog(final Uri uri) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        addCommonNoteNotFoundDialogElements(builder);
        addShortcutNoteNotFoundElements(uri, builder);
        builder.show();
    }

    private void addShortcutNoteNotFoundElements(final Uri uri, final AlertDialog.Builder builder) {
        final boolean proposeShortcutRemoval;
        final boolean calledFromShortcut = getIntent().getBooleanExtra(CALLED_FROM_SHORTCUT_EXTRA, false);
        final String shortcutName = getIntent().getStringExtra(SHORTCUT_NAME);
        proposeShortcutRemoval = calledFromShortcut && uri != null && shortcutName != null;

        if (proposeShortcutRemoval) {
            final Intent removeIntent = new NoteViewShortcutsHelper(this).getRemoveShortcutIntent(shortcutName, uri);
            builder.setPositiveButton(getString(R.string.btnRemoveShortcut), new OnClickListener() {
                public void onClick(final DialogInterface dialogInterface, final int i) {
                    sendBroadcast(removeIntent);
                    finish();
                }
            });
        }
    }

    private void addCommonNoteNotFoundDialogElements(final AlertDialog.Builder builder) {
        builder.setMessage(getString(R.string.messageNoteNotFound))
                .setTitle(getString(R.string.titleNoteNotFound))
                .setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        finish();
                    }
                });
    }

	@Override
	public void onResume(){
		super.onResume();
		SyncManager.setActivity(this);
		SyncManager.setHandler(this.syncMessageHandler);
		handleNoteUri(uri);
		showNote();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {

		// Create the menu based on what is defined in res/menu/noteview.xml
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.view_note, menu);
		return true;

	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {

			case R.id.view_note_send:
				(new Send(this, note)).send();
				return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void deleteNote() {
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
        		activity.finish();
            }

        })
        .setNegativeButton(R.string.no, null)
        .show();
	}

	private void showNote() {

		// show the note (spannable makes the TextView able to output styled text)
		content.setText(noteContent, TextView.BufferType.SPANNABLE);
		title.setText((CharSequence) note.getTitle());

		// add links to stuff that is understood by Android except phone numbers because it's too aggressive
		// TODO this is SLOWWWW!!!!
		Linkify.addLinks(content, Linkify.EMAIL_ADDRESSES|Linkify.WEB_URLS|Linkify.MAP_ADDRESSES);

		// Custom phone number linkifier (fixes lp:512204)
		Linkify.addLinks(content, LinkifyPhone.PHONE_PATTERN, "tel:", LinkifyPhone.sPhoneNumberMatchFilter, Linkify.sPhoneNumberTransformFilter);

		// This will create a link every time a note title is found in the text.
		// The pattern contains a very dumb (title1)|(title2) escaped correctly
		// Then we transform the url from the note name to the note id to avoid characters that mess up with the URI (ex: ?)
		Linkify.addLinks(content,
						 buildNoteLinkifyPattern(),
						 Tomdroid.CONTENT_URI+"/",
						 null,
						 noteTitleTransformFilter);
	}

	private Handler noteContentHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {

			//parsed ok - show
			if(msg.what == NoteContentBuilder.PARSE_OK) {
				showNote();

			//parsed not ok - error
			} else if(msg.what == NoteContentBuilder.PARSE_ERROR) {

				new AlertDialog.Builder(ViewNote.this)
					.setMessage(getString(R.string.messageErrorNoteParsing))
					.setTitle(getString(R.string.error))
					.setNeutralButton(getString(R.string.btnOk), new OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							dialog.dismiss();
							finish();
						}})
					.show();
        	}
		}
	};

	/**
	 * Builds a regular expression pattern that will match any of the note title currently in the collection.
	 * Useful for the Linkify to create the links to the notes.
	 * @return regexp pattern
	 */
	private Pattern buildNoteLinkifyPattern()  {

		StringBuilder sb = new StringBuilder();
		Cursor cursor = NoteManager.getTitles(this);

		// cursor must not be null and must return more than 0 entry
		if (!(cursor == null || cursor.getCount() == 0)) {

			String title;

			cursor.moveToFirst();

			do {
				title = cursor.getString(cursor.getColumnIndexOrThrow(Note.TITLE));

				// Pattern.quote() here make sure that special characters in the note's title are properly escaped
				sb.append("("+Pattern.quote(title)+")|");

			} while (cursor.moveToNext());

			// get rid of the last | that is not needed (I know, its ugly.. better idea?)
			String pt = sb.substring(0, sb.length()-1);

			// return a compiled match pattern
			return Pattern.compile(pt);

		} else {

			// TODO send an error to the user
			TLog.d(TAG, "Cursor returned null or 0 notes");
		}

		return null;
	}

	// custom transform filter that takes the note's title part of the URI and translate it into the note id
	// this was done to avoid problems with invalid characters in URI (ex: ? is the query separator but could be in a note title)
	private TransformFilter noteTitleTransformFilter = new TransformFilter() {

		public String transformUrl(Matcher m, String str) {

			int id = NoteManager.getNoteId(ViewNote.this, str);

			// return something like content://org.noahy.tomdroid.notes/notes/3
			return Tomdroid.CONTENT_URI.toString()+"/"+id;
		}
	};

    protected void startEditNote() {
		final Intent i = new Intent(Intent.ACTION_VIEW, uri, this, EditNote.class);
		startActivity(i);
	}
	
}