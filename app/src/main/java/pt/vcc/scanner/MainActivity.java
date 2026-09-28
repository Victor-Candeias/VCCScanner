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
import androidx.room.Room;
import com.google.mlkit.vision.documentscanner.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends ComponentActivity {
    private static final int INK = Color.rgb(25,40,55), GREEN = Color.rgb(23,107,88), MUTED = Color.rgb(107,120,134), BG = Color.rgb(245,247,249);
    private static final String[] CATEGORIES = {"Todos", "Faturas", "Recibos", "Contratos", "Manuais", "Pessoais", "Outros"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScannerDatabase db; private DocumentEngine engine;
    private LinearLayout root, body, results; private TextView status;
    private List<Document> documents = new ArrayList<>(); private Document selected;
    private String category = "Todos", query = "", appendId, restoreId;
    private String filterCompany="",filterDate="",filterTotal="",filterTags="";
    private int searchGeneration;
    private boolean busy; private File exportFile; private String exportMime;
    private ActivityResultLauncher<IntentSenderRequest> scanner;
    private ActivityResultLauncher<String[]> importer;
    private ActivityResultLauncher<Intent> saver;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = Room.databaseBuilder(getApplicationContext(), ScannerDatabase.class, "vcc-scanner.db").addMigrations(ScannerDatabase.MIGRATION_1_2, ScannerDatabase.MIGRATION_2_3).build(); engine = new DocumentEngine(this);
        if (state != null) { appendId = state.getString("appendId"); restoreId = state.getString("selectedId"); category = state.getString("category", "Todos"); query = state.getString("query", ""); String path=state.getString("export"); if(path!=null) exportFile=new File(path); exportMime=state.getString("mime"); }
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
                run("A guardar ficheiro…", () -> { try(InputStream in=new FileInputStream(source); OutputStream out=getContentResolver().openOutputStream(uri)) { if(out==null)throw new IOException("Destino indisponível."); copy(in,out); } }, () -> toast("Ficheiro guardado."));
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { if (busy) { toast("Aguarde até terminar o processamento."); return; } if(selected!=null) { selected=null; home(); } else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); } } });
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
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,dp(5),0,dp(5)); parent.addView(b,lp); b.setOnClickListener(v->{if(!busy)action.run();else toast("Aguarde até terminar o processamento.");}); return b;
    }
    private EditText input(LinearLayout parent,String hint,String value,boolean multiline) { EditText e=new EditText(this); e.setTextSize(15); e.setTextColor(INK); e.setHint(hint); e.setText(value); e.setSingleLine(!multiline); e.setPadding(dp(12),dp(12),dp(12),dp(12)); if(multiline)e.setMinLines(3); parent.addView(e,new LinearLayout.LayoutParams(-1,-2)); return e; }
    private void layout(String eyebrow,String title,String subtitle) {
        root=column(); root.setBackgroundColor(BG); root.setPadding(dp(22),dp(8),dp(22),0); setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{var bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.ime());v.setPadding(dp(22)+bars.left,dp(8)+bars.top,dp(22)+bars.right,bars.bottom);return insets;});
        TextView brand=label(eyebrow,12,GREEN); brand.setLetterSpacing(.13f); brand.setTypeface(null,Typeface.BOLD); root.addView(brand);
        root.addView(heading(title,30)); if(subtitle!=null)root.addView(label(subtitle,14,MUTED));
        status=label(busy?"A processar…":"",13,GREEN); status.setVisibility(busy?View.VISIBLE:View.GONE); root.addView(status);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); body=column(); body.setPadding(0,dp(12),0,dp(20)); scroll.addView(body);
    }
    private void home() {
        selected=null; layout("VCC  /  SCANNER", "O seu arquivo,\nsimplificado.", "Digitalize. Encontre. Leve consigo.");
        LinearLayout hero=card(body); hero.setBackground(shape(Color.rgb(219,238,228),20)); hero.addView(heading("Papel em ordem. Espaço para mais.",20)); hero.addView(label("Transforme documentos em ficheiros pesquisáveis, guardados no seu dispositivo.",14,GREEN)); button(hero,"＋  Nova digitalização",true,()->newDocument(false));
        EditText search=input(body,"Pesquisar texto, NIF, empresa…",query,false);
        button(body,hasFilters()?"Filtros ativos  ·  Alterar":"Filtrar por data, empresa, valor ou etiquetas",false,this::filters);
        HorizontalScrollView chips=new HorizontalScrollView(this); chips.setHorizontalScrollBarEnabled(false); LinearLayout row=new LinearLayout(this); chips.addView(row); body.addView(chips);
        for(String cat:CATEGORIES) { TextView chip=label(cat,13,cat.equals(category)?Color.WHITE:GREEN); chip.setPadding(dp(14),dp(10),dp(14),dp(10)); chip.setBackground(shape(cat.equals(category)?GREEN:Color.rgb(231,238,235),20)); LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,dp(10),dp(6),dp(10));row.addView(chip,lp);chip.setOnClickListener(v->{category=cat;home();}); }
        LinearLayout summary=new LinearLayout(this); TextView title=heading("Documentos",19);summary.addView(title,new LinearLayout.LayoutParams(0,-2,1));summary.addView(label(documents.size()+" no dispositivo",12,MUTED));body.addView(summary);
        results=column();body.addView(results);renderResults();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){query=s.toString();renderResults();}public void afterTextChanged(Editable e){}});
        gap(body,14);body.addView(label("PRIVADO POR NATUREZA\nSem conta. Documentos armazenados localmente.",12,MUTED));
    }
    private void renderResults() {
        if(results==null)return;int generation=++searchGeneration;String fts=Metadata.ftsQuery(query);
        if(fts.isEmpty()){renderDocuments(documents);return;}
        worker.execute(()->{try{List<Document> found=db.documents().search(fts);runOnUiThread(()->{if(!isDestroyed()&&selected==null&&generation==searchGeneration)renderDocuments(found);});}catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&generation==searchGeneration)error("Não foi possível pesquisar os documentos.");});}});
    }
    private void renderDocuments(List<Document> found){
        results.removeAllViews(); int count=0;
        for(Document d:found) if((category.equals("Todos")||category.equals(d.category))&&contains(d.company,filterCompany)&&contains(d.date,filterDate)&&contains(d.total,filterTotal)&&contains(d.tags,filterTags)) {
            count++;LinearLayout c=card(results);c.addView(label(d.category.toUpperCase(new Locale("pt","PT")),11,GREEN));c.addView(heading(d.title,18));
            int pages=0;try{pages=engine.pages(d).length();}catch(Exception ignored){}
            c.addView(label(new java.text.SimpleDateFormat("dd MMM yyyy",new Locale("pt","PT")).format(new Date(d.created))+"  ·  "+pages+" página"+(pages==1?"":"s"),13,MUTED));
            if(!d.company.isEmpty()||!d.total.isEmpty())c.addView(label(d.company+(d.total.isEmpty()?"":"  ·  "+d.total+" €"),13,INK));
            c.setContentDescription("Abrir "+d.title);c.setOnClickListener(v->{if(!busy){selected=d;detail();}});
        }
        if(count==0){LinearLayout c=card(results);c.addView(heading(documents.isEmpty()?"O seu próximo documento começa aqui":"Nenhum documento encontrado",20));c.addView(label(documents.isEmpty()?"Digitalize com a câmara ou importe imagens e PDFs. O texto fica disponível para pesquisa.":"Experimente outra palavra ou categoria.",15,MUTED));}
    }
    private boolean contains(String value,String filter){return Metadata.normalize(value).contains(Metadata.normalize(filter));}
    private boolean hasFilters(){return !(filterCompany+filterDate+filterTotal+filterTags).isEmpty();}
    private void filters(){LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));EditText company=input(content,"Empresa",filterCompany,false),date=input(content,"Data (ex.: 09/2026)",filterDate,false),total=input(content,"Valor (ex.: 19,70)",filterTotal,false),tags=input(content,"Etiqueta",filterTags,false);new AlertDialog.Builder(this).setTitle("Filtrar documentos").setView(content).setNegativeButton("Cancelar",null).setNeutralButton("Limpar",(a,b)->{filterCompany="";filterDate="";filterTotal="";filterTags="";home();}).setPositiveButton("Aplicar",(a,b)->{filterCompany=company.getText().toString().trim();filterDate=date.getText().toString().trim();filterTotal=total.getText().toString().trim();filterTags=tags.getText().toString().trim();home();}).show();}
    private void reload(){run("A carregar documentos…",()->documents=db.documents().all(),()->{if(restoreId!=null){for(Document d:documents)if(d.id.equals(restoreId))selected=d;restoreId=null;}if(selected==null)home();else detail();});}
    private void newDocument(boolean append) {
        appendId=append&&selected!=null?selected.id:null;
        new AlertDialog.Builder(this).setTitle(append?"Adicionar páginas":"Nova digitalização").setItems(new String[]{"Digitalizar com a câmara", "Importar imagens da galeria", "Importar PDF", "Importar ficheiro (imagem ou PDF)"},(dialog,which)->{
            if(which==0)startScanner();else importer.launch(which==1?new String[]{"image/*"}:which==2?new String[]{"application/pdf"}:new String[]{"image/*","application/pdf"});
        }).setNegativeButton("Cancelar",null).show();
    }
    private void startScanner() {
        var options=new GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(50).setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build();
        busy=true;status.setText("A preparar a câmara…");status.setVisibility(View.VISIBLE);
        GmsDocumentScanning.getClient(options).getStartScanIntent(this).addOnSuccessListener(sender->{busy=false;status.setVisibility(View.GONE);scanner.launch(new IntentSenderRequest.Builder(sender).build());}).addOnFailureListener(e->{busy=false;status.setVisibility(View.GONE);error("Scanner indisponível. Verifique os serviços Google Play e a ligação à Internet na primeira utilização. Pode importar imagens ou PDFs.\n\n"+e.getLocalizedMessage());});
    }
    private void importFiles(List<Uri> uris) {
        String target=appendId;appendId=null;
        run("A importar páginas e reconhecer texto…",()->{
            Document doc=null;if(target!=null)for(Document item:db.documents().all())if(item.id.equals(target))doc=item;
            if(target!=null&&doc==null)throw new IOException("O documento já não existe.");
            boolean fresh=doc==null;if(fresh)doc=engine.create();
            try{engine.importUris(doc,uris);db.documents().save(doc);engine.sweep(doc);selected=doc;documents=db.documents().all();}catch(Exception e){if(fresh)engine.deleteFiles(doc);throw e;}
        },this::detail);
    }
    private void detail() {
        if(selected==null){home();return;} Document d=selected;
        layout("VCC  /  DOCUMENTO",d.title,d.category+"  ·  Guardado no dispositivo");button(body,"‹  Todos os documentos",false,()->{selected=null;home();});
        LinearLayout actions=card(body);button(actions,"Guardar / Exportar",true,this::exportMenu);button(actions,"Editar nome e dados",false,this::editMetadata);
        body.addView(heading("Páginas",21));
        try {JSONArray pages=engine.pages(d);for(int i=0;i<pages.length();i++){
            int index=i;JSONObject item=pages.getJSONObject(i);LinearLayout c=card(body);c.addView(heading("Página "+(i+1),16));
            ImageView thumb=new ImageView(this);thumb.setAdjustViewBounds(true);thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);thumb.setBackground(shape(Color.rgb(236,239,241),10));thumb.setContentDescription("Pré-visualização da página "+(i+1));
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=4;thumb.setImageBitmap(BitmapFactory.decodeFile(item.getString("path"),opts));c.addView(thumb,new LinearLayout.LayoutParams(-1,dp(235)));
            button(c,"Recortar / corrigir perspetiva",false,()->cropPage(index));
            button(c,"Ajustar página  ·  Rodar / Filtros / Ordem",false,()->pageMenu(index));
        }}catch(Exception e){error("Não foi possível ler as páginas.");}
        button(body,"＋  Adicionar páginas",false,()->newDocument(true));
        LinearLayout metadata=card(body);metadata.addView(heading("Dados do documento",19));metadata.addView(label("Extração automática — confirme os valores antes de os utilizar.",12,MUTED));
        metadata.addView(label("Empresa: "+empty(d.company)+"\nNIF: "+empty(d.nif)+"\nData: "+empty(d.date)+"\nTotal: "+empty(d.total)+"\nEtiquetas: "+empty(d.tags),14,INK));
        LinearLayout ocr=card(body);ocr.addView(heading("Texto reconhecido",19));ocr.addView(label("Idioma: "+(d.language.equals("und")?"não identificado":d.language),12,MUTED));TextView text=label(d.text.isEmpty()?"Não foi encontrado texto nesta imagem.":d.text,14,INK);text.setTextIsSelectable(true);ocr.addView(text);
        button(ocr,"Copiar texto",false,()->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(d.title,d.text));toast("Texto copiado.");});
        if(!d.barcodes.isEmpty()){ocr.addView(heading("QR / códigos de barras",16));TextView codes=label(d.barcodes,14,INK);codes.setTextIsSelectable(true);ocr.addView(codes);}
        button(body,"Eliminar documento",false,()->new AlertDialog.Builder(this).setTitle("Eliminar documento?").setMessage("O documento e todas as suas páginas serão eliminados deste dispositivo.").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(a,b)->run("A eliminar…",()->{db.documents().delete(d);engine.deleteFiles(d);documents=db.documents().all();selected=null;},this::home)).show());
    }
    private String empty(String s){return s.isEmpty()?"—":s;}
    private void cropPage(int index){
        try{Document d=selected.copy();BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;Bitmap preview=BitmapFactory.decodeFile(engine.pages(d).getJSONObject(index).getString("path"),options);if(preview==null)throw new IOException("Imagem indisponível.");CropView view=new CropView(this,preview);LinearLayout content=column();content.setPadding(dp(12),dp(8),dp(12),0);content.addView(label("Arraste os quatro cantos até ao limite do documento.",14,MUTED));content.addView(view,new LinearLayout.LayoutParams(-1,dp(380)));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Ajustar recorte").setView(content).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",(a,b)->{float[] corners=view.corners();run("A corrigir perspetiva e reconhecer texto…",()->{engine.crop(d,index,corners);db.documents().save(d);engine.sweep(d);selected=d;documents=db.documents().all();},this::detail);}).create();dialog.setOnDismissListener(v->preview.recycle());dialog.show();
        }catch(Exception e){error(e.getLocalizedMessage());}
    }
    private void pageMenu(int index){
        String[] options={"Rodar","Original","Escala de cinzentos","Preto e branco","Documento","Mover para cima","Mover para baixo","Eliminar página"};
        new AlertDialog.Builder(this).setTitle("Página "+(index+1)).setItems(options,(dialog,which)->{
            Document d=selected.copy();
            if(which==7){new AlertDialog.Builder(this).setTitle("Eliminar esta página?").setNegativeButton("Cancelar",null).setPositiveButton("Eliminar",(a,b)->changePage(d,index,which,options)).show();}else changePage(d,index,which,options);
        }).setNegativeButton("Fechar",null).show();
    }
    private void changePage(Document d,int index,int which,String[] options){run("A atualizar a página e o texto…",()->{
        JSONArray pages=engine.pages(d);
        if(which<=4)engine.edit(d,index,options[which]);
        else if(which==7){if(pages.length()==1)throw new IOException("O documento deve ter pelo menos uma página. Para o remover, use Eliminar documento.");pages.remove(index);d.pages=pages.toString();engine.rebuild(d);}
        else{int dest=index+(which==5?-1:1);if(dest<0||dest>=pages.length())return;Object old=pages.get(index);pages.put(index,pages.get(dest));pages.put(dest,old);d.pages=pages.toString();engine.rebuild(d);}
        db.documents().save(d);engine.sweep(d);selected=d;documents=db.documents().all();
    },this::detail);}
    private void editMetadata(){
        Document d=selected;LinearLayout content=column();content.setPadding(dp(18),dp(6),dp(18),dp(6));ScrollView scroll=new ScrollView(this);scroll.addView(content);
        content.addView(label("Nome",12,MUTED));EditText title=input(content,"Nome",d.title,false);
        Spinner cats=new Spinner(this);String[] values=Arrays.copyOfRange(CATEGORIES,1,CATEGORIES.length);cats.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));cats.setSelection(Math.max(0,Arrays.asList(values).indexOf(d.category)));content.addView(cats);
        content.addView(label("Empresa",12,MUTED));EditText company=input(content,"Empresa",d.company,false);content.addView(label("NIF",12,MUTED));EditText nif=input(content,"NIF",d.nif,false);content.addView(label("Data",12,MUTED));EditText date=input(content,"Data",d.date,false);content.addView(label("Total",12,MUTED));EditText total=input(content,"Total",d.total,false);content.addView(label("Etiquetas",12,MUTED));EditText tags=input(content,"Etiquetas separadas por vírgulas",d.tags,false);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Editar documento").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(title.getText().toString().trim().isEmpty()){title.setError("Indique um nome");return;}d.title=title.getText().toString().trim();d.category=Metadata.edit(d,Metadata.CATEGORY,d.category,(String)cats.getSelectedItem());d.company=Metadata.edit(d,Metadata.COMPANY,d.company,company.getText().toString());d.nif=Metadata.edit(d,Metadata.NIF,d.nif,nif.getText().toString());d.date=Metadata.edit(d,Metadata.DATE,d.date,date.getText().toString());d.total=Metadata.edit(d,Metadata.TOTAL,d.total,total.getText().toString());d.tags=tags.getText().toString();dialog.dismiss();run("A guardar…",()->{db.documents().save(d);documents=db.documents().all();},this::detail);}));dialog.show();
    }
    private void exportMenu(){new AlertDialog.Builder(this).setTitle("Guardar / Exportar").setItems(new String[]{"PDF pesquisável", "Texto reconhecido (.txt)", "Imagens JPG (.zip)", "Imagens PNG (.zip)", "Partilhar PDF", "Imprimir"},(dialog,which)->{
        Document d=selected;String type=which==1?"txt":which==2?"zip":which==3?"png.zip":"pdf";
        run("A preparar exportação…",()->exportFile=engine.export(d,type),()->{exportMime=type.equals("pdf")?"application/pdf":type.equals("txt")?"text/plain":"application/zip";if(which==4)share();else if(which==5)print();else{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(exportMime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,exportFile.getName());saver.launch(intent);}});
    }).setNegativeButton("Cancelar",null).show();}
    private void share(){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",exportFile);Intent intent=new Intent(Intent.ACTION_SEND).setType(exportMime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Documento",uri));startActivity(Intent.createChooser(intent,"Partilhar documento"));}
    private void print(){File file=exportFile;((PrintManager)getSystemService(PRINT_SERVICE)).print(selected.title,new PrintDocumentAdapter(){
        public void onLayout(PrintAttributes old,PrintAttributes next,CancellationSignal cancel,LayoutResultCallback callback,Bundle extras){if(cancel.isCanceled()){callback.onLayoutCancelled();return;}callback.onLayoutFinished(new PrintDocumentInfo.Builder(file.getName()).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),true);}
        public void onWrite(PageRange[] ranges,ParcelFileDescriptor dest,CancellationSignal cancel,WriteResultCallback callback){worker.execute(()->{try(InputStream in=new FileInputStream(file);OutputStream out=new FileOutputStream(dest.getFileDescriptor())){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(cancel.isCanceled()){callback.onWriteCancelled();return;}out.write(buffer,0,n);}callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});}catch(Exception e){callback.onWriteFailed(e.getLocalizedMessage());}});}
    },null);}
    private interface Job{void run()throws Exception;}
    private void run(String message,Job job,Runnable success){busy=true;if(status!=null){status.setText(message);status.setVisibility(View.VISIBLE);}worker.execute(()->{try{job.run();runOnUiThread(()->{if(isDestroyed())return;busy=false;status.setVisibility(View.GONE);success.run();});}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy=false;status.setVisibility(View.GONE);error(e.getLocalizedMessage()==null?"Não foi possível concluir a operação.":e.getLocalizedMessage());});}});}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private void error(String text){new AlertDialog.Builder(this).setTitle("Não foi possível concluir").setMessage(text).setPositiveButton("Fechar",null).show();}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
}
