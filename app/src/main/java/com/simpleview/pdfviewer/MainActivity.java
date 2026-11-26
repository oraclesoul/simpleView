package com.simpleview.pdfviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.Toolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQUEST_OPEN = 1;
    private static final String STATE_URI = "uri";
    private static final String STATE_PAGE = "page";
    private static final String PREFS = "simpleview";
    private static final String PREF_INVERTED = "inverted";

    private static final int ERROR_NONE = 0;
    private static final int ERROR_PASSWORD = 1;
    private static final int ERROR_OPEN = 2;

    /** Result of opening a document on the background thread. */
    private static final class Loaded {
        PdfRenderer renderer;
        float[] widths;
        float[] heights;
        File tempFile;
        String name;
        int error = ERROR_NONE;

        void discard() {
            if (renderer != null) {
                renderer.close();
            }
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PdfView pdfView;
    private View emptyView;
    private View progress;
    private SharedPreferences prefs;

    private Uri currentUri;
    private File currentTemp;
    private int loadToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        pdfView = findViewById(R.id.pdf_view);
        emptyView = findViewById(R.id.empty_view);
        progress = findViewById(R.id.progress);
        pdfView.setInverted(prefs.getBoolean(PREF_INVERTED, false));
        findViewById(R.id.open_button).setOnClickListener(v -> pickDocument());

        // Targeting API 35+ draws edge-to-edge: the toolbar extends under the status bar, the sides avoid cutouts,
        // and pages scroll clear of the navigation bar.
        final Toolbar toolbar = findViewById(R.id.toolbar);
        setActionBar(toolbar);
        TypedValue barSize = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.actionBarSize, barSize, true);
        final int barHeight = TypedValue.complexToDimensionPixelSize(barSize.data, getResources().getDisplayMetrics());
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, 0);
            toolbar.setPadding(toolbar.getPaddingLeft(), bars.top, toolbar.getPaddingRight(), 0);
            toolbar.getLayoutParams().height = barHeight + bars.top;
            toolbar.requestLayout();
            pdfView.setBottomInset(bars.bottom);
            return insets;
        });

        String savedUri = savedInstanceState != null ? savedInstanceState.getString(STATE_URI) : null;
        if (savedUri != null) {
            load(Uri.parse(savedUri), null, savedInstanceState.getInt(STATE_PAGE, 0));
        } else {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (currentUri != null) {
            outState.putString(STATE_URI, currentUri.toString());
            outState.putInt(STATE_PAGE, pdfView.getCurrentPage());
        }
    }

    @Override
    protected void onDestroy() {
        loadToken++;
        pdfView.close();
        io.shutdown();
        if (currentTemp != null) {
            currentTemp.delete();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_go_to_page).setEnabled(pdfView.getPageCount() > 1);
        menu.findItem(R.id.action_invert).setChecked(prefs.getBoolean(PREF_INVERTED, false));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) {
            pickDocument();
        } else if (id == R.id.action_go_to_page) {
            askPage();
        } else if (id == R.id.action_invert) {
            boolean inverted = !item.isChecked();
            prefs.edit().putBoolean(PREF_INVERTED, inverted).apply();
            pdfView.setInverted(inverted);
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    // ---------------------------------------------------------------- opening documents

    private void pickDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/pdf");
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Not every provider offers persistable grants; the temporary grant is enough.
            }
            load(uri, null, 0);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        if (uri != null) {
            load(uri, null, 0);
        }
    }

    private void load(Uri uri, String password, int startPage) {
        final int token = ++loadToken;
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            Loaded loaded = new Loaded();
            try {
                open(uri, password, loaded);
            } catch (SecurityException e) {
                loaded.discard();
                loaded = new Loaded();
                loaded.error = ERROR_PASSWORD;
            } catch (Exception e) {
                loaded.discard();
                loaded = new Loaded();
                loaded.error = ERROR_OPEN;
            }
            final Loaded result = loaded;
            runOnUiThread(() -> onLoaded(token, uri, password != null, startPage, result));
        });
    }

    private void onLoaded(int token, Uri uri, boolean passwordWasTried, int startPage, Loaded loaded) {
        if (token != loadToken || isDestroyed()) {
            loaded.discard();
            return;
        }
        progress.setVisibility(View.GONE);

        if (loaded.error == ERROR_PASSWORD) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                askPassword(uri, startPage, passwordWasTried);
            } else {
                toast(R.string.error_password_unsupported);
            }
            return;
        }
        if (loaded.error != ERROR_NONE) {
            toast(R.string.error_open);
            return;
        }

        pdfView.setDocument(loaded.renderer, loaded.widths, loaded.heights, startPage);
        if (currentTemp != null) {
            currentTemp.delete();
        }
        currentTemp = loaded.tempFile;
        currentUri = uri;
        setTitle(loaded.name != null ? loaded.name : getString(R.string.app_name));
        emptyView.setVisibility(View.GONE);
        pdfView.setVisibility(View.VISIBLE);
        invalidateOptionsMenu();
    }

    /** Runs on the background thread; fills {@code out} or throws. */
    private void open(Uri uri, String password, Loaded out) throws IOException {
        ParcelFileDescriptor pfd;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
        } catch (SecurityException e) {
            // Permission problems with the file itself are not a PDF password issue.
            throw new IOException(e);
        }
        if (pfd == null) {
            throw new IOException("Cannot open " + uri);
        }
        out.name = displayName(uri);

        // PdfRenderer needs a seekable descriptor; some providers (e.g. mail attachments) give a pipe.
        if (!isSeekable(pfd)) {
            File temp = copyToCache(uri);
            out.tempFile = temp;
            pfd.close();
            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        try {
            out.renderer = createRenderer(pfd, password);
        } catch (IOException | RuntimeException e) {
            closeQuietly(pfd);
            throw e;
        }

        int count = out.renderer.getPageCount();
        if (count <= 0) {
            throw new IOException("Empty document");
        }
        out.widths = new float[count];
        out.heights = new float[count];
        for (int i = 0; i < count; i++) {
            try (PdfRenderer.Page page = out.renderer.openPage(i)) {
                out.widths[i] = Math.max(1, page.getWidth());
                out.heights[i] = Math.max(1, page.getHeight());
            }
        }
    }

    private static PdfRenderer createRenderer(ParcelFileDescriptor pfd, String password) throws IOException {
        if (password != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return new PdfRenderer(pfd, new LoadParams.Builder().setPassword(password).build());
        }
        return new PdfRenderer(pfd);
    }

    private static boolean isSeekable(ParcelFileDescriptor pfd) {
        try {
            Os.lseek(pfd.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException e) {
            return false;
        }
    }

    private File copyToCache(Uri uri) throws IOException {
        File file = File.createTempFile("doc", ".pdf", getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream outStream = new FileOutputStream(file)) {
            if (in == null) {
                throw new IOException("Cannot read " + uri);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                outStream.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            file.delete();
            throw e;
        }
        return file;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // The title is cosmetic.
        }
        return null;
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        try {
            pfd.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }

    // ---------------------------------------------------------------- dialogs

    private void askPassword(Uri uri, int startPage, boolean wrongPassword) {
        final EditText input = new EditText(this);
        input.setHint(R.string.password_hint);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.password_title)
                .setMessage(wrongPassword ? getString(R.string.error_wrong_password) : null)
                .setView(input)
                .setPositiveButton(R.string.unlock,
                        (d, which) -> load(uri, input.getText().toString(), startPage))
                .setNegativeButton(R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private void askPage() {
        final int count = pdfView.getPageCount();
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.go_to_page_hint, count));
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.go_to_page_title)
                .setView(input)
                .setPositiveButton(R.string.go, (d, which) -> goToTypedPage(input, count))
                .setNegativeButton(R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private void goToTypedPage(EditText input, int count) {
        try {
            int page = Integer.parseInt(input.getText().toString().trim());
            pdfView.goToPage(Math.max(1, Math.min(count, page)) - 1);
        } catch (NumberFormatException ignored) {
            // Empty or oversized input: stay where we are.
        }
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
