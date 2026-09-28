package pt.vcc.scanner;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.core.view.*;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.room.Room;
import com.google.mlkit.vision.documentscanner.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends ComponentActivity {
    // MUTED is darkened to 5.9:1 against the white cards and 5.5:1 against the background, above the
    // WCAG AA 4.5:1 floor for the 12-15sp secondary text it is used for.
    private static final int INK = Color.rgb(25,40,55), GREEN = Color.rgb(23,107,88), MUTED = Color.rgb(90,102,115), BG = Color.rgb(245,247,249);
    /** Canonical category keys persisted in the database; CATEGORY_LABELS holds what the user sees. */
    private static final String CATEGORY_ALL = "Todos";
    private static final String[] CATEGORIES = {CATEGORY_ALL, "Faturas", "Recibos", "Contratos", "Manuais", "Pessoais", "Outros"};
    private static final int[] CATEGORY_LABELS = {R.string.category_all, R.string.category_invoices, R.string.category_receipts, R.string.category_contracts, R.string.category_manuals, R.string.category_personal, R.string.category_other};
    /** Page filters in the order of the R.array.page_actions labels, decoupled from those labels. */
    private static final String[] PAGE_ACTIONS = {DocumentEngine.ACTION_ROTATE, DocumentEngine.ACTION_ORIGINAL, DocumentEngine.ACTION_GRAYSCALE, DocumentEngine.ACTION_MONOCHROME, DocumentEngine.ACTION_DOCUMENT};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScannerDatabase db; private DocumentEngine engine;
    private LinearLayout root, body, results; private TextView status;
    private List<Document> documents = new ArrayList<>(); private Document selected;
    private String category = CATEGORY_ALL, query = "", appendId, restoreId;
    private String filterCompany="",filterDate="",filterTotal="",filterTags="";
    private int searchGeneration;
    private boolean busy; private File exportFile; private String exportMime;
    private ActivityResultLauncher<IntentSenderRequest> scanner;
    private ActivityResultLauncher<String[]> importer;
    private ActivityResultLauncher<Intent> saver;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = Room.databaseBuilder(getApplicationContext(), ScannerDatabase.class, "vcc-scanner.db").addMigrations(ScannerDatabase.MIGRATION_1_2, ScannerDatabase.MIGRATION_2_3).build(); engine = new DocumentEngine(this);
        if (state != null) { appendId = state.getString("appendId"); restoreId = state.getString("selectedId"); category = state.getString("category", CATEGORY_ALL); query = state.getString("query", ""); String path=state.getString("export"); if(path!=null) exportFile=new File(path); exportMime=state.getString("mime"); }
        scanner = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                GmsDocumentScanningResult scan = GmsDocumentScanningResult.fromActivityResultIntent(result.getData());
                if (scan != null && scan.getPages() != null) { List<Uri> uris = new ArrayList<>(); for (var page : scan.getPages()) uris.add(page.getImageUri()); importFiles(uris); }
            }
        });
        importer = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> { if (!uris.isEmpty()) importFiles(uris); });
        saver = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null && exportFile != null) {
                Uri uri = result.getData().getData(); File source=exportFile;
                run(getString(R.string.status_saving_file), () -> { try(InputStream in=new FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(uri)) { if(out==null)throw new IOException(getString(R.string.error_destination_unavailable)); copy(in,out); } }, () -> toast(R.string.toast_file_saved));
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { if (busy) { toast(R.string.toast_busy); return; } if(selected!=null) { selected=null; home(); } else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); } } });
        home(); reload();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("selectedId", selected == null ? null : selected.id); state.putString("appendId", appendId); state.putString("category",category); state.putString("query",query);
        if(exportFile!=null)state.putString("export",exportFile.getAbsolutePath()); state.putString("mime",exportMime);
    }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdown(); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String value,int size,int color) { TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(5),0,dp(5)); return v; }
    private TextView heading(String value,int size) { TextView v=label(value,size,INK); v.setTypeface(null,Typeface.BOLD); return v; }
    private void gap(LinearLayout parent,int height) { View v=new View(this); parent.addView(v,new LinearLayout.LayoutParams(1,dp(height))); }
    private LinearLayout card(LinearLayout parent) { LinearLayout c=column(); c.setPadding(dp(18),dp(14),dp(18),dp(14)); c.setBackground(shape(Color.WHITE,18)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(6),0,dp(8)); parent.addView(c,lp); return c; }
    private Button button(LinearLayout parent,String text,boolean primary,Runnable action) {
        Button b=new Button(this); b.setText(text); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(primary?Color.WHITE:GREEN); b.setBackground(shape(primary?GREEN:Color.rgb(230,242,237),12)); b.setPadding(dp(12),dp(8),dp(12),dp(8)); b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(5),0,dp(5)); parent.addView(b,lp); b.setOnClickListener(v->{if(!busy)action.run();else toast(R.string.toast_busy);}); return b;
    }
    private EditText input(LinearLayout parent,String hint,String value,boolean multiline) { EditText e=new EditText(this); e.setTextSize(15); e.setTextColor(INK); e.setHint(hint); e.setText(value); e.setSingleLine(!multiline); e.setPadding(dp(12),dp(12),dp(12),dp(12)); if(multiline)e.setMinLines(3); parent.addView(e,new LinearLayout.LayoutParams(-1,-2)); return e; }
    private void layout(String eyebrow,String title,String subtitle) {
        root=column(); root.setBackgroundColor(BG); root.setPadding(dp(22),dp(8),dp(22),0); setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{var bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.ime());v.setPadding(dp(22)+bars.left,dp(8)+bars.top,dp(22)+bars.right,bars.bottom);return insets;});
        TextView brand=label(eyebrow,12,GREEN); brand.setLetterSpacing(.13f); brand.setTypeface(null,Typeface.BOLD); root.addView(brand);
        root.addView(heading(title,30)); if(subtitle!=null)root.addView(label(subtitle,14,MUTED));
        status=label(busy?getString(R.string.status_processing):"",13,GREEN); status.setVisibility(busy?View.VISIBLE:View.GONE); root.addView(status);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); body=column(); body.setPadding(0,dp(12),0,dp(20)); scroll.addView(body);
    }
    private void home() {
        selected=null; layout(getString(R.string.home_eyebrow), getString(R.string.home_title), getString(R.string.home_subtitle));
        LinearLayout hero=card(body); hero.setBackground(shape(Color.rgb(219,238,228),20)); hero.addView(heading(getString(R.string.home_hero_title),20)); hero.addView(label(getString(R.string.home_hero_body),14,GREEN)); button(hero,getString(R.string.home_new_scan),true,()->newDocument(false));
        EditText search=input(body,getString(R.string.home_search_hint),query,false);
        button(body,getString(hasFilters()?R.string.home_filters_active:R.string.home_filters_idle),false,this::filters);
        HorizontalScrollView chips=new HorizontalScrollView(this); chips.setHorizontalScrollBarEnabled(false); LinearLayout row=new LinearLayout(this); chips.addView(row); body.addView(chips);
        for(String cat:CATEGORIES) { boolean current=cat.equals(category); TextView chip=chip(categoryLabel(cat),current); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,dp(10),dp(6),dp(10));row.addView(chip,lp);chip.setOnClickListener(v->{category=cat;home();}); }
        LinearLayout summary=new LinearLayout(this); TextView title=heading(getString(R.string.home_documents),19);summary.addView(title,new LinearLayout.LayoutParams(0,-2,1));summary.addView(label(getResources().getQuantityString(R.plurals.home_documents_on_device,documents.size(),documents.size()),12,MUTED));body.addView(summary);
        results=column();body.addView(results);renderResults();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();renderResults();}public void afterTextChanged(Editable e){}});
        gap(body,14);body.addView(label(getString(R.string.home_privacy),12,MUTED));
    }
    /** Category filter: a TextView that TalkBack must announce as a selectable button, not as plain text. */
    private TextView chip(String text,boolean current) {
        TextView chip=label(text,13,current?Color.WHITE:GREEN);
        chip.setPadding(dp(14),dp(10),dp(14),dp(10)); chip.setBackground(shape(current?GREEN:Color.rgb(231,238,235),20));
        chip.setGravity(Gravity.CENTER); chip.setMinHeight(dp(48)); chip.setMinWidth(dp(48));
        chip.setClickable(true); chip.setFocusable(true);
        chip.setContentDescription(getString(R.string.cd_category_filter,text));
        ViewCompat.setAccessibilityDelegate(chip,new AccessibilityDelegateCompat(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfoCompat info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setClassName(Button.class.getName()); info.setCheckable(true); info.setChecked(current);
            }
        });
        return chip;
    }
    private String categoryLabel(String value) {
        for(int i=0;i<CATEGORIES.length;i++) if(CATEGORIES[i].equals(value)) return getString(CATEGORY_LABELS[i]);
        return value;
    }
    private void renderResults() {
        if(results==null)return;int generation=++searchGeneration;String fts=Metadata.ftsQuery(query);
        if(fts.isEmpty()){renderDocuments(documents);return;}
        worker.execute(()->{try{List<Document> found=db.documents().search(fts);runOnUiThread(()->{if(!isDestroyed()&&selected==null&&generation==searchGeneration)renderDocuments(found);});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&generation==searchGeneration)error(R.string.error_search_failed);});}});
    }
    private void renderDocuments(List<Document> found){
        results.removeAllViews(); int count=0; Locale locale=new Locale("pt","PT");
        for(Document d:found) if((category.equals(CATEGORY_ALL)||category.equals(d.category))&&contains(d.company,filterCompany)&&contains(d.date,filterDate)&&contains(d.total,filterTotal)&&contains(d.tags,filterTags)) {
            count++;LinearLayout c=card(results);c.addView(label(categoryLabel(d.category).toUpperCase(locale),11,GREEN));c.addView(heading(d.title,18));
            int pages=0;try{pages=engine.pages(d).length();}catch(Exception ignored){}
            String date=new java.text.SimpleDateFormat(getString(R.string.card_date_format),locale).format(new Date(d.created));
            c.addView(label(getString(R.string.card_meta,date,getResources().getQuantityString(R.plurals.page_count,pages,pages)),13,MUTED));
            if(!d.company.isEmpty()||!d.total.isEmpty())c.addView(label(d.total.isEmpty()?d.company:getString(R.string.card_company_total,d.company,d.total),13,INK));
            c.setContentDescription(getString(R.string.cd_open_document,d.title));c.setOnClickListener(v->{if(!busy){selected=d;detail();}});
        }
        if(count==0){LinearLayout c=card(results);c.addView(heading(getString(documents.isEmpty()?R.string.home_empty_title:R.string.home_no_results_title),20));c.addView(label(getString(documents.isEmpty()?R.string.home_empty_body:R.string.home_no_results_body),15,MUTED));}
    }
    private boolean contains(String value,String filter){return Metadata.normalize(value).contains(Metadata.normalize(filter));}
    private boolean hasFilters(){return !(filterCompany+filterDate+filterTotal+filterTags).isEmpty();}
    private void filters(){LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));EditText company=input(content,getString(R.string.filters_company),filterCompany,false),date=input(content,getString(R.string.filters_date),filterDate,false),total=input(content,getString(R.string.filters_total),filterTotal,false),tags=input(content,getString(R.string.filters_tag),filterTags,false);new AlertDialog.Builder(this).setTitle(R.string.filters_title).setView(content).setNegativeButton(R.string.action_cancel,null).setNeutralButton(R.string.action_clear,(a,b)->{filterCompany="";filterDate="";filterTotal="";filterTags="";home();}).setPositiveButton(R.string.action_apply,(a,b)->{filterCompany=company.getText().toString().trim();filterDate=date.getText().toString().trim();filterTotal=total.getText().toString().trim();filterTags=tags.getText().toString().trim();home();}).show();}
    private void reload(){run(getString(R.string.status_loading_documents),()->documents=db.documents().all(),()->{if(restoreId!=null){for(Document d:documents)if(d.id.equals(restoreId))selected=d;restoreId=null;}if(selected==null)home();else detail();});}
    private void newDocument(boolean append) {
        appendId=append&&selected!=null?selected.id:null;
        new AlertDialog.Builder(this).setTitle(append?R.string.new_document_append_title:R.string.new_document_title).setItems(R.array.new_document_sources,(dialog,which)->{
            if(which==0)startScanner();else importer.launch(which==1?new String[]{"image/*"}:which==2?new String[]{"application/pdf"}:new String[]{"image/*","application/pdf"});
        }).setNegativeButton(R.string.action_cancel,null).show();
    }
    private void startScanner() {
        var options=new GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(50).setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build();
        busy=true;status.setText(R.string.status_preparing_camera);status.setVisibility(View.VISIBLE);
        GmsDocumentScanning.getClient(options).getStartScanIntent(this).addOnSuccessListener(sender->{busy=false;status.setVisibility(View.GONE);scanner.launch(new IntentSenderRequest.Builder(sender).build());}).addOnFailureListener(e->{busy=false;status.setVisibility(View.GONE);error(getString(R.string.error_scanner_unavailable,e.getLocalizedMessage()));});
    }
    private void importFiles(List<Uri> uris) {
        String target=appendId;appendId=null;
        run(getString(R.string.status_importing),()->{
            Document doc=null;if(target!=null)for(Document item:db.documents().all())if(item.id.equals(target))doc=item;
            if(target!=null&&doc==null)throw new IOException(getString(R.string.error_document_missing));
            boolean fresh=doc==null;if(fresh)doc=engine.create();
            try{engine.importUris(doc,uris);db.documents().save(doc);engine.sweep(doc);selected=doc;documents=db.documents().all();}catch(Exception e){if(fresh)engine.deleteFiles(doc);throw e;}
        },this::detail);
    }
    private void detail() {
        if(selected==null){home();return;} Document d=selected;
        layout(getString(R.string.detail_eyebrow),d.title,getString(R.string.detail_subtitle,categoryLabel(d.category)));button(body,getString(R.string.detail_back),false,()->{selected=null;home();});
        LinearLayout actions=card(body);button(actions,getString(R.string.detail_export),true,this::exportMenu);button(actions,getString(R.string.detail_edit_metadata),false,this::editMetadata);
        body.addView(heading(getString(R.string.detail_pages),21));
        try {JSONArray pages=engine.pages(d);for(int i=0;i<pages.length();i++){
            int index=i;JSONObject item=pages.getJSONObject(i);LinearLayout c=card(body);c.addView(heading(getString(R.string.page_title,i+1),16));
            ImageView thumb=new ImageView(this);thumb.setAdjustViewBounds(true);thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);thumb.setBackground(shape(Color.rgb(236,239,241),10));thumb.setContentDescription(getString(R.string.cd_page_preview,i+1));
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=4;thumb.setImageBitmap(BitmapFactory.decodeFile(item.getString("path"),opts));c.addView(thumb,new LinearLayout.LayoutParams(-1,dp(235)));
            button(c,getString(R.string.detail_crop),false,()->cropPage(index));
            button(c,getString(R.string.detail_adjust),false,()->pageMenu(index));
        }}catch(Exception e){error(R.string.error_pages_unreadable);}
        button(body,getString(R.string.detail_add_pages),false,()->newDocument(true));
        LinearLayout metadata=card(body);metadata.addView(heading(getString(R.string.detail_metadata_title),19));metadata.addView(label(getString(R.string.detail_metadata_note),12,MUTED));
        metadata.addView(label(getString(R.string.detail_metadata_body,empty(d.company),empty(d.nif),empty(d.date),empty(d.total),empty(d.tags)),14,INK));
        LinearLayout ocr=card(body);ocr.addView(heading(getString(R.string.detail_ocr_title),19));ocr.addView(label(getString(R.string.detail_language,d.language.equals("und")?getString(R.string.detail_language_unknown):d.language),12,MUTED));TextView text=label(d.text.isEmpty()?getString(R.string.detail_no_text):d.text,14,INK);text.setTextIsSelectable(true);ocr.addView(text);
        button(ocr,getString(R.string.detail_copy_text),false,()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(d.title,d.text));toast(R.string.toast_text_copied);});
        if(!d.barcodes.isEmpty()){ocr.addView(heading(getString(R.string.detail_barcodes),16));TextView codes=label(d.barcodes,14,INK);codes.setTextIsSelectable(true);ocr.addView(codes);}
        button(body,getString(R.string.detail_delete),false,()->new AlertDialog.Builder(this).setTitle(R.string.detail_delete_title).setMessage(R.string.detail_delete_message).setNegativeButton(R.string.action_cancel,null).setPositiveButton(R.string.action_delete,(a,b)->run(getString(R.string.status_deleting),()->{db.documents().delete(d);engine.deleteFiles(d);documents=db.documents().all();selected=null;},this::home)).show());
    }
    private String empty(String s){return s.isEmpty()?getString(R.string.value_empty):s;}
    private void cropPage(int index){
        try{Document d=selected.copy();BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;Bitmap preview=BitmapFactory.decodeFile(engine.pages(d).getJSONObject(index).getString("path"),options);if(preview==null)throw new IOException(getString(R.string.error_image_unavailable));CropView view=new CropView(this,preview);LinearLayout content=column();content.setPadding(dp(12),dp(8),dp(12),0);content.addView(label(getString(R.string.crop_hint),14,MUTED));content.addView(view,new LinearLayout.LayoutParams(-1,dp(380)));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.crop_title).setView(content).setNegativeButton(R.string.action_cancel,null).setPositiveButton(R.string.action_apply,(a,b)->{float[] corners=view.corners();run(getString(R.string.status_cropping),()->{engine.crop(d,index,corners);db.documents().save(d);engine.sweep(d);selected=d;documents=db.documents().all();},this::detail);}).create();dialog.setOnDismissListener(v->preview.recycle());dialog.show();
        }catch(Exception e){error(e.getLocalizedMessage());}
    }
    private void pageMenu(int index){
        new AlertDialog.Builder(this).setTitle(getString(R.string.page_title,index+1)).setItems(R.array.page_actions,(dialog,which)->{
            Document d=selected.copy();
            if(which==7){new AlertDialog.Builder(this).setTitle(R.string.page_delete_title).setNegativeButton(R.string.action_cancel,null).setPositiveButton(R.string.action_delete,(a,b)->changePage(d,index,which)).show();}else changePage(d,index,which);
        }).setNegativeButton(R.string.action_close,null).show();
    }
    private void changePage(Document d,int index,int which){run(getString(R.string.status_updating_page),()->{
        JSONArray pages=engine.pages(d);
        if(which<=4)engine.edit(d,index,PAGE_ACTIONS[which]);
        else if(which==7){if(pages.length()==1)throw new IOException(getString(R.string.error_last_page));pages.remove(index);d.pages=pages.toString();engine.rebuild(d);}
        else{int dest=index+(which==5?-1:1);if(dest<0||dest>=pages.length())return;Object old=pages.get(index);pages.put(index,pages.get(dest));pages.put(dest,old);d.pages=pages.toString();engine.rebuild(d);}
        db.documents().save(d);engine.sweep(d);selected=d;documents=db.documents().all();
    },this::detail);}
    private void editMetadata(){
        Document d=selected;LinearLayout content=column();content.setPadding(dp(18),dp(6),dp(18),dp(6));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(label(getString(R.string.field_name),12,MUTED));EditText title=input(content,getString(R.string.field_name),d.title,false);
        // The spinner shows the localized labels but keeps the canonical key that the database stores.
        Spinner cats=new Spinner(this);String[] values=Arrays.copyOfRange(CATEGORIES,1,CATEGORIES.length);String[] labels=new String[values.length];for(int i=0;i<values.length;i++)labels[i]=categoryLabel(values[i]);
        cats.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));cats.setSelection(Math.max(0,Arrays.asList(values).indexOf(d.category)));cats.setContentDescription(getString(R.string.cd_document_category));content.addView(cats);
        content.addView(label(getString(R.string.field_company),12,MUTED));EditText company=input(content,getString(R.string.field_company),d.company,false);content.addView(label(getString(R.string.field_nif),12,MUTED));EditText nif=input(content,getString(R.string.field_nif),d.nif,false);content.addView(label(getString(R.string.field_date),12,MUTED));EditText date=input(content,getString(R.string.field_date),d.date,false);content.addView(label(getString(R.string.field_total),12,MUTED));EditText total=input(content,getString(R.string.field_total),d.total,false);content.addView(label(getString(R.string.field_tags),12,MUTED));EditText tags=input(content,getString(R.string.field_tags_hint),d.tags,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.edit_title).setView(scroll).setNegativeButton(R.string.action_cancel,null).setPositiveButton(R.string.action_save,null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(title.getText().toString().trim().isEmpty()){title.setError(getString(R.string.error_name_required));return;}d.title=title.getText().toString().trim();d.category=Metadata.edit(d,Metadata.CATEGORY,d.category,values[cats.getSelectedItemPosition()]);d.company=Metadata.edit(d,Metadata.COMPANY,d.company,company.getText().toString());d.nif=Metadata.edit(d,Metadata.NIF,d.nif,nif.getText().toString());d.date=Metadata.edit(d,Metadata.DATE,d.date,date.getText().toString());d.total=Metadata.edit(d,Metadata.TOTAL,d.total,total.getText().toString());d.tags=tags.getText().toString();dialog.dismiss();run(getString(R.string.status_saving),()->{db.documents().save(d);documents=db.documents().all();},this::detail);}));dialog.show();
    }
    private void exportMenu(){new AlertDialog.Builder(this).setTitle(R.string.export_title).setItems(R.array.export_options,(dialog,which)->{
        Document d=selected;String type=which==1?"txt":which==2?"zip":which==3?"png.zip":"pdf";
        run(getString(R.string.status_preparing_export),()->exportFile=engine.export(d,type),()->{exportMime=type.equals("pdf")?"application/pdf":type.equals("txt")?"text/plain":"application/zip";if(which==4)share();else if(which==5)print();else{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(exportMime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,exportFile.getName());saver.launch(intent);}});
    }).setNegativeButton(R.string.action_cancel,null).show();}
    private void share(){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",exportFile);Intent intent=new Intent(Intent.ACTION_SEND).setType(exportMime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(getString(R.string.share_clip_label),uri));startActivity(Intent.createChooser(intent,getString(R.string.share_chooser)));}
    private void print(){File file=exportFile;((PrintManager)getSystemService(PRINT_SERVICE)).print(selected.title,new PrintDocumentAdapter(){
        public void onLayout(PrintAttributes old,PrintAttributes next,CancellationSignal cancel,LayoutResultCallback callback,Bundle extras){if(cancel.isCanceled()){callback.onLayoutCancelled();return;}callback.onLayoutFinished(new PrintDocumentInfo.Builder(file.getName()).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),true);}
        public void onWrite(PageRange[] ranges,ParcelFileDescriptor dest,CancellationSignal cancel,WriteResultCallback callback){worker.execute(()->{try(InputStream in=new FileInputStream(file);OutputStream out=new FileOutputStream(dest.getFileDescriptor())){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(cancel.isCanceled()){callback.onWriteCancelled();return;}out.write(buffer,0,n);}callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});}catch(Exception e){callback.onWriteFailed(e.getLocalizedMessage());}});}
    },null);}
    private interface Job{void run()throws Exception;}
    private void run(String message,Job job,Runnable success){busy=true;if(status!=null){status.setText(message);status.setVisibility(View.VISIBLE);}worker.execute(()->{try{job.run();runOnUiThread(()->{if(isDestroyed())return;busy=false;status.setVisibility(View.GONE);success.run();});}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy=false;status.setVisibility(View.GONE);error(e.getLocalizedMessage()==null?getString(R.string.error_generic):e.getLocalizedMessage());});}});}
    private void toast(int text){toast(getString(text));}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void error(int text){error(getString(text));}
    private void error(String text){new AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(text).setPositiveButton(R.string.action_close,null).show();}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
}
