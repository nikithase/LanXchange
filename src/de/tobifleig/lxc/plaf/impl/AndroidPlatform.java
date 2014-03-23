/*
 * Copyright 2009, 2010, 2011, 2012, 2013 Tobias Fleig (tobifleig gmail com)
 *
 * All rights reserved.
 *
 * This file is part of LanXchange.
 *
 * LanXchange is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LanXchange is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LanXchange. If not, see <http://www.gnu.org/licenses/>.
 */
package de.tobifleig.lxc.plaf.impl;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import de.tobifleig.lxc.R;
import de.tobifleig.lxc.data.LXCFile;
import de.tobifleig.lxc.data.LXCJob;
import de.tobifleig.lxc.data.VirtualFile;
import de.tobifleig.lxc.data.impl.RealFile;
import de.tobifleig.lxc.plaf.GuiListener;
import de.tobifleig.lxc.plaf.ProgressIndicator;
import de.tobifleig.lxc.plaf.impl.android.AndroidSingleton;
import de.tobifleig.lxc.plaf.impl.android.FileListWrapper;
import de.tobifleig.lxc.plaf.impl.android.FilterProgressIndicator;
import de.tobifleig.lxc.plaf.impl.android.GuiInterfaceBridge;
import de.tobifleig.lxc.plaf.impl.android.NonFileContent;

/**
 * Platform for Android / Default Activity
 * 
 * no automated updates (managed by Google Play)
 * 
 * @author Tobias Fleig <tobifleig googlemail com>
 */
public class AndroidPlatform extends ListActivity {

    private static final int RETURNCODE_FILEINTENT = 12345;
    private LayoutInflater infl;
    private GuiListener guiListener;
    private FileListWrapper files;
    private DataSetObserver observer;
    private final ProgressIndicator noopIndicator = new ProgressIndicator() {

        @Override
        public void update(int percentage) {
            // do nothing
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check intent first
        List<Uri> quickShare = null;
        Intent launchIntent = getIntent();
        if (launchIntent.getAction() != null) {
            quickShare = computeInputIntent(launchIntent);
        }

        TextView emptyText = new TextView(this);
        emptyText.setText(R.string.nofiles);
        emptyText.setGravity(Gravity.CENTER);

        getListView().setEmptyView(emptyText);
        getListView().setLongClickable(true);
        // getListView().setPadding(20, 0, 20, 0);

        ViewGroup root = (ViewGroup) findViewById(android.R.id.content);
        root.addView(emptyText);

        infl = (LayoutInflater) getBaseContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        getListView().setAdapter(new ListAdapter() {

            @Override
            public void unregisterDataSetObserver(DataSetObserver arg0) {
                System.out.println("Observer deregistered");
                observer = null;
            }

            @Override
            public void registerDataSetObserver(DataSetObserver arg0) {
                System.out.println("Observer registered!");
                observer = arg0;
            }

            @Override
            public boolean isEmpty() {
                return guiListener == null || (files.getLocalList().isEmpty() && files.getRemoteList().isEmpty());
            }

            @Override
            public boolean hasStableIds() {
                // TODO Auto-generated method stub
                return false;
            }

            @Override
            public int getViewTypeCount() {
                // TODO Auto-generated method stub
                return 1;
            }

            @Override
            public View getView(int n, View view, ViewGroup group) {
                // insert header
                if (n == 0) {
                    TextView yourfiles = (TextView) infl.inflate(R.layout.listheader, group, false);
                    yourfiles.setText(R.string.ui_yourfiles);
                    return yourfiles;
                }
                // element own files?
                if (n <= files.getLocalList().size()) {
                    return createLocalListItem(files.getLocalList().get(n - 1), group);
                }
                // second header
                if (n == files.getLocalList().size() + 1) {
                    TextView sharedwithyou = (TextView) infl.inflate(R.layout.listheader, group, false);
                    sharedwithyou.setText(R.string.ui_sharedwithyou);
                    return sharedwithyou;
                }
                // network files
                return createRemoteListItem(files.getRemoteList().get(n - (2 + files.getLocalList().size())), group);
            }

            @Override
            public int getItemViewType(int arg0) {
                // TODO Auto-generated method stub
                return 0;
            }

            @Override
            public long getItemId(int arg0) {
                // TODO Auto-generated method stub
                return arg0;
            }

            @Override
            public Object getItem(int arg0) {
                // TODO Auto-generated method stub
                return null;
            }

            @Override
            public int getCount() {
                return guiListener == null ? 0 : (files.getLocalList().size() + files.getRemoteList().size()) + 2;
            }

            @Override
            public boolean isEnabled(int position) {
                // cannot click on category headers
                if (position == 0 || position == files.getLocalList().size() + 1) {
                    return false;
                }
                return true;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }
        });

        getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {

            @Override
            public boolean onItemLongClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
                return AndroidPlatform.this.onLongListItemClick(null, arg1, arg2, arg3);
            }
        });

