package pt.vcc.scanner;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

@Entity(tableName = "documents")
public class Document {
    @PrimaryKey @NonNull public String id = "";
    public String title = "";
    public String category = "Outros";
    public String text = "";
    public String company = "";
    public String nif = "";
    public String date = "";
    public String total = "";
    public String tags = "";
    public String language = "";
    public String barcodes = "";
    public String pages = "[]";
    /** Comma separated names of the fields corrected by hand, which automatic extraction must not overwrite. */
    public String edited = "";
    public long created;
    public Document copy(){
        Document d=new Document();d.id=id;d.title=title;d.category=category;d.text=text;d.company=company;d.nif=nif;d.date=date;d.total=total;d.tags=tags;d.language=language;d.barcodes=barcodes;d.pages=pages;d.edited=edited;d.created=created;return d;
    }
}
