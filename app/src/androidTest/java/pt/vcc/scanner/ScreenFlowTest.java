package pt.vcc.scanner;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/**
 * Drives the real screens of {@link MainActivity}: the document list, the detail screen with the
 * in document search, the inline page editor, the filter strip and the page reordering. The views
 * are built in code and have no identifiers, so they are located by text and content description.
 */
@RunWith(AndroidJUnit4.class)
public class ScreenFlowTest {
    private static final String TITLE = "Documento de interface";

    @Test public void listOpensDetailEditorFilterAndReorder() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DocumentEngine engine = new DocumentEngine(context);
        ScannerDatabase db = Room.databaseBuilder(context, ScannerDatabase.class, "vcc-scanner.db").addMigrations(ScannerDatabase.MIGRATION_1_2, ScannerDatabase.MIGRATION_2_3).build();
        Document doc = engine.create();
        File first = fixture(context, "ui-page-1.png", "FATURA", "Empresa XYZ, Lda.", "NIF: 501234567", "Total: 19,70 EUR");
        File second = fixture(context, "ui-page-2.png", "SEGUNDA PAGINA");
        try {
            engine.importUris(doc, List.of(Uri.fromFile(first), Uri.fromFile(second)));
            assertEquals(2, engine.pages(doc).length());
            doc.title = TITLE; db.documents().save(doc);
            List<String> imported = paths(doc);

            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                tap(scenario, described(context.getString(R.string.cd_open_document, TITLE)), "document card");
                seen(scenario, text(context.getString(R.string.detail_eyebrow)), "detail screen");
                seen(scenario, text(context.getString(R.string.detail_reorder_hint)), "reorder hint");

                act(scenario, hint(context.getString(R.string.detail_search_hint)), field -> ((EditText) field).setText("501234567"), "search field");
                seen(scenario, text(context.getResources().getQuantityString(R.plurals.detail_search_matches, 1, 1)), "one search match");
                act(scenario, hint(context.getString(R.string.detail_search_hint)), field -> ((EditText) field).setText("inexistente"), "search field");
                seen(scenario, text(context.getString(R.string.detail_search_none)), "empty search result");

                tap(scenario, text(context.getString(R.string.detail_adjust)), "adjust page");
                seen(scenario, text(context.getString(R.string.page_title, 1)), "page editor");
                seen(scenario, text(context.getString(R.string.editor_filters)), "filter strip");

                tap(scenario, described(context.getString(R.string.cd_page_filter, "Escala de cinzentos")), "grayscale filter");
                Document filtered = until(db, changed -> !changed.pages.equals(doc.pages), "the filter to be written");
                assertNotEquals(imported, paths(filtered));
                assertTrue(new File(paths(filtered).get(0)).exists());
                seen(scenario, text(context.getString(R.string.editor_order)), "editor after the filter");

                tap(scenario, text(context.getString(R.string.editor_move_down)), "move page down");
                Document reordered = until(db, changed -> {
                    try { return paths(changed).get(1).equals(paths(filtered).get(0)); } catch (Exception broken) { return false; }
                }, "the new page order");
                assertEquals(paths(filtered).get(1), paths(reordered).get(0));
                seen(scenario, text(context.getString(R.string.detail_eyebrow)), "detail screen after reordering");

                scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
                seen(scenario, text(context.getString(R.string.home_eyebrow)), "home screen");
                seen(scenario, view -> view.getContentDescription() != null && view.getContentDescription().toString().startsWith("Categoria Todos,"), "category chip with a count");
                seen(scenario, described(context.getString(R.string.cd_document_preview, TITLE)), "list thumbnail");
            }
        } finally {
            Document stored = null; for (Document d : db.documents().all()) if (d.id.equals(doc.id)) stored = d;
            if (stored != null) db.documents().delete(stored);
            db.close(); engine.deleteFiles(doc); first.delete(); second.delete();
        }
    }

    /** Runs an action on the first matching view, retrying until the screen shows it. */
    private static void act(ActivityScenario<MainActivity> scenario, Predicate<View> match, Consumer<View> action, String what) throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            boolean[] done = {false};
            scenario.onActivity(activity -> { View view = find(activity.getWindow().getDecorView(), match); if (view != null) { action.accept(view); done[0] = true; } });
            if (done[0]) return;
            Thread.sleep(200);
        }
        fail("Timed out waiting for " + what);
    }
    private static void tap(ActivityScenario<MainActivity> scenario, Predicate<View> match, String what) throws Exception {
        act(scenario, match, view -> assertTrue("Nothing handled the tap on " + what, view.performClick()), what);
    }
    private static void seen(ActivityScenario<MainActivity> scenario, Predicate<View> match, String what) throws Exception {
        act(scenario, match, view -> {}, what);
    }
    /** Reads the document back from the database until the activity has written the expected change. */
    private static Document until(ScannerDatabase db, Predicate<Document> match, String what) throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            for (Document d : db.documents().all()) if (TITLE.equals(d.title) && match.test(d)) return d;
            Thread.sleep(200);
        }
        fail("Timed out waiting for " + what);
        return null;
    }
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) { View found = find(group.getChildAt(i), match); if (found != null) return found; } }
        return null;
    }
    private static Predicate<View> text(String value) { return view -> view instanceof TextView && value.contentEquals(((TextView) view).getText()); }
    private static Predicate<View> hint(String value) { return view -> view instanceof EditText && ((EditText) view).getHint() != null && value.contentEquals(((EditText) view).getHint()); }
    private static Predicate<View> described(String value) { return view -> view.getContentDescription() != null && value.contentEquals(view.getContentDescription()); }
    private static List<String> paths(Document d) throws Exception {
        JSONArray pages = new JSONArray(d.pages); List<String> list = new ArrayList<>();
        for (int i = 0; i < pages.length(); i++) list.add(pages.getJSONObject(i).getString("path"));
        return list;
    }
    private static File fixture(Context context, String name, String... lines) throws Exception {
        File file = new File(context.getCacheDir(), name); Bitmap bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888);
        try { Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTextSize(48); paint.setColor(Color.BLACK);
            for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], 100, 180 + i * 100, paint);
            try (OutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        } finally { bitmap.recycle(); }
        return file;
    }
}