        AndroidSingleton.onCreateMainActivity(this, new GuiInterfaceBridge() {

            @Override
            public void update() {
                updateGui();
            }
        }, quickShare);
    }

    private View createLocalListItem(LXCFile file, ViewGroup group) {
        View item = infl.inflate(R.layout.file_item, group, false);
        ((TextView) item.findViewById(R.id.filename)).setText(file.getShownName());
        ((TextView) item.findViewById(R.id.filesize)).setText(LXCFile.getFormattedSize(file.getFileSize()));
        // set image
        if (file.getType() == LXCFile.TYPE_FILE) {
            ((ImageView) item.findViewById(R.id.imageView1)).setImageDrawable(getResources().getDrawable(R.drawable.singlefile));
        } else if (file.getType() == LXCFile.TYPE_FOLDER) {
            ((ImageView) item.findViewById(R.id.imageView1)).setImageResource(R.drawable.folder);
        } else { // multi
            ((ImageView) item.findViewById(R.id.imageView1)).setImageResource(R.drawable.multifile);
        }
        // Status text is different for own files
        TextView statusText = (TextView) item.findViewById(R.id.TextView01);
        statusText.setText(R.string.ui_holdtoremove);
        // Override all default ProgressIndicators
        for (LXCJob job : file.getJobs()) {
            job.getTrans().setProgressIndicator(noopIndicator);
        }
        return item;
    }

    private View createRemoteListItem(LXCFile file, ViewGroup group) {
        View item = infl.inflate(R.layout.file_item, group, false);
        ((TextView) item.findViewById(R.id.filename)).setText(file.getShownName());
        ((TextView) item.findViewById(R.id.filesize)).setText(LXCFile.getFormattedSize(file.getFileSize()));
        // set image
        if (!file.isAvailable() && file.getType() == LXCFile.TYPE_FILE) {
            ((ImageView) item.findViewById(R.id.imageView1)).setImageDrawable(getResources().getDrawable(R.drawable.singlefile));
        } else if (!file.isAvailable() && file.getType() == LXCFile.TYPE_FOLDER) {
            ((ImageView) item.findViewById(R.id.imageView1)).setImageResource(R.drawable.folder);
        } else if (!file.isAvailable()) { // multi
            ((ImageView) item.findViewById(R.id.imageView1)).setImageResource(R.drawable.multifile);
        } else {
            ((ImageView) item.findViewById(R.id.imageView1)).setImageDrawable(getResources().getDrawable(R.drawable.done));
        }
        // Show status
        final ProgressBar progressBar = (ProgressBar) item.findViewById(R.id.progressBar1);
        final TextView statusText = (TextView) item.findViewById(R.id.TextView01);
        // download starting?
        if (file.isLocked() && file.getJobs().size() == 0) {
            progressBar.setVisibility(View.VISIBLE);
            statusText.setText(R.string.ui_connecting);
        } else if (!file.isAvailable() && file.getJobs().size() == 1) {
            // downloading
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(false);
            int progress = (int) (file.getJobs().get(0).getTrans().getProgress() * 100f);
            progressBar.setProgress(progress);
            statusText.setText(getResources().getString(R.string.ui_downloading) + " " + progress + "%");
            // override default ProgressIndicator
            file.getJobs().get(0).getTrans().setProgressIndicator(new FilterProgressIndicator() {
                @Override
                protected void updateGui() {
                    getListView().post(new Runnable() {
                        @Override
                        public void run() {
                            progressBar.setProgress(lastProgress);
                            statusText.setText(getResources().getString(R.string.ui_downloading) + " " + lastProgress + "%");
                        }
                    });
                }
            });
        } else if (file.isAvailable()) {
            // done
            statusText.setText(R.string.ui_available);
        }
        return item;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.lxc_layout, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.quit:
            AndroidSingleton.onRealDestroy(this);
            finish();
            return true;
        case R.id.addFile:
            // There are several methods to select a file
            // Built-in (always offered) are Music, Video and Images
            // Optional are generic files. This option is available if the
            // user has a file-browser installed:
            // Best way: User has a file-browser installed:
            final Intent fileIntent = new Intent();
            fileIntent.setAction(Intent.ACTION_GET_CONTENT);
            fileIntent.setType("file/*");
            CharSequence[] items = { "Video", "Music", "Image" };
            if (this.getPackageManager().resolveActivity(fileIntent, 0) != null) {
                // file-browser available:
                items = new CharSequence[] { "Video", "Music", "Image", "Other files" };
            }

            // AlertDialog.Builder builder = new AlertDialog.Builder(this);
            // builder.setTitle("Pick what to share:");
            // builder.setItems(items, new DialogInterface.OnClickListener() {
            // @Override
            // public void onClick(DialogInterface dialog, int item) {
            // Intent pickIntent = new Intent();
            // pickIntent.setAction(Intent.ACTION_GET_CONTENT);
            // switch (item) {
            // case 0: // Video
            // pickIntent.setType("video/*");
            // //pickIntent.setData(MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            // startActivityForResult(pickIntent, RETURNCODE_MEDIAINTENT);
            // break;
            // case 1: // Audio
            // //pickIntent.setData(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
            // pickIntent.setType("audio/*");
            // startActivityForResult(pickIntent, RETURNCODE_MEDIAINTENT);
            // break;
            // case 2: // Images
            // pickIntent.setType("image/*");
            // //pickIntent.setData(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            // startActivityForResult(pickIntent, RETURNCODE_MEDIAINTENT);
            // break;
            // case 3: // Other files
            // pickIntent.setType("file/*");
            // startActivityForResult(fileIntent, RETURNCODE_FILEINTENT);
            // break;
            // }
            //
            // }
            // });
            // AlertDialog alert = builder.create();
            // alert.show();

            Intent testIntent = new Intent();
            testIntent.setAction(Intent.ACTION_GET_CONTENT);
            testIntent.addCategory(Intent.CATEGORY_OPENABLE);
            if (android.os.Build.VERSION.SDK_INT >= 18) {
                testIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            testIntent.setType("*/*");
            startActivityForResult(testIntent, RETURNCODE_FILEINTENT);
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null) {
            // User pressed "back"/"cancel" etc
            return;
        }

        ArrayList<Uri> uris = new ArrayList<Uri>();
        // multiple files
        if (android.os.Build.VERSION.SDK_INT >= 18 && data.getData() == null && data.getClipData() != null) {
            if (data.getData() == null && data.getClipData() != null) {
                uris.addAll(urisFromClipdata(data.getClipData()));
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        offerFiles(uris);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (intent.getAction() != null) {
            List<Uri> uris = computeInputIntent(intent);
            if (uris != null && !uris.isEmpty()) {
                offerFiles(uris);
            } else {
                // cannot compute input, display error
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.error_cantoffer_title);
                builder.setMessage(R.string.error_cantoffer_text);
                builder.setPositiveButton(R.string.error_cantoffer_ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // do noting
                    }
                });
                builder.show();
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private List<Uri> urisFromClipdata(ClipData clipdata) {
        ArrayList<Uri> result = new ArrayList<Uri>();
        for (int i = 0; i < clipdata.getItemCount(); i++) {
            ClipData.Item item = clipdata.getItemAt(i);
            result.add(item.getUri());
        }
        return result;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private List<Uri> computeInputIntent(Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_SEND)) {
            Object data = intent.getExtras().get(Intent.EXTRA_STREAM);
            if (data != null && (data.toString().startsWith("file://") || data.toString().startsWith("content:"))) {
                // Make file available asap:
                ArrayList<Uri> uris = new ArrayList<Uri>();
                uris.add(Uri.parse(intent.getExtras().get(Intent.EXTRA_STREAM).toString()));
                return uris;
            }
        } else if (intent.getAction().equals(Intent.ACTION_SEND_MULTIPLE)) {
            // there is a legacy and a new way to receive multiple files
            // try the new first
            if (android.os.Build.VERSION.SDK_INT >= 16 && intent.getClipData() != null) {
                return urisFromClipdata(intent.getClipData());
            } else if (intent.getStringArrayListExtra(Intent.EXTRA_STREAM) != null) {
                ArrayList<Uri> uris = new ArrayList<Uri>();
                ArrayList<String> uriStrings = intent.getStringArrayListExtra(Intent.EXTRA_STREAM);
                for (String uriString : uriStrings) {
                    uris.add(Uri.parse(uriString));
                }
                return uris;
            }
        }
        return null;
    }

    /**
     * Offers a file.
     * 
     * @param path
     *            the absolute path
     */
    private void offerFiles(List<Uri> uris) {
        System.out.println("First uri string is " + uris.get(0).toString());

        List<VirtualFile> list = new ArrayList<VirtualFile>();
        for (Uri uri : uris) {
            VirtualFile virtualFile = uriToVirtualFile(uri);
            if (virtualFile != null) {
                list.add(virtualFile);
            }
        }

        // we tried everything
        if (list.isEmpty()) {
            System.err.println("invalid input!");
            return;
        }

        LXCFile lxcfile = new LXCFile(list, list.get(0).getName());
        guiListener.offerFile(lxcfile);
    }

    private VirtualFile uriToVirtualFile(Uri uri) {
        String uriString = uri.toString();
        VirtualFile file = null;
        // Handle kitkat files
        if (uriString.startsWith("content://")) {
            ContentResolver resolver = getBaseContext().getContentResolver();
            // get file name
            String[] proj = { MediaStore.Files.FileColumns.DISPLAY_NAME };
            Cursor cursor = resolver.query(uri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
            cursor.moveToFirst();
            String name = cursor.getString(column_index);
            try {
                ParcelFileDescriptor desc = resolver.openFileDescriptor(uri, "r");
                file = new NonFileContent(name, desc, uri, resolver);

            } catch (FileNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else if (uriString.startsWith("file://")) {
            // seems to be useable right away
            file = new RealFile(new File(uriString.substring(8))); // just strip
            // "file://"
        }

        // one last trick
        if (file == null) {
            File resolvedFile = new File(uri.getPath());
            if (resolvedFile.exists()) {
                file = new RealFile(resolvedFile);
            }
            // filePath.substring(filePath.indexOf('/'))
        }
        return file;
    }

    private String getRealPathFromURI(Context context, Uri contentUri) {
        Cursor cursor = null;
        String result = null;
        try {
            String[] proj = { MediaStore.Images.Media.DATA };
            cursor = context.getContentResolver().query(contentUri, proj, null, null, null);
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            result = cursor.getString(column_index);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result;
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        // only clicks to second list for now
        if (position >= files.getLocalList().size() + 2) {
            final LXCFile file = files.getRemoteList().get(position - files.getLocalList().size() - 2);
            if (!file.isLocal() && !file.isAvailable()) {
                file.setLocked(true);
                updateGui();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        guiListener.downloadFile(file, false);
                    }
                });
                t.setName("lxc_helper_initdl_" + file.getShownName());
                t.setDaemon(true);
                t.start();
            } else if (!file.isLocal() && file.isAvailable()) {
                // open file
                Intent openIntent = new Intent();
                openIntent.setAction(Intent.ACTION_VIEW);
                // Hack: Local files are RealFiles
                RealFile realFile = (RealFile) file.getFiles().get(0);
                Uri fileUri = Uri.fromFile(realFile.getBackingFile());
                String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        MimeTypeMap.getFileExtensionFromUrl(realFile.getBackingFile().getAbsolutePath()));
                openIntent.setDataAndType(fileUri, mimeType);
                System.out.println("Starting intent for uri " + fileUri + " mimeType is " + mimeType);
                // check if intent can be processed
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(openIntent, 0);
                if (list.isEmpty()) {
                    // cannot be opened
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.error_cantopen_title);
                    builder.setMessage(R.string.error_cantopen_text);
                    builder.setPositiveButton(R.string.error_cantopen_ok, new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // do noting
                        }
                    });
                    builder.show();
                } else {
                    startActivity(openIntent);
                }
            }
        }
    }

    public boolean onLongListItemClick(ListView l, View v, int position, long id) {
        // clicks for first list
        if (position != 0 && position <= files.getLocalList().size()) {
            final LXCFile file = files.getLocalList().get(position - 1);
            guiListener.removeFile(file);
            updateGui();
            return true;
        } else if (position >= files.getLocalList().size() + 2) {
            // clicks for second list, only valid for downloaded files
            final LXCFile file = files.getRemoteList().get(position - files.getLocalList().size() - 2);
            if (file.isAvailable()) {
                guiListener.resetFile(file);
                updateGui();
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the GuiListener. Will be called by AndroidSingleton when LXC is
     * ready. If this Activity has been recreated and LXC is still running,
     * AndroidSingleton calls this within onCreateMainActivity
     * 
     * @param guiListener
     *            out future GuiListener
     */
    public void setGuiListener(GuiListener guiListener) {
        files = new FileListWrapper(guiListener.getFileList());
        this.guiListener = guiListener;
        updateGui();
    }

    private void updateGui() {
        getListView().post(new Runnable() {

            @Override
            public void run() {
                if (observer != null) {
                    files.listChanged();
                    observer.onChanged();
                }
            }
        });
    }

    /**
     * When this activity is started with an ACTION_SEND Intent, the path of the
     * file to share will end up here.
     * 
     * @param uris a list of Uris to share
     */
    public void quickShare(List<Uri> uris) {
        offerFiles(uris);
    }
}
