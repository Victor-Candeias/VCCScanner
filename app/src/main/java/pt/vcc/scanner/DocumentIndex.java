package pt.vcc.scanner;
import androidx.room.Entity;
import androidx.room.Fts4;
@Fts4 @Entity(tableName="document_index")
public class DocumentIndex {
    public String documentId="";
    public String content="";
    public static DocumentIndex from(Document d){DocumentIndex index=new DocumentIndex();index.documentId=d.id;index.content=Metadata.normalize(d.title+" "+d.text+" "+d.company+" "+d.nif+" "+d.date+" "+d.total+" "+d.tags+" "+d.barcodes);return index;}
}
